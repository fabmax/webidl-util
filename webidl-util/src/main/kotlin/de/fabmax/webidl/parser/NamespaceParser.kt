package de.fabmax.webidl.parser

import de.fabmax.webidl.model.IdlNamespace

class NamespaceParser(parserState: WebIdlParser.ParserState) : ElementParser(parserState,
    WebIdlParserType.Namespace
) {
    lateinit var builder: IdlNamespace.Builder

    override suspend fun parse(): String {
        popToken("namespace")

        val interfaceName = popUntilPattern("\\{") ?: parserException("Failed parsing interface name")
        builder = IdlNamespace.Builder(interfaceName.first)
        builder.sourcePackage = parserState.sourcePackage
        parserState.popDecorators(builder)
        parserState.parentParser<RootParser>().builder.addNamespace(builder)

        parseChildren("}")

        popToken(";")
        parserState.popParser()
        return interfaceName.second
    }
}