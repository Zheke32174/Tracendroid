package dev.pleiades.masamune.shell

/**
 * Termux's public RunCommandService contract, transcribed from
 * docs/donors/RE-total-commander.md §3 (which recovered these constants from a shipped APK).
 *
 * This is a *contract*, not code: no Termux source is copied, nothing is bundled, and the
 * app declares exactly one `<queries>` entry so it can tell whether Termux is present.
 */
object TermuxContract {
    const val PACKAGE = "com.termux"
    const val SERVICE_CLASS = "com.termux.app.RunCommandService"
    const val ACTION = "com.termux.RUN_COMMAND"
    const val PERMISSION = "com.termux.permission.RUN_COMMAND"

    const val EXTRA_PATH = "com.termux.RUN_COMMAND_PATH"
    const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
    const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
    const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
    const val EXTRA_SESSION_ACTION = "com.termux.RUN_COMMAND_SESSION_ACTION"
    const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"

    /** Result bundle Termux puts on the PendingIntent it fires back. */
    const val EXTRA_RESULT_BUNDLE = "result"
    const val RESULT_STDOUT = "stdout"
    const val RESULT_STDERR = "stderr"
    const val RESULT_EXIT_CODE = "exitCode"
    const val RESULT_ERR = "err"
    const val RESULT_ERRMSG = "errmsg"

    /** Termux's own prefix. Commands run as `bash -c "<line>"` under it. */
    const val PREFIX = "/data/data/com.termux/files/usr"
    const val BASH = "$PREFIX/bin/bash"
    const val HOME = "/data/data/com.termux/files/home"
}
