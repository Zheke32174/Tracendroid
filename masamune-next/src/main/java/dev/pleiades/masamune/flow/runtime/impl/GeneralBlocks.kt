package dev.pleiades.masamune.flow.runtime.impl

import dev.pleiades.masamune.flow.expr.Value
import dev.pleiades.masamune.flow.model.FlowNode
import dev.pleiades.masamune.flow.model.Port
import dev.pleiades.masamune.flow.runtime.BlockImpl
import dev.pleiades.masamune.flow.runtime.Fiber
import dev.pleiades.masamune.flow.runtime.Outcome
import dev.pleiades.masamune.flow.runtime.forEachKey

/**
 * The General category's runnable blocks: the conditional, the variable and container mutations,
 * and the loop.
 *
 * ### Where a mutation's target comes from
 * The frozen catalog declares these blocks' *value* inputs (an expression) and their *reported*
 * outputs (`varOldValue`, `varLevel`, …), but not the primary in/out variable each one mutates —
 * `Variable set`'s destination, an `Array add`'s array. That variable is a bare **name**, so it
 * lives in the output channel ([FlowNode.outputs], key `variable`; see [targetVariable]). Until
 * the catalog gains a declared output slot to surface it, the editor cannot bind it, so these
 * blocks are driven by execution and tests that set the binding directly — and every one fails
 * *visibly* when the target is unbound rather than mutating nothing, because a silent no-op here
 * makes every downstream block wrong.
 */

/**
 * `Expression true` — the escape hatch decision. The `expression` argument is resolved by the
 * runtime (evaluated when its `fx` toggle is on), so all that is left is to branch on the
 * language's own truthiness: a non-zero, non-NaN, non-empty result takes YES, everything else NO.
 * That single rule is why the other 417 blocks never need a bespoke conditional.
 */
internal class ExpressionTrueBlock : BlockImpl {
    override val specId = "expression_decision"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome =
        Outcome.Proceed(if ((args["expression"] ?: Value.Null).isTrue) Port.YES else Port.NO)
}

/** `Variable set` — assign the resolved value to the bound target variable. */
internal class VariableSetBlock : BlockImpl {
    override val specId = "variable_assign"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val target = node.targetVariable()
            ?: return Outcome.Fail("Variable set has no target variable bound (output 'variable').")
        return Outcome.Proceed(Port.OK, mapOf(target to (args["value"] ?: Value.Null)))
    }
}

/**
 * `Array add` — insert a value into the target array. An absent index appends (the documented
 * default); a numeric index inserts at that position, clamped into range so an out-of-bounds index
 * lands at an end rather than failing. A non-numeric index (Automate's multi-dimensional path form)
 * is not modelled this build and also appends. An unset target array reads as empty and is created.
 */
internal class ArrayAddBlock : BlockImpl {
    override val specId = "array_add"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val target = node.targetVariable()
            ?: return Outcome.Fail("Array add has no target array bound (output 'variable').")
        val items = (fiber.readVariable(target) as? Value.ArrayV)?.items.orEmpty().toMutableList()
        val value = args["value"] ?: Value.Null
        val at = args["index"].asNumOrNull()?.toInt()?.coerceIn(0, items.size) ?: items.size
        items.add(at, value)
        return Outcome.Proceed(Port.OK, mapOf(target to Value.ArrayV(items)))
    }
}

/**
 * `Array set` — replace an element. An index within the array replaces; an absent index, or one
 * equal to the length, appends (the "end of array" default); anything further out fails, since
 * silently growing an array to reach a far index hides an off-by-more bug.
 */
internal class ArraySetBlock : BlockImpl {
    override val specId = "array_set"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val target = node.targetVariable()
            ?: return Outcome.Fail("Array set has no target array bound (output 'variable').")
        val items = (fiber.readVariable(target) as? Value.ArrayV)?.items.orEmpty().toMutableList()
        val value = args["value"] ?: Value.Null
        val at = args["index"].asNumOrNull()?.toInt() ?: items.size
        when {
            at in items.indices -> items[at] = value
            at == items.size -> items.add(value)
            else -> return Outcome.Fail("Array set index $at is outside the array (size ${items.size}).")
        }
        return Outcome.Proceed(Port.OK, mapOf(target to Value.ArrayV(items)))
    }
}

