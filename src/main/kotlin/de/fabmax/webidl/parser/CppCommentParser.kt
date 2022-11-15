package de.fabmax.webidl.parser

import java.io.File
import java.util.*

object CppCommentParser {

    private val cachedComments = mutableMapOf<File, List<CppComments>>()

    fun parseComments(file: File): List<CppComments> {
        return if (file.isDirectory) {
            file.walk()
                .filter { it.name.endsWith(".h", true) }
                .flatMap { parseCommentsFile(it) }
                .toList()
        } else {
            parseCommentsFile(file)
        }
    }

    fun parseCommentsFile(cppFile: File): List<CppComments> {
        if (cppFile in cachedComments) {
            return cachedComments[cppFile]!!
        }

        val parser = CppParser(cppFile)
        val nonEmptyClasses = parser.classes.filter { it.isNotEmpty } + parser.enums.filter { it.isNotEmpty }
        cachedComments[cppFile] = nonEmptyClasses
        return nonEmptyClasses
    }

    private class CppParser(val cppFile: File) {
        val tokenizer = CppTokenizer(cppFile.readLines())

        val modeStack = mutableListOf(Mode.ROOT)
        val mode: Mode get() = modeStack.last()
        var latestCommentString: String? = null

        val blockDelimiters = listOf("/**", "/*", "{", "}", ";")
        val commentDelimiters = listOf("*/")
        val enumDelimiters = listOf(",", "}", "/**", "/*")
        val delimiters = mapOf(
            Mode.ROOT to blockDelimiters,
            Mode.NAMESPACE to blockDelimiters,
            Mode.CLASS to blockDelimiters,
            Mode.INNER_BLOCK to blockDelimiters,
            Mode.DOC_COMMENT to commentDelimiters,
            Mode.MULTI_LINE_COMMENT to commentDelimiters,
            Mode.ENUM to enumDelimiters,
        )

        val namespaceRegex = Regex("[\\s\\n]namespace[\\s\\n]")
        val classRegex = Regex("[\\s\\n]class[\\s\\n]")
        val structRegex = Regex("[\\s\\n]struct[\\s\\n]")
        val enumRegex = Regex("[\\s\\n]enum[\\s\\n]")

        val namespacePath = mutableListOf<String>()
        val nestedClassPath = mutableListOf<String>()

        val classes = mutableListOf<CppClassComments>()
        val currentClass: CppClassComments? get() = classes.lastOrNull()
        val enums = mutableListOf<CppEnumComments>()
        val currentEnum: CppEnumComments? get() = enums.lastOrNull()

        init {
            do {
                val delims = delimiters[mode]!!
                val (part, delim) = tokenizer.readUntil(delims, mode != Mode.DOC_COMMENT)

                //println("  : $mode > ${part.lines().joinToString("\\n")}")

                when (modeStack.last()) {
                    Mode.ROOT -> processRoot(part, delim)
                    Mode.DOC_COMMENT -> processDocComment(part)
                    Mode.NAMESPACE -> processNamespace(part, delim)
                    Mode.CLASS -> processClass(part, delim)
                    Mode.ENUM -> processEnum(part, delim)
                    Mode.INNER_BLOCK -> processBlock(part, delim)
                    Mode.MULTI_LINE_COMMENT -> modeStack.removeLast()
                }
            } while (delim != null)
        }

        fun popMode(): Mode {
            return if (modeStack.size > 1) {
                modeStack.removeLast()
            } else {
                System.err.println("[CppCommentParser] Parser mode stack underflow: $cppFile")
                modeStack[0]
            }
        }

        fun processComment(delim: String?): Boolean {
            return when (delim) {
                "/**" -> {
                    // start of doc comment, content of part is irrelevant
                    modeStack += Mode.DOC_COMMENT
                    true
                }
                "/*" -> {
                    // start of multi-line comment, content of part is irrelevant
                    modeStack += Mode.MULTI_LINE_COMMENT
                    true
                }
                else -> false
            }
        }

