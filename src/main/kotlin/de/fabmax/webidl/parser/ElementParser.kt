package de.fabmax.webidl.parser

import de.fabmax.webidl.model.IdlType

abstract class ElementParser(val parserState: WebIdlParser.ParserState, val selfType: WebIdlParserType) {
    val stream: WebIdlStream get() = parserState.parserStream

    abstract suspend fun parse(): String?

    suspend fun parseChildren(termToken: String?) {
        var child = selfType.possibleChildren().find { it.matches(stream) }
        var childTerm: String? = null
        while (child != null && (termToken == null || !stream.startsWith(termToken))) {
            childTerm = child.newParser(parserState).parse()
            child = selfType.possibleChildren().find { it.matches(stream) }
        }

        if (child == null && stream.buffer.isNotEmpty()
            && termToken?.let { stream.buffer.startsWith(it) || it == childTerm } != true) {
            parserException("Unexpected idl content")
        }

        if (termToken != null && stream.startsWith(termToken)) {
            popToken(termToken)
        }
    }

    fun parserException(message: String): Nothing {
        var lineNum = 1 + parserState.parserStream.readLines - parserState.parserStream.contextLines.size
        throw ParserException("Invalid $selfType: $message, somewhere around [${parserState.currentFileName}:${parserState.parserStream.readLines}]\n" +
                parserState.parserStream.contextLines.joinToString(separator = "\n") { " > ${lineNum++}: $it" })
    }

    protected suspend fun popToken(token: String) = parserState.parserStream.popToken(token, this)

    protected suspend fun popUntilPattern(searchPattern: String, abortPattern: String? = null) =
        popUntilPattern(Regex(searchPattern), abortPattern?.let { Regex(it) })

    protected suspend fun popUntilPattern(searchPattern: Regex, abortPattern: Regex? = null): Pair<String, String>? =
            parserState.parserStream.popUntilPattern(searchPattern, abortPattern, this)

    protected suspend fun popIfPresent(token: String): Boolean =
        parserState.parserStream.popIfPresent(token, this)

    protected suspend fun parseType(): IdlType = parserState.parserStream.parseType(this)
}