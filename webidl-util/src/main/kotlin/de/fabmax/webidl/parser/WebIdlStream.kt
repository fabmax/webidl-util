package de.fabmax.webidl.parser

import de.fabmax.webidl.model.IdlType
import kotlinx.coroutines.channels.Channel
import java.util.*

class WebIdlStream {
    var channel = Channel<String>(Channel.UNLIMITED)
    var readLines = 0
    var buffer = StringBuilder()

    val contextLines = LinkedList<String>()

    private suspend inline fun readUntil(stop: () -> Boolean): Boolean {
        while (!stop()) {
            val nextLine = channel.receiveCatching()
            if (nextLine.isSuccess) {
                readLines++
                if (buffer.isNotEmpty()) {
                    buffer.append("\n")
                }
                val appendLine = nextLine.getOrThrow()
                buffer.append(appendLine.substringBefore("//").trim()).replace(whitespaceRegex, " ")
                contextLines.addLast(appendLine)
                while (contextLines.size > 4) {
                    contextLines.removeFirst()
                }
            } else {
                // channel was closed
                return false
            }
        }
        return true
    }

    private suspend fun readCharacters(minLen: Int): Boolean {
        return readUntil { buffer.length >= minLen }
    }

    private suspend fun readUntilPattern(searchPattern: Regex, abortPattern: Regex?): Boolean {
        readUntil { searchPattern.containsMatchIn(buffer) || abortPattern?.containsMatchIn(buffer) ?: false }
        return searchPattern.containsMatchIn(buffer)
    }

    suspend fun startsWith(prefix: String): Boolean {
        readCharacters(prefix.length)
        return buffer.startsWith(prefix)
    }

    suspend fun endWith(prefix: String): Boolean {
        readCharacters(prefix.length)
        return buffer.endsWith(prefix)
    }

    suspend fun pollUntilPattern(searchPattern: String, abortPattern: String? = null) =
        pollUntilPattern(Regex(searchPattern), abortPattern?.let { Regex(it) })

    suspend fun pollUntilPattern(searchPattern: Regex, abortPattern: Regex? = null): Pair<String, String>? {
        readUntilPattern(searchPattern, abortPattern)
        val matchRange = searchPattern.find(buffer)?.range
        return if (matchRange != null) {
            val first = buffer.substring(0, matchRange.first).trim()
            val second = buffer.substring(matchRange)
            first to second
        } else {
            null
        }
    }

    suspend fun pollUntilWhitespaceOrEnd(): String {
        return pollUntilPattern(whitespaceRegex, null)?.first ?: buffer.toString()
    }

    suspend fun popToken(token: String, parser: ElementParser) {
        readCharacters(token.length)
        if (!buffer.startsWith(token)) {
            parser.parserException("Missing expected token: \"$token\" (current buffer content: \"$buffer\")")
        }
        buffer = StringBuilder(buffer.substring(token.length).dropWhile { it.isWhitespace() })
    }

    suspend fun popIfPresent(token: String, parser: ElementParser): Boolean {
        if (startsWith(token)) {
            popToken(token, parser)
            return true
        }
        return false
    }

    suspend fun popUntilPattern(searchPattern: Regex, abortPattern: Regex?, parser: ElementParser): Pair<String, String>? {
        val s = pollUntilPattern(searchPattern, abortPattern)
        if (s != null) {
            popToken(s.first, parser)
            if (s.second.isNotBlank()) {
                popToken(s.second.trim(), parser)
            }
        }
        return s
    }

    suspend fun popUntilWhitespaceOrEnd(parser: ElementParser): String {
        val s = pollUntilWhitespaceOrEnd()
        popToken(s, parser)
        return s
    }

    suspend fun parseType(parser: ElementParser): IdlType {
        var isArray = false
        var isUnsigned = false

        var typeName = popUntilWhitespaceOrEnd(parser)
        if (typeName == "unsigned") {
            isUnsigned = true
            typeName = popUntilWhitespaceOrEnd(parser)
        }
        if (typeName == "long") {
            val next = pollUntilWhitespaceOrEnd()
            if (next == "long" || next == "long[]") {
                typeName = "long $next"
                popToken(next, parser)
            }
        }
        if (isUnsigned) {
            typeName = "unsigned $typeName"
        }

        if (typeName.endsWith("[]")) {
            isArray = true
            typeName = typeName.substring(0, typeName.length - 2)
        }
        val type = IdlType(typeName, isArray)
        if (!type.isValid()) {
            parser.parserException("Invalid Type: \"$type\"")
        }
        return type
    }

    companion object {
        private val whitespaceRegex = Regex("\\s+")
    }
}