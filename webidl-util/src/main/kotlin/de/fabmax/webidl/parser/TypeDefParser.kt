package de.fabmax.webidl.parser

import de.fabmax.webidl.model.IdlTypeDef

class TypeDefParser(parserState: WebIdlParser.ParserState) : ElementParser(parserState,
    WebIdlParserType.TypeDef
) {
    lateinit var builder: IdlTypeDef.Builder

    override suspend fun parse(): String {
        builder = IdlTypeDef.Builder()
        popToken("typedef")
        parseChildren(null)
        parserState.popDecorators(builder)
        builder.type = parseType()
        val tokens = popUntilPattern(";") ?: parserException("Failed parsing member")


        parserState.popParser()
        return tokens.second
    }
}