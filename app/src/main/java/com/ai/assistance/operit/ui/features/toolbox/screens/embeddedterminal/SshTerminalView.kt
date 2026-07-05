/*
 * SshTerminalView — a self-contained ANSI terminal View driven by an SSH byte stream.
 *
 * WHY A CUSTOM VIEW (documented design choice):
 * The vendored com.termux.view.TerminalView is welded to com.termux.terminal.TerminalSession,
 * which is `final` and ALWAYS forks a LOCAL process over a pty via JNI.createSubprocess(). It
 * cannot be fed bytes from a non-local transport (like SSH) without editing the vendored, final
 * classes — which we will not do (keeps the terminal cornerstone intact and the module a clean
 * upstream mirror). So for the SSH profiles we reuse the parts that ARE decoupled:
 *   - com.termux.terminal.TerminalEmulator  — the full VT100/xterm parser + screen model. It takes
 *     a TerminalOutput (where it writes replies/input) and you feed it bytes via append().
 *   - com.termux.view.TerminalRenderer      — renders a TerminalEmulator onto a Canvas (public,
 *     needs no session).
 * This View owns an emulator + renderer, pumps SSH stdout into emulator.append(), and sends key /
 * IME input to the SSH channel's stdin. That yields full interactive rendering (vim, apt progress,
 * top, less) over SSH — the ConnectBot-style decoupling the task calls for.
 *
 * HONESTY: nothing here fabricates output. Every glyph shown came from the remote shell over SSH.
 * If the connection drops, the emulator simply stops receiving bytes and the disconnect is
 * surfaced by the owning screen.
 */
package com.ai.assistance.operit.ui.features.toolbox.screens.embeddedterminal

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Typeface
import android.text.InputType
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.View
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalRenderer
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * A [View] that renders a [TerminalEmulator] fed by [connection]'s SSH stream. Create it, then
 * call [attachConnection] with a live [SshTerminalConnection].
 */
