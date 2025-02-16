package de.fabmax.webidl.parser

import de.fabmax.webidl.model.IdlDictionary

class DictionaryParser(parserState: WebIdlParser.ParserState) : ElementParser(parserState,
    WebIdlParserType.Dictionary
) {
    lateinit var builder: IdlDictionary.Builder

    override suspend fun parse(): String {
        popToken("dictionary")

        val interfaceName = popUntilPattern("\\{") ?: parserException("Failed parsing dictionary name")
        builder = IdlDictionary.Builder(interfaceName.first)
        builder.sourcePackage = parserState.sourcePackage
        parserState.popDecorators(builder)
        parserState.parentParser<RootParser>().builder.addDictionary(builder)

        parseChildren("}")

        popToken(";")
        parserState.popParser()
        return interfaceName.second
    }
}