package de.fabmax.webidl.parser

import de.fabmax.webidl.model.IdlModel

class RootParser(parserState: WebIdlParser.ParserState) : ElementParser(parserState, WebIdlParserType.Root) {
    val builder = IdlModel.Builder()

    override suspend fun parse(): String? {
        parseChildren(null)
        parserState.popParser()
        return null
    }
}