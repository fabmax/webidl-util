package de.fabmax.webidl.parser

import de.fabmax.webidl.model.IdlType

class IncludesParser(parserState: WebIdlParser.ParserState) : ElementParser(parserState,
    WebIdlParserType.Function
) {
    override suspend fun parse(): String {
        val concreteIf = popUntilPattern("\\sincludes\\s")?.first ?: parserException("Failed parsing interface name")
        val superIf = popUntilPattern(";")?.first ?: parserException("Failed parsing super interface name")

        if (!IdlType.isValidTypeName(concreteIf) || !IdlType.isValidTypeName(superIf)) {
            parserException("Invalid implements statement: $concreteIf implements $superIf")
        }
        parserState.parentParser<RootParser>().builder.addIncludes(concreteIf, superIf)
        parserState.popParser()
        return ";"
    }
}