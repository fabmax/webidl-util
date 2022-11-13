package de.fabmax.webidl.parser

import de.fabmax.webidl.model.IdlEnum

class EnumParser(parserState: WebIdlParser.ParserState) : ElementParser(parserState,
    WebIdlParserType.Interface
) {
    lateinit var builder: IdlEnum.Builder

    override suspend fun parse(): String {
        popToken("enum")
        val name = popUntilPattern("\\{") ?: parserException("Failed parsing enum name")
        builder = IdlEnum.Builder(name.first)
        builder.sourcePackage = parserState.sourcePackage
        parserState.popDecorators(builder)
        parserState.parentParser<RootParser>().builder.addEnum(builder)

        var sep: String? = ","
        while (sep == ",") {
            val next = popUntilPattern("[,\\}]") ?: parserException("Failed values of enum ${name.first}")
            if (next.first.isNotEmpty()) {
                val valName = next.first.substring(1, next.first.lastIndex)
                builder.addValue(valName)
            }
            sep = next.second
        }
        popToken(";")
        parserState.popParser()
        return ";"
    }
}