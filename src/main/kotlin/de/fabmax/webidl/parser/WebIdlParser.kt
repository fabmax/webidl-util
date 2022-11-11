package de.fabmax.webidl.parser

import de.fabmax.webidl.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.io.*
import kotlin.coroutines.CoroutineContext

class WebIdlParser(
    val modelName: String = "webidl",
    val explodeOptionalFunctionParams: Boolean = true
) : CoroutineScope {

    private val job = Job()
    override val coroutineContext: CoroutineContext
        get() = job

    private val parserState = ParserState()
    private val processorStream = WebIdlStream()

    val parseResult = async {
        val parser = WebIdlParserType.Root.newParser(parserState) as RootParser
        parser.builder.name = modelName
        parser.parse(processorStream)
        if (parserState.parserStack.isNotEmpty()) {
            throw IllegalStateException("Unexpected non-empty parser stack")
        }
        parser.builder.build()
    }

    suspend fun parseStream(inStream: InputStream) {
        parserState.onNewFile()
        BufferedReader(InputStreamReader(inStream)).use { r ->
            r.lineSequence().forEach {
                processorStream.channel.send(it)
            }
        }
    }

    suspend fun finish(): IdlModel {
        processorStream.channel.close()
        return parseResult.await()
    }

    companion object {
        fun parseDirectory(path: String, modelName: String? = null): IdlModel {
            val directory = File(path)
            if (!directory.exists() || !directory.isDirectory) {
                throw IOException("Given path is not a directory")
            }
            val name = modelName ?: directory.name

            return runBlocking {
                val parser = WebIdlParser(name)
                directory.listFiles { _, name -> name.endsWith(".idl", true) }?.forEach { idlFile ->
                    println("Parsing $idlFile")
                    FileInputStream(idlFile).use {
                        parser.parseStream(it)
                    }
                }
                parser.finish()
            }
        }

        fun parseSingleFile(path: String, modelName: String? = null): IdlModel {
            return FileInputStream(path).use {
                val name = modelName ?: File(path).name.replace(".idl", "", true)
                parseFromInputStream(it, name)
            }
        }

        fun parseFromInputStream(inStream: InputStream, modelName: String = "webidl"): IdlModel {
            return runBlocking {
                val parser = WebIdlParser(modelName)
                parser.parseStream(inStream)
                parser.finish()
            }
        }
    }

    private inner class ParserState {
        val parserStack = ArrayDeque<Parser>()

        val explodeOptionalFunctionParams: Boolean get() = this@WebIdlParser.explodeOptionalFunctionParams

        var sourcePackage = ""

        var currentDecorators = mutableSetOf<IdlDecorator>()
        var currentComment: String? = null
            set(value) {
                value?.let { evaluateMetaTags(it) }
                field = value
            }

        inline fun<reified T: Parser> parentParser(): T {
            return parserStack[parserStack.lastIndex - 1] as? T ?: throw IllegalStateException("Unexpected parent parser type")
        }

        fun <T: Parser> pushParser(builder: T): T {
            parserStack.addLast(builder)
            return builder
        }

        fun popParser(): Parser = parserStack.removeLast()

        fun popDecorators(target: IdlDecoratedElement.Builder) {
            target.decorators += currentDecorators
            currentDecorators = mutableSetOf()
        }

        fun evaluateMetaTags(comment: String) {
            val tagPattern = Regex("\\[\\w+=[^]]*]?")
            tagPattern.findAll(comment).forEach {
                val tag = comment.substring(it.range)
                val split = tag.indexOf('=')
                val key = tag.substring(1, split)
                val value = tag.substring(split + 1, tag.length - 1)

                when (key) {
                    "package" -> sourcePackage = value
                }
            }
        }

        fun onNewFile() {
            sourcePackage = ""
        }
    }

    class ParserException(msg: String) : Exception(msg)

    private abstract class Parser(val parserState: ParserState, val selfType: WebIdlParserType) {
        abstract suspend fun parse(stream: WebIdlStream)

        suspend fun parseChildren(stream: WebIdlStream, termToken: String?) {
            var child = selfType.possibleChildren().find { it.matches(stream) }
            while (child != null && (termToken == null || !stream.startsWith(termToken))) {
                child.newParser(parserState).parse(stream)
                child = selfType.possibleChildren().find { it.matches(stream) }
            }
            if (termToken != null && stream.startsWith(termToken)) {
                stream.popToken(termToken)
            }
        }
    }

    private class RootParser(parserState: ParserState) : Parser(parserState, WebIdlParserType.Root) {
        val builder = IdlModel.Builder()

        override suspend fun parse(stream: WebIdlStream) {
            parseChildren(stream, null)
            parserState.popParser()
        }
    }

    private class LineCommentParser(parserState: ParserState) : Parser(parserState, WebIdlParserType.LineComment) {
        override suspend fun parse(stream: WebIdlStream) {
            stream.popToken("//")
            parserState.currentComment = stream.popUntilPattern("\\n")?.first
            parserState.popParser()
        }
    }

    private class BlockCommentParser(parserState: ParserState) : Parser(parserState, WebIdlParserType.LineComment) {
        override suspend fun parse(stream: WebIdlStream) {
            stream.popToken("/*")
            parserState.currentComment = stream.popUntilPattern("\\*/")?.first?.replace("\n", "\\n")
            parserState.popParser()
        }
    }

    private class InterfaceParser(parserState: ParserState) : Parser(parserState, WebIdlParserType.Interface) {
        lateinit var builder: IdlInterface.Builder

        override suspend fun parse(stream: WebIdlStream) {
            stream.popToken("interface")

            val interfaceName = stream.popUntilPattern("\\{") ?: throw ParserException("Failed parsing interface name")
            builder = IdlInterface.Builder(interfaceName.first)
            builder.sourcePackage = parserState.sourcePackage
            parserState.popDecorators(builder)
            parserState.parentParser<RootParser>().builder.addInterface(builder)

            parseChildren(stream, "}")

            stream.popToken(";")
            parserState.popParser()
        }
    }

    private class FunctionParser(parserState: ParserState) : Parser(parserState, WebIdlParserType.Function) {
        lateinit var builder: IdlFunction.Builder

        override suspend fun parse(stream: WebIdlStream) {
            val isStatic = stream.popIfPresent("static")
            val type = stream.parseType()
            val name = stream.popUntilPattern("\\(") ?: throw ParserException("Failed parsing function name")
            builder = IdlFunction.Builder(name.first, type)
            builder.explodeOptionalFunctionParams = parserState.explodeOptionalFunctionParams
            builder.isStatic = isStatic
            parserState.popDecorators(builder)
            parserState.parentParser<InterfaceParser>().builder.addFunction(builder)

            parseChildren(stream, ")")

            stream.popToken(";")
            parserState.popParser()
        }
    }

    private class FunctionParameterParser(parserState: ParserState) : Parser(parserState, WebIdlParserType.FunctionParameter) {
        lateinit var builder: IdlFunctionParameter.Builder

        override suspend fun parse(stream: WebIdlStream) {
            val isOptional = stream.popIfPresent("optional")
            val paramType = stream.parseType()
            val name = stream.popUntilPattern("[,\\)]") ?: throw ParserException("Failed parsing function parameter")
            builder = IdlFunctionParameter.Builder(name.first, paramType)
            builder.isOptional = isOptional
            parserState.popDecorators(builder)
            parserState.parentParser<FunctionParser>().builder.addParameter(builder)

            parserState.popParser()
        }
    }

    private class DecoratorParser(parserState: ParserState) : Parser(parserState, WebIdlParserType.Function) {
        override suspend fun parse(stream: WebIdlStream) {
            stream.popToken("[")
            var sep: String? = ","
            while (sep == ",") {
                val tok = stream.popUntilPattern("[,\\]]") ?: throw ParserException("Failed parsing decorator list")
                val splitIdx = tok.first.indexOf('=')
                val decorator = if (splitIdx > 0) {
                    var value = tok.first.substring(splitIdx+1).trim()
                    if (value.startsWith("\"") && value.endsWith("\"")) {
                        value = value.substring(1, value.length - 1)
                    }
                    IdlDecorator(tok.first.substring(0 until splitIdx).trim(), value)
                } else {
                    IdlDecorator(tok.first, null)
                }
                parserState.currentDecorators.add(decorator)
                sep = tok.second
            }
            parserState.popParser()
        }
    }

    private class AttributeParser(parserState: ParserState) : Parser(parserState, WebIdlParserType.Function) {
        lateinit var builder: IdlAttribute.Builder

        override suspend fun parse(stream: WebIdlStream) {
            val qualifiers = stream.popUntilPattern("attribute") ?: throw ParserException("Failed parsing attribute")
            val type = stream.parseType()
            val name = stream.popUntilPattern(";") ?: throw ParserException("Failed parsing attribute")
            builder = IdlAttribute.Builder(name.first, type)
            builder.isStatic = qualifiers.first.contains("static")
            builder.isReadonly = qualifiers.first.contains("readonly")
            parserState.popDecorators(builder)
            parserState.parentParser<InterfaceParser>().builder.addAttribute(builder)

            parserState.popParser()
        }
    }

    private class ImplementsParser(parserState: ParserState) : Parser(parserState, WebIdlParserType.Function) {
        override suspend fun parse(stream: WebIdlStream) {
            val concreteIf = stream.popUntilPattern("\\simplements\\s")?.first ?: throw ParserException("Failed parsing interface name")
            val superIf = stream.popUntilPattern(";")?.first ?: throw ParserException("Failed parsing super interface name")

            if (!IdlType.isValidTypeName(concreteIf) || !IdlType.isValidTypeName(superIf)) {
                throw ParserException("Invalid implements statement: $concreteIf implements $superIf")
            }
            parserState.parentParser<RootParser>().builder.addImplements(concreteIf, superIf)
            parserState.popParser()
        }
    }

    private class EnumParser(parserState: ParserState) : Parser(parserState, WebIdlParserType.Interface) {
        lateinit var builder: IdlEnum.Builder

        override suspend fun parse(stream: WebIdlStream) {
            stream.popToken("enum")
            val name = stream.popUntilPattern("\\{") ?: throw ParserException("Failed parsing enum name")
            builder = IdlEnum.Builder(name.first)
            builder.sourcePackage = parserState.sourcePackage
            parserState.popDecorators(builder)
            parserState.parentParser<RootParser>().builder.addEnum(builder)

            var sep: String? = ","
            while (sep == ",") {
                val next = stream.popUntilPattern("[,\\}]") ?: throw ParserException("Failed values of enum ${name.first}")
                if (next.first.isNotEmpty()) {
                    val valName = next.first.substring(1, next.first.lastIndex)
                    builder.addValue(valName)
                }
                sep = next.second
            }
            stream.popToken(";")
            parserState.popParser()
        }
    }

    private enum class WebIdlParserType {
        Root {
            override fun possibleChildren() = listOf(Interface, Enum, LineComment, BlockComment, Decorators, Implements)
            override suspend fun matches(stream: WebIdlStream) = false
            override fun newParser(parserState: ParserState) = parserState.pushParser(RootParser(parserState))
        },

        Interface {
            override fun possibleChildren() = listOf(Decorators, LineComment, BlockComment, Attribute, Function)
            override suspend fun matches(stream: WebIdlStream) = stream.startsWith("interface")
            override fun newParser(parserState: ParserState) = parserState.pushParser(InterfaceParser(parserState))
        },

        Attribute {
            override fun possibleChildren(): List<WebIdlParserType> = emptyList()
            override suspend fun matches(stream: WebIdlStream) = stream.startsWith("attribute")
                    || stream.startsWith("static attribute")
                    || stream.startsWith("static readonly attribute")
            override fun newParser(parserState: ParserState) = parserState.pushParser(AttributeParser(parserState))
        },

        Function {
            override fun possibleChildren(): List<WebIdlParserType> = listOf(Decorators, FunctionParameter)
            override suspend fun matches(stream: WebIdlStream): Boolean {
                var line = stream.pollUntilPattern("[\\(]", "[,;\\{\\}]")?.first ?: return false
                if (line.startsWith("static")) {
                    line = line.substring(7)
                }
                return IdlType.startsWithType(line)
            }
            override fun newParser(parserState: ParserState) = parserState.pushParser(FunctionParser(parserState))
        },

        FunctionParameter {
            override fun possibleChildren(): List<WebIdlParserType> = emptyList()
            override suspend fun matches(stream: WebIdlStream): Boolean {
                val line = stream.pollUntilPattern("[,\\)]", "[;\\{\\}]")?.first ?: return false
                return line.startsWith("optional") || IdlType.startsWithType(line)
            }
            override fun newParser(parserState: ParserState) = parserState.pushParser(FunctionParameterParser(parserState))
        },

        Enum {
            override fun possibleChildren(): List<WebIdlParserType> = emptyList()
            override suspend fun matches(stream: WebIdlStream) = stream.startsWith("enum")
            override fun newParser(parserState: ParserState) = parserState.pushParser(EnumParser(parserState))
        },

        LineComment {
            override fun possibleChildren(): List<WebIdlParserType> = emptyList()
            override suspend fun matches(stream: WebIdlStream) = stream.startsWith("//")
            override fun newParser(parserState: ParserState) = parserState.pushParser(LineCommentParser(parserState))
        },

        BlockComment {
            override fun possibleChildren(): List<WebIdlParserType> = emptyList()
            override suspend fun matches(stream: WebIdlStream) = stream.startsWith("/*")
            override fun newParser(parserState: ParserState) = parserState.pushParser(BlockCommentParser(parserState))
        },

        Decorators {
            override fun possibleChildren(): List<WebIdlParserType> = emptyList()
            override suspend fun matches(stream: WebIdlStream) = stream.startsWith("[")
            override fun newParser(parserState: ParserState) = parserState.pushParser(DecoratorParser(parserState))
        },

        Implements {
            override fun possibleChildren(): List<WebIdlParserType> = emptyList()
            override suspend fun matches(stream: WebIdlStream) = stream.pollUntilPattern("\\simplements\\s", "[;,\\{\\}]") != null
            override fun newParser(parserState: ParserState) = parserState.pushParser(ImplementsParser(parserState))
        };

        abstract fun possibleChildren(): List<WebIdlParserType>
        abstract suspend fun matches(stream: WebIdlStream): Boolean
        abstract fun newParser(parserState: ParserState): Parser
    }
}