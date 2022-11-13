package de.fabmax.webidl.parser

class BlockCommentParser(parserState: WebIdlParser.ParserState) : ElementParser(parserState,
    WebIdlParserType.LineComment
) {
    override suspend fun parse(): String? {
        popToken("/*")
        val popComment = popUntilPattern("\\*/")
        parserState.currentComment = popComment?.first?.replace("\n", "\\n")
        parserState.popParser()
        return popComment?.second
    }
}