/**
 * `Array remove` — drop an element and report it. The default index is the first element (0). An
 * index outside the array removes nothing and reports Null on `varOldValue`, matching the
 * language's read-past-the-end-is-Null rule rather than failing on an empty or short array.
 */
internal class ArrayRemoveBlock : BlockImpl {
    override val specId = "array_remove"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val target = node.targetVariable()
            ?: return Outcome.Fail("Array remove has no target array bound (output 'variable').")
        val items = (fiber.readVariable(target) as? Value.ArrayV)?.items.orEmpty().toMutableList()
        val at = args["index"].asNumOrNull()?.toInt() ?: 0
        val removed = if (at in items.indices) items.removeAt(at) else Value.Null
        val writes = LinkedHashMap<String, Value>()
        writes[target] = Value.ArrayV(items)
        node.outputs["varOldValue"]?.let { writes[it] = removed }
        return Outcome.Proceed(Port.OK, writes)
    }
}

/** `Dictionary put` — associate a value with a text key, reporting any value it replaced. */
internal class DictionaryPutBlock : BlockImpl {
    override val specId = "dictionary_put"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val target = node.targetVariable()
            ?: return Outcome.Fail("Dictionary put has no target dictionary bound (output 'variable').")
        val entries = (fiber.readVariable(target) as? Value.DictV)?.entries.orEmpty().toMutableMap()
        val key = (args["key"] ?: Value.Null).asText()
        val old = entries[key] ?: Value.Null
        entries[key] = args["value"] ?: Value.Null
        val writes = LinkedHashMap<String, Value>()
        writes[target] = Value.DictV(entries)
        node.outputs["varOldValue"]?.let { writes[it] = old }
        return Outcome.Proceed(Port.OK, writes)
    }
}

/** `Dictionary remove` — drop a key, reporting any value it held. */
internal class DictionaryRemoveBlock : BlockImpl {
    override val specId = "dictionary_remove"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val target = node.targetVariable()
            ?: return Outcome.Fail("Dictionary remove has no target dictionary bound (output 'variable').")
        val entries = (fiber.readVariable(target) as? Value.DictV)?.entries.orEmpty().toMutableMap()
        val key = (args["key"] ?: Value.Null).asText()
        val old = entries.remove(key) ?: Value.Null
        val writes = LinkedHashMap<String, Value>()
        writes[target] = Value.DictV(entries)
        node.outputs["varOldValue"]?.let { writes[it] = old }
        return Outcome.Proceed(Port.OK, writes)
    }
}

/**
 * `Destructuring assign` — spread an array's elements across several variables.
 *
 * The N target names are bound as outputs `var0, var1, …` in index order — element i to `var{i}`,
 * and a slot past the array's end gets Null (read-past-the-end is Null, not an error). Contiguous
 * from zero: the first unbound index ends the spread, so a flow cannot silently skip a middle
 * variable. With no bound targets the block is a no-op that proceeds.
 */
internal class DestructuringAssignBlock : BlockImpl {
    override val specId = "destructuring_assign"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val items = (args["value"] as? Value.ArrayV)?.items.orEmpty()
        val writes = LinkedHashMap<String, Value>()
        var i = 0
        while (true) {
            val name = node.outputs["var$i"]?.takeIf { it.isNotBlank() } ?: break
            writes[name] = items.getOrNull(i) ?: Value.Null
            i++
        }
        return Outcome.Proceed(Port.OK, writes)
    }
}

/**
 * `Android version` — branch on the running platform level. Reads `android.os.Build.VERSION`
 * inside `run` (never at construction, so merely registering the block touches no framework
 * class), reports the level on `varLevel`, and takes YES when it sits within the optional
 * min/max bounds — an unset bound being open on that side.
 */
