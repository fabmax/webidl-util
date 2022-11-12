package de.fabmax.webidl.parser

import de.fabmax.webidl.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import java.io.*
import kotlin.coroutines.CoroutineContext

class WebIdlParser(
    modelName: String = "webidl",
    val explodeOptionalFunctionParams: Boolean = true
) : CoroutineScope {

    private val job = Job()
    override val coroutineContext: CoroutineContext
        get() = job

    private val parserStream = WebIdlStream()
    private val parserState = ParserState(parserStream)
    private val rootParser = WebIdlParserType.Root.newParser(parserState) as RootParser

    init {
        rootParser.builder.name = modelName
    }

    private fun asyncParser() = async {
        rootParser.parse()
        if (parserState.parserStack.isNotEmpty()) {
            throw IllegalStateException("Unexpected non-empty parser stack")
        }
    }

    suspend fun parseStream(inStream: InputStream, fileName: String = "webidl") {
        parserState.onNewFile(fileName)
        val parseResult = asyncParser()
        BufferedReader(InputStreamReader(inStream)).use { r ->
            r.lineSequence().forEach {
                parserStream.channel.send(it)
            }
            parserStream.channel.close()
        }
        parseResult.await()
    }

    fun finish(): IdlModel {
        return rootParser.builder.build()
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
                directory.walk().filter { it.name.endsWith(".idl", true) }.forEach { idlFile ->
                    println("Parsing $idlFile")
                    FileInputStream(idlFile).use {
                        parser.parseStream(it, idlFile.name)
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
                parser.parseStream(inStream, modelName)
                parser.finish()
            }
        }
    }

    inner class ParserState(val parserStream: WebIdlStream) {
        val parserStack = ArrayDeque<Parser>()

        val explodeOptionalFunctionParams: Boolean get() = this@WebIdlParser.explodeOptionalFunctionParams

        var currentFileName = ""
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

        fun onNewFile(fileName: String) {
            currentFileName = fileName
            sourcePackage = ""
            parserStream.readLines = 0
            parserStream.channel = Channel(Channel.UNLIMITED)
            pushParser(rootParser)
        }
    }

    class ParserException(msg: String) : Exception(msg)

    abstract class Parser(val parserState: ParserState, val selfType: WebIdlParserType) {
        val stream: WebIdlStream get() = parserState.parserStream

        abstract suspend fun parse(): String?

        suspend fun parseChildren(termToken: String?) {
            var child = selfType.possibleChildren().find { it.matches(stream) }
            var childTerm: String? = null
            while (child != null && (termToken == null || !stream.startsWith(termToken))) {
                childTerm = child.newParser(parserState).parse()
                child = selfType.possibleChildren().find { it.matches(stream) }
            }

            if (child == null && stream.buffer.isNotEmpty()
                && termToken?.let { stream.buffer.startsWith(it) || it == childTerm } != true) {
                parserException("Unexpected idl content")
            }

            if (termToken != null && stream.startsWith(termToken)) {
                popToken(termToken)
            }
        }

        fun parserException(message: String): Nothing {
            var lineNum = 1 + parserState.parserStream.readLines - parserState.parserStream.contextLines.size
            throw ParserException("Invalid $selfType: $message, somewhere around [${parserState.currentFileName}:${parserState.parserStream.readLines}]\n" +
                    parserState.parserStream.contextLines.joinToString(separator = "\n") { " > ${lineNum++}: $it" })
        }

        protected suspend fun popToken(token: String) = parserState.parserStream.popToken(token, this)

        protected suspend fun popUntilPattern(searchPattern: String, abortPattern: String? = null) =
            popUntilPattern(Regex(searchPattern), abortPattern?.let { Regex(it) })

        protected suspend fun popUntilPattern(searchPattern: Regex, abortPattern: Regex? = null): Pair<String, String>? =
                parserState.parserStream.popUntilPattern(searchPattern, abortPattern, this)

        protected suspend fun popIfPresent(token: String): Boolean =
            parserState.parserStream.popIfPresent(token, this)

        protected suspend fun parseType(): IdlType = parserState.parserStream.parseType(this)
    }

    class RootParser(parserState: ParserState) : Parser(parserState, WebIdlParserType.Root) {
        val builder = IdlModel.Builder()

        override suspend fun parse(): String? {
            parseChildren(null)
            parserState.popParser()
            return null
        }
    }

    class LineCommentParser(parserState: ParserState) : Parser(parserState, WebIdlParserType.LineComment) {
        override suspend fun parse(): String? {
            popToken("//")
            parserState.currentComment = popUntilPattern("\\n")?.first
            parserState.popParser()
            return null
        }
    }

    class BlockCommentParser(parserState: ParserState) : Parser(parserState, WebIdlParserType.LineComment) {
        override suspend fun parse(): String? {
            popToken("/*")
            val popComment = popUntilPattern("\\*/")
            parserState.currentComment = popComment?.first?.replace("\n", "\\n")
            parserState.popParser()
            return popComment?.second
        }
    }

    class InterfaceParser(parserState: ParserState) : Parser(parserState, WebIdlParserType.Interface) {
        lateinit var builder: IdlInterface.Builder

        override suspend fun parse(): String {
            popToken("interface")

            val interfaceName = popUntilPattern("\\{") ?: parserException("Failed parsing interface name")
            builder = IdlInterface.Builder(interfaceName.first)
            builder.sourcePackage = parserState.sourcePackage
            parserState.popDecorators(builder)
            parserState.parentParser<RootParser>().builder.addInterface(builder)

            parseChildren("}")

            popToken(";")
            parserState.popParser()
            return interfaceName.second
        }
    }

    class FunctionParser(parserState: ParserState) : Parser(parserState, WebIdlParserType.Function) {
        lateinit var builder: IdlFunction.Builder

        override suspend fun parse(): String {
            val isStatic = popIfPresent("static")
            val type = parseType()
            val name = popUntilPattern("\\(") ?: parserException("Failed parsing function name")
            builder = IdlFunction.Builder(name.first, type)
            builder.explodeOptionalFunctionParams = parserState.explodeOptionalFunctionParams
            builder.isStatic = isStatic
            parserState.popDecorators(builder)
            parserState.parentParser<InterfaceParser>().builder.addFunction(builder)

            parseChildren(")")

            popToken(";")
            parserState.popParser()
            return ";"
        }
    }

    class FunctionParameterParser(parserState: ParserState) : Parser(parserState, WebIdlParserType.FunctionParameter) {
        lateinit var builder: IdlFunctionParameter.Builder

        override suspend fun parse(): String {
            val isOptional = popIfPresent("optional")
            val paramType = parseType()
            val name = popUntilPattern("[,\\)]") ?: parserException("Failed parsing function parameter")
            builder = IdlFunctionParameter.Builder(name.first, paramType)
            builder.isOptional = isOptional
            parserState.popDecorators(builder)
            parserState.parentParser<FunctionParser>().builder.addParameter(builder)

            parserState.popParser()
            return name.second
        }
    }

    class DecoratorParser(parserState: ParserState) : Parser(parserState, WebIdlParserType.Function) {
        override suspend fun parse(): String? {
            popToken("[")
            var sep: String? = ","
            while (sep == ",") {
                val tok = popUntilPattern("[,\\]]") ?: parserException("Failed parsing decorator list")
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
            return sep
        }
    }

    class AttributeParser(parserState: ParserState) : Parser(parserState, WebIdlParserType.Function) {
        lateinit var builder: IdlAttribute.Builder

        override suspend fun parse(): String {
            val qualifiers = popUntilPattern("attribute") ?: parserException("Failed parsing attribute")
            val type = parseType()
            val name = popUntilPattern(";") ?: parserException("Failed parsing attribute")
            builder = IdlAttribute.Builder(name.first, type)
            builder.isStatic = qualifiers.first.contains("static")
            builder.isReadonly = qualifiers.first.contains("readonly")
            parserState.popDecorators(builder)
            parserState.parentParser<InterfaceParser>().builder.addAttribute(builder)

            parserState.popParser()
            return name.second
        }
    }

    class ImplementsParser(parserState: ParserState) : Parser(parserState, WebIdlParserType.Function) {
        override suspend fun parse(): String {
            val concreteIf = popUntilPattern("\\simplements\\s")?.first ?: parserException("Failed parsing interface name")
            val superIf = popUntilPattern(";")?.first ?: parserException("Failed parsing super interface name")

            if (!IdlType.isValidTypeName(concreteIf) || !IdlType.isValidTypeName(superIf)) {
                parserException("Invalid implements statement: $concreteIf implements $superIf")
            }
            parserState.parentParser<RootParser>().builder.addImplements(concreteIf, superIf)
            parserState.popParser()
            return ";"
        }
    }

    class EnumParser(parserState: ParserState) : Parser(parserState, WebIdlParserType.Interface) {
        lateinit var builder: IdlEnum.Builder

        override suspend fun parse(): String {
            popToken("enum")
            val name = popUntilPattern("\\{") ?: parserException("Failed parsing enum name")
            builder = IdlEnum.Builder(name.first)
            builder.sourcePackage = parserState.sourcePackage
            parserState.popDecorators(builder)
            parserState.parentParser<RootParser>().builder.addEnum(builder)

            var sep: String? = ","
            while (sep == ",") {
                val next = popUntilPattern("[,\\}]") ?: parserException("Failed values of enum ${name.first}")
                if (next.first.isNotEmpty()) {
                    val valName = next.first.substring(1, next.first.lastIndex)
                    builder.addValue(valName)
                }
                sep = next.second
            }
            popToken(";")
            parserState.popParser()
            return ";"
        }
    }

    enum class WebIdlParserType {
        Root {
            override fun possibleChildren() = listOf(Interface, Enum, LineComment, BlockComment, Decorators, Implements)
            override suspend fun matches(stream: WebIdlStream) = false
            override fun newParser(parserState: ParserState) = RootParser(parserState)
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