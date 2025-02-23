package de.fabmax.webidl.parser

import de.fabmax.webidl.model.IdlFunctionParameter

class FunctionParameterParser(parserState: WebIdlParser.ParserState) : ElementParser(parserState,
    WebIdlParserType.FunctionParameter
) {
    lateinit var builder: IdlFunctionParameter.Builder

    override suspend fun parse(): String {
        val isOptional = popIfPresent("optional")
        val paramType = parseType()
        val name = popUntilPattern("[,\\)]") ?: parserException("Failed parsing function parameter")
        builder = IdlFunctionParameter.Builder(name.first, paramType)
        builder.isOptional = isOptional
        parserState.popDecorators(builder)
        parserState.parentParserOrNull<FunctionParser>()?.builder?.addParameter(builder)
        parserState.parentParserOrNull<ConstructorParser>()?.builder?.addParameter(builder)

        parserState.popParser()
        return name.second
    }
}