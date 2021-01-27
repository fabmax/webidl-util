package de.fabmax.webidl.parser

import de.fabmax.webidl.model.IdlType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.receiveOrNull

class WebIdlStream {
    val channel = Channel<String>()
    var readLines = 0
    var buffer = StringBuilder()

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend inline fun readUntil(stop: () -> Boolean): Boolean {
        while (!stop()) {
            val nextLine = channel.receiveOrNull()
            if (nextLine != null) {
                readLines++
                if (buffer.isNotEmpty()) {
                    buffer.append("\n")
                }
                buffer.append(nextLine.trim()).replace(whitespaceRegex, " ")
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

    suspend fun popToken(token: String) {
        readCharacters(token.length)
        if (!buffer.startsWith(token)) {
            throw WebIdlParser.ParserException("Missing expected token: \"$token\" (current buffer content: \"$buffer\")")
        }
        buffer = StringBuilder(buffer.substring(token.length).dropWhile { it.isWhitespace() })
    }

    suspend fun popIfPresent(token: String): Boolean {
        if (startsWith(token)) {
            popToken(token)
            return true
        }
        return false
    }

    suspend fun popUntilPattern(searchPattern: String, abortPattern: String? = null) =
        popUntilPattern(Regex(searchPattern), abortPattern?.let { Regex(it) })

    suspend fun popUntilPattern(searchPattern: Regex, abortPattern: Regex? = null): Pair<String, String>? {
        val s = pollUntilPattern(searchPattern, abortPattern)
        if (s != null) {
            popToken(s.first)
            if (s.second.isNotBlank()) {
                popToken(s.second.trim())
            }
        }
        return s
    }

    suspend fun popUntilWhitespaceOrEnd(): String {
        val s = pollUntilWhitespaceOrEnd()
        popToken(s)
        return s
    }

    suspend fun parseType(): IdlType {
        var isArray = false
        var isUnsigned = false

        var typeName = popUntilWhitespaceOrEnd()
        if (typeName == "unsigned") {
            isUnsigned = true
            typeName = popUntilWhitespaceOrEnd()
        }
        if (typeName == "long") {
            val next = pollUntilWhitespaceOrEnd()
            if (next == "long" || next == "long[]") {
                typeName = "long $next"
                popToken(next)
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
            throw WebIdlParser.ParserException("Invalid Type: $type")
        }
        return type
    }

    companion object {
        private val whitespaceRegex = Regex("\\s+")
    }
}