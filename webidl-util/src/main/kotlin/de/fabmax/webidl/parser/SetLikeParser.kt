package de.fabmax.webidl.parser


import de.fabmax.webidl.model.IdlSetLike
import de.fabmax.webidl.model.IdlType

class SetLikeParser(parserState: WebIdlParser.ParserState) : ElementParser(parserState,
    WebIdlParserType.Function
) {
    lateinit var builder: IdlSetLike.Builder

    override suspend fun parse(): String {
        val type = (popUntilPattern(";")?.first ?: parserException("Failed parsing setlike"))
            .substringAfter("<")
            .substringBefore(">")
            .let { IdlType(it, false) }

        builder = IdlSetLike.Builder(type)
        parserState.parentParser<InterfaceParser>().builder.setLike = builder
        parserState.popParser()
        return builder.name
    }
}