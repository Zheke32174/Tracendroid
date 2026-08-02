package dev.pleiades.masamune.flow.runtime.impl

/**
 * The sink behind `Log append` — the flow's own message log.
 *
 * The donor writes to a "flow log file"; the runtime depends only on "append a line, and say whether
 * logging is on", so the block is testable against an in-memory sink and the app can back it with a
 * real file without the block changing. One log is created per running flow (in [BlockRegistry],
 * beside [AtomicStore] and [HandoffStore]) so a flow's `Log append` lines accumulate together and
 * no wider.
 *
 * This is flow-internal bookkeeping, not a device capability or a permission — so unlike the seam
 * blocks it is always available, registered unconditionally with a default in-memory sink. The
 * `whenLogging` flag lets a flow suppress a line unless logging is enabled; [loggingEnabled] is what
 * that flag consults.
 */
internal interface FlowLog {
    /** Whether logging is currently on. A `Log append` with `whenLogging` set no-ops when this is false. */
    val loggingEnabled: Boolean

    /** Append one message line to the flow log. */
    fun append(message: String)
}

/**
 * The default [FlowLog]: an in-memory, append-only line buffer. Logging is always on; the lines are
 * held in order for the monitor (or a file-backed wrapper) to read. Guarded by a plain monitor
 * because several of a flow's fibers may append concurrently.
 */
internal class InMemoryFlowLog : FlowLog {
    private val lock = Any()
    private val lines = ArrayList<String>()

    override val loggingEnabled: Boolean get() = true

    override fun append(message: String) = synchronized(lock) { lines.add(message); Unit }

    /** A snapshot of the lines appended so far, oldest first. */
    fun snapshot(): List<String> = synchronized(lock) { lines.toList() }
}
