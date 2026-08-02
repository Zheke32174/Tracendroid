package dev.pleiades.masamune.shell

/**
 * Recognises a request for root so it can be answered by the container instead of the device.
 *
 * `su` on Android means "ask the ROM for uid 0", and on an unrooted device that ask simply fails —
 * `su: not found`, which tells the user nothing about what Masamune can actually do. Masamune ships
 * its own userland, and that userland can have its own root ([CapsuleRoot]). So a typed `su` is
 * re-routed *inward*: same word, same shape, answered by the container.
 *
 * This is a rewrite, not a fake. The caller is told which root it got — see
 * [ShellDispatcher.Dispatch.RanAsContainerRoot] — because container root and kernel root are two
 * different powers and a surface that blurs them would be lying about the more useful one.
 *
 * Parsing is deliberately narrow: only the forms that really mean "run this as root" are rewritten.
 * Anything else (a path containing the letters `su`, a `sudo` with no command, an argument list this
 * does not understand) is left alone and runs as itself, so an unrecognised line never gets silently
 * turned into something else.
 */
object SuRewrite {

    sealed class Request {
        /** `su -c '<command>'`, `su root -c …`, `sudo <command>` — there is a command to run. */
        data class Command(val commandLine: String) : Request()

        /**
          * Bare `su`: a request for an interactive root *session*. The Terminal dispatches one
          * command per entry and has no session to hand over, so this is reported rather than
          * quietly turned into something that only looks like it worked.
          */
        data object Interactive : Request()
    }

    /** The su-alike leaders. `sudo` is included because users type it out of habit. */
    private val LEADERS = setOf("su", "sudo")

    /**
     * Returns the root request [commandLine] expresses, or null when it is an ordinary command.
     */
    fun parse(commandLine: String): Request? {
        val tokens = tokenize(commandLine) ?: return null
        val leader = tokens.firstOrNull() ?: return null
        if (leader !in LEADERS) return null

        var i = 1
        while (i < tokens.size) {
            val t = tokens[i]
            when {
                // `-c <command>` — everything after it is the command, already one token when the
                // user quoted it, joined when they did not.
                t == "-c" -> {
                    val rest = tokens.drop(i + 1)
                    return if (rest.isEmpty()) Request.Interactive
                    else Request.Command(if (rest.size == 1) rest[0] else rest.joinToString(" "))
                }
                // `su root …`, `su 0 …`: the user named the target, and it is the one we can be.
                t == "root" || t == "0" -> i++
                // `sudo -u root …` and friends: understood only for root; anything else is not ours.
                t == "-u" -> {
                    val who = tokens.getOrNull(i + 1) ?: return null
                    if (who != "root" && who != "0") return null
                    i += 2
                }
                // A lone flag we do not model. Refusing to guess is the honest move: the line runs
                // unmodified and fails as itself rather than being reinterpreted.
                t.startsWith("-") -> return null
                // `sudo <command> …` with no -c: the remainder IS the command.
                else -> return Request.Command(tokens.drop(i).joinToString(" "))
            }
        }
        return Request.Interactive
    }

    /**
     * Split on whitespace, honouring single and double quotes so `su -c 'a b'` yields one command
     * token. Returns null on an unterminated quote — a line the shell itself would reject, and one
     * this must not half-parse into a different command than the user wrote.
     */
    internal fun tokenize(line: String): List<String>? {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var quote: Char? = null
        var started = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quote != null && c == quote -> { quote = null; i++ }
                quote != null -> { cur.append(c); i++ }
                c == '\'' || c == '"' -> { quote = c; started = true; i++ }
                c == '\\' && i + 1 < line.length -> { cur.append(line[i + 1]); started = true; i += 2 }
                c.isWhitespace() -> {
                    if (cur.isNotEmpty() || started) { out.add(cur.toString()); cur.clear(); started = false }
                    i++
                }
                else -> { cur.append(c); started = true; i++ }
            }
        }
        if (quote != null) return null
        if (cur.isNotEmpty() || started) out.add(cur.toString())
        return out
    }
}