        fun processBlock(part: String, delim: String?) {
            if (processComment(delim)) {
                return
            }

            val elem = when {
                part.contains(namespaceRegex) -> {
                    namespacePath += part.substringAfter("namespace").trim()
                    Mode.NAMESPACE
                }
                part.contains(classRegex) -> {
                    val className = part.substringAfter("class").substringBefore(":").trim()
                    enterClass(className, delim)
                    Mode.CLASS
                }
                part.contains(structRegex) -> {
                    val structName = part.substringAfter("struct").substringBefore(":").trim()
                    enterClass(structName, delim)
                    Mode.CLASS
                }
                part.contains(enumRegex) -> {
                    val enumName = part.substringAfter("enum").trim()
                    enterEnum(enumName)
                    Mode.ENUM
                }
                else -> {
                    if (mode == Mode.CLASS && part.isNotBlank()) {
                        parseClassMember(part)
                    }
                    Mode.INNER_BLOCK
                }
            }

            if (delim == "{") {
                //println("line ${tokenizer.processedLines}: enter $elem")
                modeStack += elem

            } else if (delim == "}") {
                //println("line ${tokenizer.processedLines}: exit $mode")
                when (popMode()) {
                    Mode.CLASS -> nestedClassPath.removeLast()
                    Mode.NAMESPACE -> namespacePath.removeLast()
                    else -> { }
                }
            }

            // any latest comment was consumed by this block
            latestCommentString = null
        }

        private fun enterClass(className: String, delim: String?) {
            if (delim?.endsWith("{") == true) {
                classes += CppClassComments(namespacePath.joinToString("::"), className, latestCommentString)
                nestedClassPath += className
            }
        }

        private fun enterEnum(enumName: String) {
            val prefixedName = (currentClass?.className ?: "") + enumName
            enums += CppEnumComments(namespacePath.joinToString("::"), prefixedName, latestCommentString)
        }

        fun processRoot(part: String, delim: String?) = processBlock(part, delim)

        fun processNamespace(part: String, delim: String?) = processBlock(part, delim)

        fun processClass(part: String, delim: String?) = processBlock(part, delim)

        fun processEnum(part: String, delim: String?) {
            if (processComment(delim)) {
                return
            }
            if (part.isNotBlank()) {
                val valueName = part.substringBefore("=").trim()
                val value = part.substringAfter("=", "").trim()
                currentEnum?.enumValues?.put(valueName, CppEnumComment(valueName, value, latestCommentString))
            }
            if (delim == "}") {
                popMode()
            }
            latestCommentString = null
        }

        fun processDocComment(part: String) {
            val commentLines = part.trimIndent().lines()
            if (commentLines.any { it.isNotBlank() }) {
                latestCommentString = commentLines
                    .dropWhile { it.isBlank() }
                    .dropLastWhile { it.isBlank() }
                    .joinToString("\n")
            }
            popMode()
        }

        fun parseClassMember(part: String) {
            if (part.contains("(")) {
                parseClassMemberFunction(part)
            } else {
                parseClassMemberAttribute(part)
            }
        }

        fun parseClassMemberFunction(part: String) {
            val (funName, retType) = splitNameAndType(part.substringBefore("("))
            val params = part.findParamsPart()
                .split(",")
                .filter { it.isNotBlank() }
                .map {
                    val isOptional = it.contains("=")
                    val (pName, pType) = splitNameAndType(it.substringBefore("="))
                    CppMethodParameter(pName, pType, isOptional)
                }
            val funComments = currentClass?.methods?.getOrPut(funName) { mutableListOf() }
            funComments?.add(CppMethodComment(funName, retType, params, latestCommentString))
        }

        private fun String.findParamsPart(): String {
            val start = indexOf('(') + 1
            var pos = start
            var parenthesisCnt = 1
            while (pos < length) {
                if (this[pos] == '(') {
                    parenthesisCnt++
                } else if (this[pos] == ')') {
                    parenthesisCnt--
                }
                if (parenthesisCnt == 0) {
                    break
                }
                pos++
            }
            return substring(start until pos)
        }

