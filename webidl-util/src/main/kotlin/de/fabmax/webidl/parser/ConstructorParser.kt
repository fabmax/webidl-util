package de.fabmax.webidl.parser

import de.fabmax.webidl.model.IdlConstructor
import de.fabmax.webidl.model.IdlFunction

class ConstructorParser(parserState: WebIdlParser.ParserState) : ElementParser(parserState,
    WebIdlParserType.Constructor
) {
    lateinit var builder: IdlConstructor.Builder

    override suspend fun parse(): String {
        val name = popUntilPattern("\\(") ?: parserException("Failed parsing function name")
        builder = IdlConstructor.Builder(name.first)
        builder.explodeOptionalFunctionParams = parserState.explodeOptionalFunctionParams
        parserState.popDecorators(builder)
        parserState.parentParser<InterfaceParser>().builder.addConstructor(builder)

        parseChildren(")")

        popToken(";")
        parserState.popParser()
        return ";"
    }
}