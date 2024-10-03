package de.fabmax.webidl.parser

import de.fabmax.webidl.model.IdlAttribute

class AttributeParser(parserState: WebIdlParser.ParserState) : ElementParser(parserState,
    WebIdlParserType.Function
) {
    lateinit var builder: IdlAttribute.Builder

    override suspend fun parse(): String {
        val qualifiers = popUntilPattern("attribute") ?: parserException("Failed parsing attribute")
        val type = parseType()
        val name = popUntilPattern(";") ?: parserException("Failed parsing attribute")
        builder = IdlAttribute.Builder(name.first, type)
        builder.isStatic = qualifiers.first.contains("static")
        builder.isReadonly = qualifiers.first.contains("readonly")
        parserState.popDecorators(builder)
        parserState.parentParser<InterfaceParser>().builder.addAttribute(builder)

        parserState.popParser()
        return name.second
    }
}