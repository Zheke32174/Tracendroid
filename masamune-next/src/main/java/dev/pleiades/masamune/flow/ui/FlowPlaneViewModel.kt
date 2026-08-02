package dev.pleiades.masamune.flow.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.pleiades.masamune.core.halt.HaltController
import dev.pleiades.masamune.flow.catalog.BlockCatalog
import dev.pleiades.masamune.flow.model.BlockSpec
import dev.pleiades.masamune.flow.model.FlowGraph
import dev.pleiades.masamune.flow.model.FlowNode
import dev.pleiades.masamune.flow.model.Port
import dev.pleiades.masamune.flow.model.Requirement
import dev.pleiades.masamune.apps.AppInspector
import dev.pleiades.masamune.apps.ConnectivityReader
import dev.pleiades.masamune.apps.LocationReader
import dev.pleiades.masamune.apps.PowerState
import dev.pleiades.masamune.apps.SensorReader
import dev.pleiades.masamune.apps.SystemSettings
import dev.pleiades.masamune.flow.runtime.ArgResolver
import dev.pleiades.masamune.flow.runtime.BlockRegistry
import dev.pleiades.masamune.flow.runtime.ExprEvalAdapter
import dev.pleiades.masamune.flow.runtime.impl.appsLookup
import dev.pleiades.masamune.flow.runtime.impl.connectivityLookup
import dev.pleiades.masamune.flow.runtime.impl.locationLookup
import dev.pleiades.masamune.flow.runtime.impl.powerLookup
import dev.pleiades.masamune.flow.runtime.impl.sensorLookup
import dev.pleiades.masamune.flow.runtime.impl.settingsLookup
import dev.pleiades.masamune.flow.runtime.Fiber
import dev.pleiades.masamune.flow.runtime.InMemoryFiberStore
import dev.pleiades.masamune.flow.runtime.Scheduler
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Holds the one editable [FlowGraph] and the view's selection, and applies every mutation through
 * the graph's own methods so the model's invariants (an output port holds at most one edge; a
 * removed node drops every edge touching it) are the graph's to keep, not the UI's to reinvent.
 *
 * ### Honest state, not simulated state
 * [satisfied] is the set of [Requirement]s the device actually grants. This build has no detector
 * for the flow-block grants (an enabled AccessibilityService, the Yojimbo uid-2000 server, a
 * NotificationListener, a device-admin receiver), so the honest, fail-closed answer is the empty
 * set: nothing is assumed granted. That is what makes the palette's gating visible rather than
 * decorative — a block whose grant cannot be confirmed reports unavailable, it does not pretend.
 *
 * [fibers] is the live list the running [Scheduler] holds — empty until [runFlow] starts one, then
 * the fibers actually executing this graph, current block and variable frame and all. The monitor
 * renders exactly what the runtime hands it; there is no invented state.
 *
 * @param appInspector supplies the package-manager-backed [AppInspector] the Apps blocks
 * (`app_installed`, `app_list`, `resolve_activity`, `activity_start`, `activity_start_result`) run
 * against. It defaults to `{ null }` so this ViewModel constructs with no Android `Context` — and a
 * null inspector is honest: those blocks then fail by name with `APPS_ABSENT` rather than pretending
 * to read the package manager. A factory holding a `Context` passes
 * `{ PackageManagerAppInspector(context) }` to make them live; nothing in the runtime changes.
 *
 * @param systemSettings supplies the settings-store-backed [SystemSettings] the Settings blocks
 * (`system_setting_get`/`_set`, `screen_brightness`/`_set`, `screen_off_timeout`/`_set`,
 * `ringer_mode`/`_set`, `system_property_get`, `system_language_get`) run against. It defaults to
 * `{ null }` on the same honest terms as [appInspector]: with no `Context` there is no seam, and each
 * Settings block fails by name with `SETTINGS_ABSENT` rather than pretending to read or write a
 * setting. A factory holding a `Context` passes `{ AndroidSystemSettings(context) }` to make them
 * live; nothing in the runtime changes.
 *
 * @param powerState supplies the battery/power-state-backed [PowerState] the Battery&Power blocks
 * (`battery_charging`, `battery_level`, `battery_properties`, `power_source_plugged`,
 * `power_save_mode_enabled`, `device_idle_mode_active`, `device_interactive`) run against. It defaults
 * to `{ null }` on the same honest terms as [appInspector] and [systemSettings]: with no `Context`
 * there is no seam, and each Battery&Power block fails by name with `POWER_ABSENT` rather than
 * pretending to read a device reading. A factory holding a `Context` passes
 * `{ AndroidPowerState(context) }` to make them live; nothing in the runtime changes.
 *
 * @param sensorReader supplies the hardware-sensor-backed [SensorReader] the Sensor blocks
 * (`ambient_light`, `ambient_temperature`, `atmospheric_pressure`, `device_acceleration`,
 * `device_orientation`, `hinge_angle`, `magnetic_field_strength`, `proximity`, `relative_humidity`) run
 * against. It defaults to `{ null }` on the same honest terms as the seams above: with no `Context`
 * there is no seam, and each Sensor block fails by name with `SENSOR_ABSENT` rather than pretending to
 * read a hardware sensor. A factory holding a `Context` passes `{ AndroidSensorReader(context) }` to
 * make them live; nothing in the runtime changes.
 *
 * @param locationReader supplies the location-subsystem-backed [LocationReader] the Location blocks
 * (`geocoding_reverse`, `geocoding`, `location_at`, `location_get`, `location_provider_enabled`) run
 * against. It defaults to `{ null }` on the same honest terms as the seams above: with no `Context`
 * there is no seam, and each Location block fails by name with `LOCATION_ABSENT` rather than pretending
 * to read a place. A factory holding a `Context` passes `{ AndroidLocationReader(context) }` to make
 * them live; nothing in the runtime changes.
 *
 * @param connectivityReader supplies the radio/network-stack-backed [ConnectivityReader] the Connectivity
 * read blocks (`airplane_mode_enabled`, `wifi_enabled`, `wifi_ap_enabled`, `wifi_network_connected`,
 * `wifi_signal_level`, `bluetooth_enabled`, `bluetooth_device_connected`, `nfc_enabled`,
 * `mobile_data_enabled`, `mobile_data_network_type`, `network_connected`, `network_type`) run against. It
 * defaults to `{ null }` on the same honest terms as the seams above: with no `Context` there is no seam,
 * and each Connectivity block fails by name with `CONNECTIVITY_ABSENT` rather than pretending to read a
 * radio state. A factory holding a `Context` passes `{ AndroidConnectivityReader(context) }` to make them
 * live; nothing in the runtime changes.
 */
