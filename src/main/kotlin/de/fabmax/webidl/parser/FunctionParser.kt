package de.fabmax.webidl.parser

import de.fabmax.webidl.model.IdlFunction

class FunctionParser(parserState: WebIdlParser.ParserState) : ElementParser(parserState,
    WebIdlParserType.Function
) {
    lateinit var builder: IdlFunction.Builder

    override suspend fun parse(): String {
        val isStatic = popIfPresent("static")
        val type = parseType()
        val name = popUntilPattern("\\(") ?: parserException("Failed parsing function name")
        builder = IdlFunction.Builder(name.first, type)
        builder.explodeOptionalFunctionParams = parserState.explodeOptionalFunctionParams
        builder.isStatic = isStatic
        parserState.popDecorators(builder)
        parserState.parentParser<InterfaceParser>().builder.addFunction(builder)

        parseChildren(")")

        popToken(";")
        parserState.popParser()
        return ";"
    }
}