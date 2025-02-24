package de.fabmax.webidl.parser

import de.fabmax.webidl.model.IdlFunctionParameter
import de.fabmax.webidl.model.IdlMember

class FunctionParameterParser(parserState: WebIdlParser.ParserState) : ElementParser(parserState,
    WebIdlParserType.FunctionParameter
) {
    lateinit var builder: IdlFunctionParameter.Builder

    override suspend fun parse(): String {
        val isOptional = popIfPresent("optional")
        val paramType = parseType()
        val tokens = popUntilPattern("[,\\)]") ?: parserException("Failed parsing function parameter")
        builder = if (tokens.first.contains("=")) {
            val name = tokens.first.substringBefore("=").trim()
            IdlFunctionParameter.Builder(name, paramType).also {
                it.defaultValue = tokens.first.substringAfter("=").trim()
            }
        } else IdlFunctionParameter.Builder(tokens.first, paramType)
        builder.isOptional = isOptional
        parserState.popDecorators(builder)
        parserState.parentParserOrNull<FunctionParser>()?.builder?.addParameter(builder)
        parserState.parentParserOrNull<ConstructorParser>()?.builder?.addParameter(builder)

        parserState.popParser()
        return tokens.second
    }
}