class FlowPlaneViewModel(
    private val appInspector: () -> AppInspector? = { null },
    private val systemSettings: () -> SystemSettings? = { null },
    private val powerState: () -> PowerState? = { null },
    private val sensorReader: () -> SensorReader? = { null },
    private val locationReader: () -> LocationReader? = { null },
    private val connectivityReader: () -> ConnectivityReader? = { null },
) : ViewModel() {

    private val _graph = MutableStateFlow(FlowGraph(id = UUID.randomUUID().toString(), name = "Untitled flow"))
    val graph: StateFlow<FlowGraph> = _graph.asStateFlow()

    private val _selectedNodeId = MutableStateFlow<String?>(null)
    val selectedNodeId: StateFlow<String?> = _selectedNodeId.asStateFlow()

    /** Real device grants. Empty until a capability detector is wired — fail-closed by design. */
    val satisfied: Set<Requirement> = emptySet()

    /** Live fibers from the running scheduler. Empty when nothing is running; never fabricated. */
    private val _fibers = MutableStateFlow<List<Fiber>>(emptyList())
    val fibers: StateFlow<List<Fiber>> = _fibers.asStateFlow()

    /** Execution is wired on this screen: a real scheduler runs the current graph. */
    val executionWired: Boolean = true

    /** True while a run is in flight, so the Run control can guard against launching a second. */
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private var runJob: Job? = null

    private var placed = 0

    fun addBlock(spec: BlockSpec) {
        // Cascade new blocks so they do not stack exactly on top of one another.
        val step = placed % 6
        val row = placed / 6
        val node = FlowNode(
            id = UUID.randomUUID().toString(),
            specId = spec.id,
            x = 96f + step * 56f,
            y = 96f + step * 40f + row * 24f,
        )
        placed++
        _graph.update { it.copy(nodes = it.nodes + node) }
        _selectedNodeId.value = node.id
    }

    fun selectNode(id: String) {
        _selectedNodeId.value = id
    }

    fun moveNode(id: String, x: Float, y: Float) {
        _graph.update { g ->
            g.copy(nodes = g.nodes.map { if (it.id == id) it.copy(x = x, y = y) else it })
        }
    }

    fun connect(fromNode: String, fromPort: Port, toNode: String) {
        if (fromNode == toNode) return
        _graph.update { it.connect(fromNode, fromPort, toNode) }
    }

    fun updateNode(node: FlowNode) {
        _graph.update { g ->
            g.copy(nodes = g.nodes.map { if (it.id == node.id) node else it })
        }
    }

    fun deleteNode(id: String) {
        _graph.update { it.removeNode(id) }
        if (_selectedNodeId.value == id) _selectedNodeId.value = null
    }

    /** A fiber's `currentNode` (a node id) resolved to its block's display name, for the monitor. */
    fun blockNameOf(nodeId: String): String? =
        _graph.value.node(nodeId)?.let { BlockCatalog[it.specId]?.name }

    /**
     * Run the current graph to completion on a background coroutine, streaming the live fiber list
     * into [fibers] as it goes.
     *
     * A [Scheduler] is built fresh per run from the graph snapshot, the real [BlockRegistry]
     * (whose gate reports any block this build cannot run), the [ExprEvalAdapter] over the actual
     * expression evaluator, and an [InMemoryFiberStore]. The scheduler's `isHalted` seam is bound
     * to [HaltController], so the shared stop control parks this flow exactly as it stops every
     * other privileged surface — halt is a property of the run, not a flag this screen invents.
     *
     * The registry closes over [viewModelScope], so a `Delay`'s waker and the run itself share the
     * ViewModel's lifecycle: clearing the screen cancels an in-flight run and its parked timers.
     * A lightweight poll mirrors the scheduler's snapshot into [fibers] while it runs, then a final
     * snapshot captures the terminal state.
     */
    fun runFlow() {
        if (runJob?.isActive == true) return
        val snapshot = _graph.value
        _running.value = true
        runJob = viewModelScope.launch {
            val registry = BlockRegistry(snapshot, viewModelScope)
            // Compose the Apps inspect-and-launch blocks over the base registry, the same way
            // OperatorLoop composes its Interface actions: found here first, else the base registry.
            // With a null inspector each Apps block fails by name (APPS_ABSENT) — honest, not silent.
            val apps = appsLookup(appInspector)
            // Compose the Settings read/write blocks the same way, layered ahead of the Apps blocks:
            // found here first, else Apps, else the base registry. A null seam fails each by name
            // (SETTINGS_ABSENT) — honest, not silent — exactly as the Apps layer does with APPS_ABSENT.
            val settings = settingsLookup(systemSettings)
            // Compose the Battery&Power device-state reads the same way, layered ahead of Settings:
            // found here first, else Settings, else Apps, else the base registry. A null seam fails
            // each by name (POWER_ABSENT) — honest, not silent — exactly as the layers below do.
            val power = powerLookup(powerState)
            // Compose the Sensor device-state reads the same way, layered ahead of Battery&Power:
            // found here first, else Power, else Settings, else Apps, else the base registry. A null
            // seam fails each by name (SENSOR_ABSENT) — honest, not silent — exactly as the layers below.
            val sensors = sensorLookup(sensorReader)
            // Compose the Location one-shot reads the same way, layered ahead of Sensor: found here
            // first, else Sensor, else Power, else Settings, else Apps, else the base registry. A null
            // seam fails each by name (LOCATION_ABSENT) — honest, not silent — exactly as the layers below.
            val location = locationLookup(locationReader)
            // Compose the Connectivity one-shot reads the same way, layered ahead of Location: found here
            // first, else Location, else Sensor, …, else the base registry. A null seam fails each by name
            // (CONNECTIVITY_ABSENT) — honest, not silent — exactly as the layers below.
            val connectivity = connectivityLookup(connectivityReader)
            val scheduler = Scheduler(
                graph = snapshot,
                specs = { BlockCatalog[it] },
                impls = { id ->
                    connectivity[id] ?: location[id] ?: sensors[id] ?: power[id] ?: settings[id]
                        ?: apps[id] ?: registry.lookup(id)
                },
                resolver = ArgResolver(ExprEvalAdapter()),
                store = InMemoryFiberStore(),
                scope = viewModelScope,
                isHalted = { HaltController.isHalted },
            )
            val mirror = launch {
                while (isActive) {
                    _fibers.value = scheduler.snapshot()
                    delay(FIBER_POLL_MS)
                }
            }
            try {
                scheduler.start(UUID.randomUUID().toString())
                scheduler.run()
            } finally {
                mirror.cancel()
                _fibers.value = scheduler.snapshot()
                _running.value = false
            }
        }
    }

    private companion object {
        /** How often the monitor mirror samples the scheduler while a flow runs. */
        const val FIBER_POLL_MS = 120L
    }
}