        fun parseClassMemberAttribute(part: String) {
            val (attrName, type) = splitNameAndType(part)
            currentClass?.attributes?.put(attrName, CppAttributeComment(attrName, type, latestCommentString))
        }

        private fun splitNameAndType(part: String): Pair<String, String> {
            val trimmed = part.trim()
            val nameStart = trimmed.indexOfLast { it.isWhitespace() }
            val name = if (nameStart < 0) trimmed else trimmed.substring(nameStart + 1)

            val type = if (nameStart > 0) {
                val typePart = trimmed.substring(0 until nameStart).trim()
                val typeStart = typePart.indexOfLast { it.isWhitespace() }
                if (typeStart < 0) typePart else typePart.substring(typeStart + 1)
            } else {
                ""
            }
            return name to type
        }

        enum class Mode {
            ROOT,
            MULTI_LINE_COMMENT,
            DOC_COMMENT,
            NAMESPACE,
            CLASS,
            ENUM,
            INNER_BLOCK
        }
    }

    private class CppTokenizer(cppSourceLines: List<String>) {
        val cppSource = LinkedList(cppSourceLines)
        var processedLines = 0

        fun readUntil(delimiters: List<String>, skipLineComments: Boolean): Pair<String, String?> {
            var minLineCount = 0
            var minPos = Integer.MAX_VALUE
            var selectedDelim: String? = null
            var part = StringBuilder()

            for (delim in delimiters) {
                var lineCount = 0
                var delimPos = 0
                var delimFound = false
                val delimPart = StringBuilder()
                for (line in cppSource) {
                    val truncLine = if (skipLineComments) line.substringBefore("//") else line
                    val delimIndex = truncLine.indexOf(delim)
                    if (delimIndex >= 0) {
                        delimPart.append(truncLine.substring(0 until delimIndex))
                        delimPos += delimIndex
                        delimFound = true
                        break
                    } else {
                        delimPart.append(truncLine).append("\n")
                        delimPos += truncLine.length
                        lineCount++
                    }
                }
                if (delimFound && delimPos < minPos) {
                    minPos = delimPos
                    minLineCount = lineCount
                    selectedDelim = delim
                    part = delimPart
                }
            }

            processedLines += minLineCount

            for (i in 0 until minLineCount) {
                cppSource.removeFirst()
            }
            if (selectedDelim != null) {
                val firstLine = cppSource.removeFirst().substringAfter(selectedDelim)
                cppSource.addFirst(firstLine)
            }
            return part.toString() to selectedDelim
        }
    }
}

sealed class CppComments(val namespace: String, val className: String, val comment: String?) {
    abstract val isNotEmpty: Boolean
}

class CppClassComments(namespace: String, className: String, comment: String?)
    : CppComments(namespace, className, comment)
{
    // methods are stored in a map of name to list<comments> to support overloaded functions
    val methods = mutableMapOf<String, MutableList<CppMethodComment>>()
    val attributes = mutableMapOf<String, CppAttributeComment>()

    override val isNotEmpty: Boolean get() = !comment.isNullOrBlank() || methods.isNotEmpty() || attributes.isNotEmpty()
}

class CppEnumComments(namespace: String, className: String, comment: String?)
    : CppComments(namespace, className, comment)
{
    // methods are stored in a map of name to list<comments> to support overloaded functions
    val enumValues = mutableMapOf<String, CppEnumComment>()

    override val isNotEmpty: Boolean get() = !comment.isNullOrBlank() || enumValues.isNotEmpty()
}

data class CppMethodComment(
    val methodName: String,
    val returnType: String,
    val parameters: List<CppMethodParameter>,
    val comment: String?
)

data class CppAttributeComment(
    val attributeName: String,
    val type: String,
    val comment: String?
)

data class CppMethodParameter(
    val name: String,
    val type: String,
    val isOptional: Boolean
)

data class CppEnumComment(
    val valueName: String,
    val value: String,
    val comment: String?
)