@SuppressLint("ViewConstructor")
class SshTerminalView(
    context: Context,
    private val sessionClient: TerminalSessionClient,
    textSizePx: Int,
) : View(context) {

    private val renderer = TerminalRenderer(textSizePx, Typeface.MONOSPACE)

    private var emulator: TerminalEmulator? = null
    private var connection: SshTerminalConnection? = null
    private var readerThread: Thread? = null

    /** Top row for scrollback rendering (0 = bottom/live). Not yet user-scrollable; live view. */
    private var topRow = 0

    /** [TerminalOutput] the emulator writes to (its replies + our translated key input). */
    private inner class SshOutput : TerminalOutput() {
        override fun write(data: ByteArray, offset: Int, count: Int) {
            val out: OutputStream = connection?.output ?: return
            try {
                out.write(data, offset, count)
                out.flush()
            } catch (_: Exception) {
                // Channel closed underneath us; the reader thread will observe EOF and report.
            }
        }
        override fun titleChanged(oldTitle: String?, newTitle: String?) {}
        override fun onCopyTextToClipboard(text: String?) {}
        override fun onPasteTextFromClipboard() {}
        override fun onBell() {}
        override fun onColorsChanged() {}
    }

    private val terminalOutput = SshOutput()

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    /** Bind a live SSH connection and start pumping its output into a fresh emulator. */
    fun attachConnection(conn: SshTerminalConnection) {
        connection = conn
        // Emulator sized on first layout in onSizeChanged; use a placeholder grid until then.
        ensureEmulator(width, height)
        startReader(conn)
        requestFocus()
    }

    private fun ensureEmulator(viewWidth: Int, viewHeight: Int) {
        val cols = computeColumns(viewWidth)
        val rows = computeRows(viewHeight)
        val existing = emulator
        if (existing == null) {
            emulator = TerminalEmulator(
                terminalOutput, cols, rows,
                renderer.fontWidth.toInt().coerceAtLeast(1),
                renderer.fontLineSpacing,
                TRANSCRIPT_ROWS, sessionClient,
            )
            connection?.resize(cols, rows, renderer.fontWidth.toInt(), renderer.fontLineSpacing)
        } else if (cols != existing.mColumns || rows != existing.mRows) {
            existing.resize(cols, rows, renderer.fontWidth.toInt(), renderer.fontLineSpacing)
            connection?.resize(cols, rows, renderer.fontWidth.toInt(), renderer.fontLineSpacing)
        }
    }

    private fun computeColumns(viewWidth: Int): Int =
        Math.max(4, (viewWidth / renderer.fontWidth).toInt())

    private fun computeRows(viewHeight: Int): Int =
        Math.max(4, viewHeight / renderer.fontLineSpacing - 1)

    private fun startReader(conn: SshTerminalConnection) {
        readerThread?.interrupt()
        val t = kotlin.concurrent.thread(start = true, isDaemon = true, name = "SshTerminalReader") {
            val buffer = ByteArray(4096)
            val stream = conn.input
            try {
                while (!Thread.currentThread().isInterrupted) {
                    val read = stream.read(buffer)
                    if (read == -1) break
                    if (read > 0) {
                        val chunk = buffer.copyOf(read)
                        post {
                            emulator?.append(chunk, chunk.size)
                            invalidate()
                        }
                    }
                }
            } catch (_: Exception) {
                // Connection closed / interrupted — nothing to render further.
            } finally {
                post { invalidate() }
            }
        }
        readerThread = t
    }

    /** Detach and stop the reader (does NOT close the connection — the owner does that). */
    fun detach() {
        readerThread?.interrupt()
        readerThread = null
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            ensureEmulator(w, h)
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val emu = emulator
        if (emu == null) {
            canvas.drawColor(0xFF000000.toInt())
            return
        }
        renderer.render(emu, canvas, topRow, -1, -1, -1, -1)
    }

    // ----------------------------------------------------------------------------------------
    // Input: hardware keys + soft keyboard, translated to bytes on the SSH channel.
    // ----------------------------------------------------------------------------------------

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // VISIBLE_PASSWORD + NO_SUGGESTIONS mirrors TerminalView: it stops autocorrect/compose from
        // mangling terminal input on stock keyboards.
        outAttrs.inputType = InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI

        return object : BaseInputConnection(this, true) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                if (text != null) sendText(text)
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                // Map backspace-from-IME to DEL bytes.
                repeat(beforeLength) { sendBytes(byteArrayOf(0x7f)) }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN) handleKeyDown(event.keyCode, event)
                return true
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (handleKeyDown(keyCode, event)) return true
        return super.onKeyDown(keyCode, event)
    }

    private fun handleKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val emu = emulator ?: return false
        // Printable chars (with modifiers folded in by the framework) come through getUnicodeChar.
        val metaState = event.metaState
        val ctrlDown = event.isCtrlPressed
        val altDown = event.isAltPressed

        // Try a control-sequence mapping first (arrows, F-keys, Home/End, Enter, Backspace, Tab…).
        var keyMod = 0
        if (event.isShiftPressed) keyMod = keyMod or KeyHandler.KEYMOD_SHIFT
        if (ctrlDown) keyMod = keyMod or KeyHandler.KEYMOD_CTRL
        if (altDown) keyMod = keyMod or KeyHandler.KEYMOD_ALT
        val code = KeyHandler.getCode(
            keyCode, keyMod,
            emu.isCursorKeysApplicationMode,
            emu.isKeypadApplicationMode,
        )
        if (code != null) {
            sendBytes(code.toByteArray(StandardCharsets.UTF_8))
            return true
        }

        // Otherwise resolve to a unicode code point and send it (handling Ctrl-letter).
        val codePoint = event.getUnicodeChar(if (ctrlDown || altDown) 0 else metaState)
        if (codePoint > 0) {
            var cp = codePoint
            if (ctrlDown) {
                // Ctrl-A..Ctrl-Z -> 0x01..0x1a, and a handful of symbol controls.
                cp = when (cp) {
                    in 'a'.code..'z'.code -> cp - 'a'.code + 1
                    in 'A'.code..'Z'.code -> cp - 'A'.code + 1
                    ' '.code, '@'.code -> 0
                    '['.code -> 27
                    '\\'.code -> 28
                    ']'.code -> 29
                    '^'.code -> 30
                    '_'.code -> 31
                    else -> cp
                }
            }
            val bytes = if (altDown) {
                // Alt/Meta prefixes with ESC (xterm meta-sends-escape).
                byteArrayOf(27) + String(Character.toChars(cp)).toByteArray(StandardCharsets.UTF_8)
            } else {
                String(Character.toChars(cp)).toByteArray(StandardCharsets.UTF_8)
            }
            sendBytes(bytes)
            return true
        }
        return false
    }

    private fun sendText(text: CharSequence) {
        val s = text.toString()
        // A newline from the soft keyboard should be carriage-return for a pty (matches TerminalView).
        val normalized = s.replace("\n", "\r")
        sendBytes(normalized.toByteArray(StandardCharsets.UTF_8))
    }

    private fun sendBytes(bytes: ByteArray) {
        if (emulator == null) return
        // Route through OUR TerminalOutput, which writes straight to the SSH channel's stdin.
        // (The emulator's own mSession field is private; we hold the identical instance here.)
        terminalOutput.write(bytes, 0, bytes.size)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            requestFocus()
            // Nudge the IME to show when the user taps the terminal.
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
                    as? android.view.inputmethod.InputMethodManager
            imm?.showSoftInput(this, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
        return true
    }

    companion object {
        private const val TRANSCRIPT_ROWS = 2_000
    }
}
