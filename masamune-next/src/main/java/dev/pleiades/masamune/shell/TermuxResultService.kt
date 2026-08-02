package dev.pleiades.masamune.shell

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * The receiving half of the Termux RUN_COMMAND contract.
 *
 * Termux does not return output synchronously. It fires a PendingIntent that the caller
 * supplied, carrying a "result" bundle. We hand it a PendingIntent targeting this service, so
 * the delivery happens with *our* identity and the service stays un-exported.
 *
 * Results are correlated back to the caller by [KEY_EXEC_ID], which we set on the base intent
 * before wrapping it — Termux only adds extras, it does not clear ours.
 */
class TermuxResultService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            val execId = intent.getLongExtra(KEY_EXEC_ID, -1L)
            val bundle: Bundle? = intent.getBundleExtra(TermuxContract.EXTRA_RESULT_BUNDLE)
            TermuxResultBus.publish(
                TermuxRawResult(
                    execId = execId,
                    stdout = bundle?.getString(TermuxContract.RESULT_STDOUT).orEmpty(),
                    stderr = bundle?.getString(TermuxContract.RESULT_STDERR).orEmpty(),
                    exitCode = bundle?.getInt(TermuxContract.RESULT_EXIT_CODE, Int.MIN_VALUE)
                        ?: Int.MIN_VALUE,
                    err = bundle?.getInt(TermuxContract.RESULT_ERR, 0) ?: 0,
                    errmsg = bundle?.getString(TermuxContract.RESULT_ERRMSG).orEmpty(),
                )
            )
        }
        stopSelf(startId)
        return START_NOT_STICKY
    }

    companion object {
        const val KEY_EXEC_ID = "dev.pleiades.masamune.EXEC_ID"
    }
}

data class TermuxRawResult(
    val execId: Long,
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val err: Int,
    val errmsg: String,
)

/** Process-wide correlation point between the service and whoever is awaiting a result. */
object TermuxResultBus {
    private val _results = MutableSharedFlow<TermuxRawResult>(
        replay = 8,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val results: SharedFlow<TermuxRawResult> = _results.asSharedFlow()

    fun publish(result: TermuxRawResult) {
        _results.tryEmit(result)
    }
}
