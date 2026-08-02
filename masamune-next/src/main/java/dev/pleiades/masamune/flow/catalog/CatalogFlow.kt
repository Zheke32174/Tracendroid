package dev.pleiades.masamune.flow.catalog

import dev.pleiades.masamune.flow.model.BlockCategory
import dev.pleiades.masamune.flow.model.BlockSpec

/**
 * The graph's own control structures — and the eight-block problem in this port.
 *
 * Automate gives six of its thirteen Flow blocks connector dots that are neither `OK` nor
 * `YES`/`NO`: `FAIL`, `NEW`, `DO`, `N/A`. Masamune has two shapes and adds no third, so each
 * of those is mapped onto the shape with the matching number of outcomes and the mapping is
 * stated in the block's own summary. `Fork` and `Subroutine` become decisions whose YES is the
 * child fiber's path; `Failure catch` becomes a decision whose NO is the retry path; `Go to`
 * becomes an action because only its `N/A` dot is ever connected — a jump that matches leaves
 * through the `Label` block, not through a port.
 *
 * Two more of these specials live elsewhere — `For each` in General and `Process text
 * selection` in Interface — for eight in the catalog. The alternative was a third shape, and a
 * shape that exists is one every editor, serializer and renderer carries forever.
 *
 * Blocks are listed in Automate's own palette order, which is the order this catalog and the
 * palette both render. See `docs/donors/RE-automate.md`.
 */
internal val FLOW_BLOCKS: List<BlockSpec> = category(BlockCategory.FLOW) {
    decision(
        "failure_catch", "Failure catch",
        "Catches any failure in a subsequent block, preventing the fiber from stopping. " +
            "Automate gives this block an OK and a FAIL dot; FAIL maps to NO, so the retry path " +
            "is the NO port.",
        args = listOf(
            num("retryLimit", "Retry limit", "3"),
        ),
        outputs = listOf(
            out("varRetryCount", "Retry count"),
            out("varFailureStatementId", "Failure block id"),
            out("varFailureType", "Failure type"),
            out("varFailureMessage", "Failure message"),
        ),
    )
    action(
        "fiber_stop", "Fiber stop",
        "Stops another fiber.",
        args = listOf(
            text("fiberUri", "Fiber URI", "to do nothing, no fiber will stop"),
        ),
    )
    decision(
        "fiber_stopped", "Fiber stopped",
        "Checks if a child fiber has stopped, either manually or by an error.",
        proceed = AWAIT,
        args = listOf(
            text("fiberUri", "Fiber URI"),
        ),
    )
    action(
        "flow_beginning", "Flow beginning",
        "Starting point of a flow: creates a fiber and proceeds without pause. Takes no " +
            "incoming edges - it is where a fiber is created, not somewhere a fiber arrives.",
        options = listOf(
            flagOption("hidden", "Hidden"),
            flagOption("parallelLaunch", "Parallel launch"),
        ),
        outputs = listOf(
            out("varPayload", "Payload"),
            out("varFiberUri", "Fiber URI"),
        ),
    )
    decision(
        "flow_beginning_pick", "Flow beginning pick",
        "Lets the user choose a flow and beginning block.",
        args = listOf(
            num("timeout", "Timeout", "no timeout"),
            any("notificationChannelId", "Notification channel", "the flow default or Flow"),
            flag("startActivity", "Show window"),
        ),
        outputs = listOf(
            out("varFlowUri", "Flow URI"),
            out("varFlowTitle", "Flow title"),
            out("varFlowDescription", "Flow description"),
            out("varBeginningTitle", "Beginning title"),
        ),
    )
    decision(
        "flow_pick", "Flow pick",
        "Lets the user choose a flow.",
        args = listOf(
            num("timeout", "Timeout", "no timeout"),
            any("notificationChannelId", "Notification channel", "the flow default or Flow"),
            flag("startActivity", "Show window"),
        ),
        outputs = listOf(
            out("varFlowUri", "Flow URI"),
            out("varFlowTitle", "Flow title"),
            out("varFlowDescription", "Flow description"),
        ),
    )
    action(
        "flow_start", "Flow start",
        "Starts another flow at specific beginning block.",
        options = listOf(flagOption("stopWithParent", "Stop with parent")),
        args = listOf(
            text("flowUri", "Flow URI"),
            any("payload", "Payload"),
        ),
        outputs = listOf(
            out("varChildFiberURI", "Fiber URI"),
        ),
    )
    action(
        "flow_stop", "Flow stop",
        "Stops a flow and all its running fibers.",
        args = listOf(
            text("flowUri", "Flow URI", "the current flow"),
        ),
    )
    decision(
        "fork", "Fork",
        "Starts a new fiber by copying the state of the current one. Automate gives this " +
            "block an OK and a NEW dot; NEW maps to YES (walked by the child fiber) and OK to NO " +
            "(walked by the parent).",
        options = listOf(flagOption("stopWithParent", "Stop with parent")),
        outputs = listOf(
            out("varChildFiberURI", "Child fiber URI"),
            out("varParentFiberURI", "Parent fiber URI"),
        ),
    )
    action(
        "goto", "Go to",
        "Transfers control of the fiber to the Label block whose value matches. Automate " +
            "gives this block only an N/A dot: a matched jump leaves through the Label block, " +
            "not through a port, so OK here means no label matched.",
        args = listOf(
            text("labelValue", "Label value"),
        ),
    )
    action(
        "label", "Label",
        "Does nothing except act as a destination for Go to blocks.",
    )
    action(
        "log_append", "Log append",
        "Appends a message to the flow log file.",
        args = listOf(
            text("message", "Message"),
            flag("whenLogging", "Logging", "always log"),
        ),
    )
    decision(
        "subroutine", "Subroutine",
        "Runs a subset of blocks in a new fiber and waits for it to stop, optionally with " +
            "results. Automate gives this block an OK and a NEW dot; NEW maps to YES (the " +
            "subroutine body, run by the new fiber) and OK to NO (the caller, resumed when it " +
            "returns).",
    )
}
