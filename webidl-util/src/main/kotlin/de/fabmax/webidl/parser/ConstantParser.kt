package de.fabmax.webidl.parser

import de.fabmax.webidl.model.IdlConstant

class ConstantParser(parserState: WebIdlParser.ParserState) : ElementParser(
    parserState,
    WebIdlParserType.Constant
) {

    override suspend fun parse(): String {
        popToken("const")
        val type = parseType()
        val tokens = popUntilPattern(";") ?: parserException("Failed parsing constant")
        val name = tokens.first.substringBefore("=").trim()
        val builder = IdlConstant.Builder(name, type).also {
            it.defaultValue = tokens.first.substringAfter("=").trim()
        }
        parserState.popDecorators(builder)
        parserState.parentParser<NamespaceParser>().builder.addConstant(builder)

        parserState.popParser()
        return tokens.second
    }
}