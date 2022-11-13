package de.fabmax.webidl.parser

class LineCommentParser(parserState: WebIdlParser.ParserState) : ElementParser(parserState,
    WebIdlParserType.LineComment
) {
    override suspend fun parse(): String? {
        popToken("//")
        parserState.currentComment = popUntilPattern("\\n")?.first
        parserState.popParser()
        return null
    }
}