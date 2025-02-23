package de.fabmax.webidl.parser

import de.fabmax.webidl.model.IdlTypeDef

class TypeDefParser(parserState: WebIdlParser.ParserState) : ElementParser(parserState,
    WebIdlParserType.TypeDef
) {

    override suspend fun parse(): String {
        val builder = IdlTypeDef.Builder()
        popToken("typedef")
        parseChildren()
        parserState.popDecorators(builder)
        builder.type = parseType()
        val tokens = popUntilPattern(";") ?: parserException("Failed parsing member")
        builder.name = tokens.first.trim()
        parserState.parentParser<RootParser>().builder.addTypeDef(builder)
        parserState.popParser()
        return tokens.second
    }
}