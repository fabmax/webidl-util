package de.fabmax.webidl.parser

import de.fabmax.webidl.model.IdlDecorator

class DecoratorParser(parserState: WebIdlParser.ParserState) : ElementParser(parserState,
    WebIdlParserType.Function
) {
    override suspend fun parse(): String? {
        popToken("[")
        var sep: String? = ","
        while (sep == ",") {
            val tok = popUntilPattern("[,\\]]") ?: parserException("Failed parsing decorator list")
            val splitIdx = tok.first.indexOf('=')
            val decorator = if (splitIdx > 0) {
                val value = tok.first.substring(splitIdx+1).trim().removeSurrounding("\"")
                IdlDecorator(tok.first.substring(0 until splitIdx).trim(), value)
            } else {
                IdlDecorator(tok.first, null)
            }
            parserState.currentDecorators.add(decorator)
            sep = tok.second
        }
        parserState.popParser()
        return sep
    }
}