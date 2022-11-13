package de.fabmax.webidl.parser

import de.fabmax.webidl.model.IdlDecoratedElement
import de.fabmax.webidl.model.IdlDecorator
import de.fabmax.webidl.model.IdlModel
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
        val parserStack = ArrayDeque<ElementParser>()

        val explodeOptionalFunctionParams: Boolean get() = this@WebIdlParser.explodeOptionalFunctionParams

        var currentFileName = ""
        var sourcePackage = ""

        var currentDecorators = mutableSetOf<IdlDecorator>()
        var currentComment: String? = null
            set(value) {
                value?.let { evaluateMetaTags(it) }
                field = value
            }

        inline fun<reified T: ElementParser> parentParser(): T {
            return parserStack[parserStack.lastIndex - 1] as? T ?: throw IllegalStateException("Unexpected parent parser type")
        }

        fun <T: ElementParser> pushParser(builder: T): T {
            parserStack.addLast(builder)
            return builder
        }

        fun popParser(): ElementParser = parserStack.removeLast()

        fun popDecorators(target: IdlDecoratedElement.Builder) {
            target.decorators += currentDecorators
            currentDecorators = mutableSetOf()
        }

        private fun evaluateMetaTags(comment: String) {
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
}