package com.ai.assistance.operit.core.tools.javascript

import android.content.Context
import android.content.SharedPreferences
import com.ai.assistance.operit.util.AppLogger
import org.json.JSONArray
import org.json.JSONObject

/**
 * SharedPreferences-backed [JsPluginGate.Persister] (§ 4.2 follow-up).
 *
 * The grants are small (a few dozen entries at most) and the gate consults them on every
 * tool dispatch — synchronous reads beat coroutine plumbing for this access pattern.
 * Serialization is a single JSON array under one key; rewritten on every save.
 *
 * The chosen store is not EncryptedSharedPreferences — these grants are not secrets, and
 * the integrity comes from the threat model treating them as user policy rather than
 * authentication material.
 */
class JsPluginGatePersistence(context: Context) : JsPluginGate.Persister {

    companion object {
        private const val TAG = "JsPluginGatePersistence"
        private const val PREFS_NAME = "js_plugin_gate"
        private const val KEY_GRANTS = "grants_json"
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun loadInto(sink: (JsPluginGate.GrantEntry) -> Unit) {
        val raw = prefs.getString(KEY_GRANTS, null) ?: return
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val pluginId = obj.optString("pluginId").takeIf { it.isNotBlank() } ?: continue
                val capability = runCatching {
                    JsCapabilityClass.valueOf(obj.optString("capability"))
                }.getOrNull() ?: continue
                val state = runCatching {
                    JsPluginGate.GateState.valueOf(obj.optString("state"))
                }.getOrNull() ?: continue
                sink(JsPluginGate.GrantEntry(pluginId, capability, state))
            }
        }.onFailure { e ->
            AppLogger.w(TAG, "loadInto failed; treating store as empty: ${e.message}")
        }
    }

    override fun save(entry: JsPluginGate.GrantEntry) {
        rewrite { current ->
            current[entry.pluginId to entry.capability] = entry.state
        }
    }

    override fun remove(pluginId: String, capability: JsCapabilityClass) {
        rewrite { current ->
            current.remove(pluginId to capability)
        }
    }

    private fun rewrite(mutate: (MutableMap<Pair<String, JsCapabilityClass>, JsPluginGate.GateState>) -> Unit) {
        val snapshot = mutableMapOf<Pair<String, JsCapabilityClass>, JsPluginGate.GateState>()
        loadInto { entry -> snapshot[entry.pluginId to entry.capability] = entry.state }
        mutate(snapshot)
        val arr = JSONArray()
        snapshot.forEach { (key, state) ->
            val (pluginId, capability) = key
            arr.put(
                JSONObject().apply {
                    put("pluginId", pluginId)
                    put("capability", capability.name)
                    put("state", state.name)
                }
            )
        }
        prefs.edit().putString(KEY_GRANTS, arr.toString()).apply()
    }
}