internal class AndroidVersionBlock : BlockImpl {
    override val specId = "android_version"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val level = android.os.Build.VERSION.SDK_INT
        val min = args["minLevel"].asNumOrNull()
        val max = args["maxLevel"].asNumOrNull()
        val inRange = (min == null || level >= min) && (max == null || level <= max)
        val writes = LinkedHashMap<String, Value>()
        node.outputs["varLevel"]?.let { writes[it] = Value.Num(level.toDouble()) }
        return Outcome.Proceed(if (inRange) Port.YES else Port.NO, writes)
    }
}

/**
 * `For each` — iterate a container, DO⇒YES for one more element, OK⇒NO when exhausted.
 *
 * The cursor — the snapshot of what is being iterated plus the next index — is private fiber state
 * under [forEachKey], namespaced by this node's id so nested and sibling loops never share one.
 * The container is captured **once**, on the first visit, exactly as the donor does: mutating the
 * source variable inside the loop cannot lengthen or shorten the iteration underway. Because the
 * cursor is an ordinary [Value] in the variable frame, a loop in flight survives a process death
 * and resumes on the same element — the persist-resume invariant reaches inside the loop, not just
 * to its edges. Exhaustion clears the cursor (to Null) so re-entering the block later starts fresh.
 *
 * Arrays iterate by element, dictionaries by entry (with the key on `varEntryKey`), text by
 * character, and a number N as the counts 0…N-1 — the four containers the catalog names.
 */
internal class ForEachBlock : BlockImpl {
    override val specId = "for_each"
    override suspend fun run(fiber: Fiber, node: FlowNode, args: Map<String, Value>): Outcome {
        val key = forEachKey(node.id)
        val state = fiber.readVariable(key) as? Value.DictV

        val values: List<Value>
        val keys: List<Value>
        val index: Int
        if (state != null) {
            values = (state.entries["vals"] as? Value.ArrayV)?.items.orEmpty()
            keys = (state.entries["keys"] as? Value.ArrayV)?.items.orEmpty()
            index = (state.entries["i"] as? Value.Num)?.value?.toInt() ?: 0
        } else {
            val captured = capture(args["container"] ?: Value.Null)
            values = captured.first
            keys = captured.second
            index = 0
        }

        if (index >= values.size) {
            // Exhausted: clear the cursor and leave by NO.
            return Outcome.Proceed(Port.NO, mapOf(key to Value.Null))
        }

        val writes = LinkedHashMap<String, Value>()
        writes[key] = Value.DictV(
            mapOf(
                "vals" to Value.ArrayV(values),
                "keys" to Value.ArrayV(keys),
                "i" to Value.Num((index + 1).toDouble()),
            ),
        )
        node.outputs["varEntryValue"]?.let { writes[it] = values[index] }
        node.outputs["varEntryIndex"]?.let { writes[it] = Value.Num(index.toDouble()) }
        node.outputs["varEntryKey"]?.let { writes[it] = keys.getOrElse(index) { Value.Null } }
        return Outcome.Proceed(Port.YES, writes)
    }

    /** Freeze the container into parallel value/key lists once, so the loop is stable against later edits. */
    private fun capture(container: Value): Pair<List<Value>, List<Value>> = when (container) {
        is Value.ArrayV -> container.items to container.items.map { Value.Null }
        is Value.DictV -> container.entries.values.toList() to container.entries.keys.map { Value.Text(it) }
        is Value.Text -> container.value.map { Value.Text(it.toString()) } to container.value.map { Value.Null }
        is Value.Num -> {
            val n = if (container.value.isFinite() && container.value > 0) container.value.toInt() else 0
            (0 until n).map { Value.Num(it.toDouble()) } to (0 until n).map { Value.Null }
        }
        else -> emptyList<Value>() to emptyList()
    }
}
