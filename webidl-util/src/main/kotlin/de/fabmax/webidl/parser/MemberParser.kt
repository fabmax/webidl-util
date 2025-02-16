package de.fabmax.webidl.parser

import de.fabmax.webidl.model.IdlMember

class MemberParser(parserState: WebIdlParser.ParserState) : ElementParser(parserState,
    WebIdlParserType.Dictionary
) {
    lateinit var builder: IdlMember.Builder

    override suspend fun parse(): String {
        val isRequired = popIfPresent("required")
        val type = parseType()
        val tokens = popUntilPattern(";") ?: parserException("Failed parsing member")
        builder = if (tokens.first.contains("=")) {
            val name = tokens.first.substringBefore("=").trim()
            IdlMember.Builder(name, type).also {
                it.defaultValue = tokens.first.substringAfter("=").trim()
            }
        } else IdlMember.Builder(tokens.first, type)
        builder.isRequired = isRequired
        parserState.popDecorators(builder)
        parserState.parentParser<DictionaryParser>().builder.addAttribute(builder)

        parserState.popParser()
        return tokens.second
    }
}