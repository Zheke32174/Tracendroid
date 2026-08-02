package dev.pleiades.masamune.operator.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.pleiades.masamune.ai.AiServiceFactory
import dev.pleiades.masamune.ai.ProviderStore
import dev.pleiades.masamune.core.halt.HaltController
import dev.pleiades.masamune.flow.expr.Value
import dev.pleiades.masamune.flow.runtime.ArgResolver
import dev.pleiades.masamune.flow.runtime.ExprEvalAdapter
import dev.pleiades.masamune.flow.runtime.Fiber
import dev.pleiades.masamune.flow.runtime.InMemoryFiberStore
import dev.pleiades.masamune.flow.runtime.Scheduler
import dev.pleiades.masamune.operator.AiOperatorDecider
import dev.pleiades.masamune.operator.OperatorGate
import dev.pleiades.masamune.operator.OperatorLoop
import dev.pleiades.masamune.operator.OperatorTrace
import dev.pleiades.masamune.operator.a11y.A11yServiceHolder
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Drives one operator run and exposes its live state to [OperatorScreen].
 *
 * The run is the [OperatorLoop] graph on a real [Scheduler], built exactly the way
 * `FlowPlaneViewModel.runFlow` builds a manual run — same scheduler, same `ArgResolver` over the
 * real expression evaluator, same `HaltController`-bound `isHalted` seam — because the operator is
 * a fiber like any other, not a privileged side path. What is added here over the manual runner is
 * only the operator's three pieces: the [AiOperatorDecider] (the LLM), the [OperatorGate] (actions
 * as `Caller.AiAgent`), and the live actuator lookup ([A11yServiceHolder]).
 *
 * ### The accessibility gate is the whole enable story
 * [a11yConnected] is the truth of whether a run can even start: it is non-null-actuator, i.e. a
 * connected [dev.pleiades.masamune.operator.a11y.MasamuneA11yService]. With it false the screen
 * disables the Run control and names what is missing; a run is refused rather than started into a
 * silent no-op. Nothing here fabricates a screen read or an action.
 */
class OperatorViewModel(private val appContext: Context) : ViewModel() {

    private val providerStore = ProviderStore.get(appContext)

    private val _goal = MutableStateFlow("")
    val goal: StateFlow<String> = _goal.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** Live fibers from the running scheduler — the audit surface, rendered by [FiberMonitor]. */
    private val _fibers = MutableStateFlow<List<Fiber>>(emptyList())
    val fibers: StateFlow<List<Fiber>> = _fibers.asStateFlow()

    /** The operator's reasoning, one line per decide/act step, newest last. */
    private val _transcript = MutableStateFlow<List<String>>(emptyList())
    val transcript: StateFlow<List<String>> = _transcript.asStateFlow()

    /** True when a connected accessibility service exists — the run gate. Refreshed by [refreshGate]. */
    private val _a11yConnected = MutableStateFlow(false)
    val a11yConnected: StateFlow<Boolean> = _a11yConnected.asStateFlow()

    /** Whether Masamune's service is toggled on in system settings — for the "give it a moment" hint. */
    private val _a11yEnabledInSettings = MutableStateFlow(false)
    val a11yEnabledInSettings: StateFlow<Boolean> = _a11yEnabledInSettings.asStateFlow()

    /** Whether a provider is configured enough to answer — else the decide step would fail on first call. */
    val providerUsable: Boolean get() = providerStore.config.value.isUsable

    /** The configured provider/model identity, for the header. */
    val providerLabel: String get() = providerStore.config.value.let { "${it.kind.label} · ${it.model}" }

    private var runJob: Job? = null

    fun setGoal(value: String) {
        _goal.value = value
    }

    /** Re-read the accessibility service's live state. Cheap; the screen polls it while idle. */
    fun refreshGate() {
        _a11yConnected.value = A11yServiceHolder.actuator() != null
        _a11yEnabledInSettings.value = A11yServiceHolder.isEnabledInSettings(appContext)
    }

    /** Resolve a fiber's current node id to a human block name for the monitor. */
    fun blockNameOf(nodeId: String): String? =
        OperatorLoop.buildGraph().node(nodeId)?.specId?.let { OperatorLoop.blockName(it) }

    /**
     * Start an operator run for the current goal.
     *
     * Refuses (without starting a scheduler) when a run is already live, the goal is blank, or no
     * connected service exists — the last being the accessibility gate. A prior halt is cleared so
     * a fresh run is not born parked. The decider is the real LLM over the configured provider; if
     * that provider is not usable the decide block will fail visibly on its first call, which the
     * transcript and the errored fiber both show.
     */
    fun run() {
        refreshGate()
        val goalText = _goal.value.trim()
        if (runJob?.isActive == true || goalText.isEmpty() || !_a11yConnected.value) return

        HaltController.clear()
        _transcript.value = listOf("goal: $goalText")
        _running.value = true

        runJob = viewModelScope.launch {
            val graph = OperatorLoop.buildGraph()
            val service = AiServiceFactory.create(appContext, providerStore.config.value)
            val decider = AiOperatorDecider(service)
            val gate = OperatorGate.real(appContext)
            val trace = OperatorTrace { entry -> _transcript.update { it + entry } }

            val implLookup = OperatorLoop.buildImplLookup(
                graph = graph,
                scope = viewModelScope,
                actuatorProvider = { A11yServiceHolder.actuator() },
                gate = gate,
                decider = decider,
                trace = trace,
            )
            val scheduler = Scheduler(
                graph = graph,
                specs = OperatorLoop.specLookup,
                impls = implLookup,
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
                scheduler.start(
                    UUID.randomUUID().toString(),
                    // Start at observe explicitly: the loop is a cycle (act loops back to observe),
                    // so no node is without an incoming edge for the scheduler to auto-pick.
                    at = OperatorLoop.NODE_OBSERVE,
                    seedVariables = mapOf(OperatorLoop.VAR_GOAL to Value.Text(goalText)),
                )
                scheduler.run()
            } finally {
                mirror.cancel()
                _fibers.value = scheduler.snapshot()
                _running.value = false
            }
        }
    }

    /** Stop the operator — the shared halt. The scheduler parks the fiber between blocks. */
    fun stop() {
        HaltController.requestHalt("user", "operator stop")
    }

    private companion object {
        const val FIBER_POLL_MS = 120L
    }
}
