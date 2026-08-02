package dev.pleiades.masamune.flow.runtime

import dev.pleiades.masamune.flow.expr.Value
import dev.pleiades.masamune.flow.model.Port
import java.math.BigInteger
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistence for fibers — the mechanism behind Automate's resume-after-shutdown.
 *
 * The scheduler calls [save] at every block boundary and [loadAll] once on start. The contract
 * is deliberately tiny (save one, load a flow's set, drop one) because the guarantee it backs
 * is large: a fiber that was mid-flow when the process died comes back at exactly its last
 * block, not restarted and not skipped past. Anything richer than this contract is a place for
 * the resume invariant to develop a crack.
 */
interface FiberStore {
    suspend fun save(fiber: Fiber)

    /** Every persisted fiber for [flowId], terminal ones included so history survives a look. */
    suspend fun loadAll(flowId: String): List<Fiber>

    suspend fun delete(fiberId: String)
}

/**
 * In-memory store. Correct and complete for tests and for a flow that does not need to outlive
 * the process; **not** durable across shutdown, so it does not on its own deliver the resume
 * guarantee — a disk-backed implementation does, over this same interface.
 *
 * It is a real implementation, not a stand-in: the scheduler cannot tell it apart from a
 * durable one, which is exactly what makes the durable one a drop-in and keeps the scheduler
 * innocent of where fibers live.
 */
class InMemoryFiberStore : FiberStore {
    private val byId = LinkedHashMap<String, Fiber>()

    override suspend fun save(fiber: Fiber) {
        byId[fiber.id] = fiber
    }

    override suspend fun loadAll(flowId: String): List<Fiber> =
        byId.values.filter { it.flowId == flowId }

    override suspend fun delete(fiberId: String) {
        byId.remove(fiberId)
    }
}

/**
 * Fiber ⇄ JSON, hand-rolled on `org.json`.
 *
 * This is the single definition of a fiber's on-disk shape, and it exists as one object so
 * encode and decode cannot drift: a fiber that writes one way and reads back another is a
 * resume that silently corrupts state, which is the one failure the persistence layer must
 * never have. A disk-backed [FiberStore] and the round-trip test both go through here.
 *
 * The variable frame is the interesting part, because a [Value] is a closed hierarchy that
 * includes [BigInteger] (which JSON has no native type for) and nested arrays/dicts. Each kind
 * is tagged so decode is unambiguous — a bigint and a number that happen to be equal must not
 * collapse into one on the way back, since the expr layer treats them differently.
 */
object FiberCodec {

    fun encode(fiber: Fiber): JSONObject = JSONObject().apply {
        put("id", fiber.id)
        put("flowId", fiber.flowId)
        put("currentNode", fiber.currentNode ?: JSONObject.NULL)
        put("enteredBy", fiber.enteredBy?.name ?: JSONObject.NULL)
        put("status", fiber.status.name)
        put("errorMessage", fiber.errorMessage ?: JSONObject.NULL)
        put("awaitReason", fiber.awaitReason ?: JSONObject.NULL)
        put("variables", JSONObject().apply {
            for ((k, v) in fiber.variables) put(k, encodeValue(v))
        })
    }

    fun decode(json: JSONObject): Fiber {
        val vars = LinkedHashMap<String, Value>()
        val vobj = json.optJSONObject("variables") ?: JSONObject()
        for (key in vobj.keys()) vars[key] = decodeValue(vobj.get(key))
        return Fiber(
            id = json.getString("id"),
            flowId = json.getString("flowId"),
            currentNode = json.optStringOrNull("currentNode"),
            enteredBy = json.optStringOrNull("enteredBy")?.let { Port.valueOf(it) },
            variables = vars,
            status = FiberStatus.valueOf(json.getString("status")),
            errorMessage = json.optStringOrNull("errorMessage"),
            awaitReason = json.optStringOrNull("awaitReason"),
        )
    }

    fun encodeToString(fiber: Fiber): String = encode(fiber).toString()

    fun decodeFromString(text: String): Fiber = decode(JSONObject(text))

    /**
     * A [Value] as a `{type, value}` pair. The type tag is what keeps `Num(1.0)` and
     * `BigInt(1)` distinct across a round-trip — the expr layer's arithmetic rules hinge on
     * that distinction, so losing it here would be a correctness bug disguised as a storage one.
     */
    private fun encodeValue(v: Value): JSONObject = JSONObject().apply {
        when (v) {
            is Value.Num -> { put("t", "num"); put("v", v.value) }
            is Value.BigInt -> { put("t", "bigint"); put("v", v.value.toString()) }
            is Value.Text -> { put("t", "text"); put("v", v.value) }
            is Value.ArrayV -> { put("t", "array"); put("v", JSONArray().apply { v.items.forEach { put(encodeValue(it)) } }) }
            is Value.DictV -> {
                put("t", "dict")
                put("v", JSONObject().apply { for ((k, item) in v.entries) put(k, encodeValue(item)) })
            }
            Value.Null -> put("t", "null")
        }
    }

    private fun decodeValue(raw: Any): Value {
        val o = raw as? JSONObject ?: return Value.Null
        return when (o.getString("t")) {
            "num" -> Value.Num(o.getDouble("v"))
            "bigint" -> Value.BigInt(BigInteger(o.getString("v")))
            "text" -> Value.Text(o.getString("v"))
            "array" -> {
                val arr = o.getJSONArray("v")
                Value.ArrayV((0 until arr.length()).map { decodeValue(arr.get(it)) })
            }
            "dict" -> {
                val d = o.getJSONObject("v")
                Value.DictV(d.keys().asSequence().associateWith { decodeValue(d.get(it)) })
            }
            else -> Value.Null
        }
    }

    /** `optString` returns "" for an absent key AND for an explicit null; we need to tell them apart. */
    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key) || !has(key)) null else getString(key)
}
