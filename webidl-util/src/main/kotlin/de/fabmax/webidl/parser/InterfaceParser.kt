package de.fabmax.webidl.parser

import de.fabmax.webidl.model.IdlInterface

class InterfaceParser(parserState: WebIdlParser.ParserState) : ElementParser(parserState,
    WebIdlParserType.Interface
) {
    lateinit var builder: IdlInterface.Builder

    override suspend fun parse(): String {
        val isPartial = popIfPresent("partial")
        popToken("interface")
        val isMixin = popIfPresent("mixin")

        val interfaceName = popUntilPattern("\\{") ?: parserException("Failed parsing interface name")
        builder = IdlInterface.Builder(interfaceName.first)
        builder.sourcePackage = parserState.sourcePackage
        builder.isMixin = isMixin
        builder.isPartial = isPartial
        parserState.popDecorators(builder)
        parserState.parentParser<RootParser>().builder.addInterface(builder)

        parseChildren("}")

        popToken(";")
        parserState.popParser()
        return interfaceName.second
    }
}