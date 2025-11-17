package de.fabmax.webidl.parser

import java.io.File

object CppCommentParser {

    private val cachedComments = mutableMapOf<File, List<CppComments>>()

    fun parseComments(file: File): List<CppComments> {
        return if (file.isDirectory) {
            file.walk()
                .filter { it.name.endsWith(".h", true) || it.name.endsWith(".hpp", true) }
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
        var latestCommentString: DocPart? = null

        val blockDelimiters = listOf("/**", "/*", "{", "}", ";", "///", "//")
        val commentDelimiters = listOf("*/")
        val lineCommentDelimiters = listOf("\n")
        val enumDelimiters = listOf(",", "}", "/**", "/*", "///", "//")
        val delimiters = mapOf(
            Mode.ROOT to blockDelimiters,
            Mode.NAMESPACE to blockDelimiters,
            Mode.CLASS to blockDelimiters,
            Mode.INNER_BLOCK to blockDelimiters,
            Mode.DOC_COMMENT to commentDelimiters,
            Mode.LINE_DOC_COMMENT to lineCommentDelimiters,
            Mode.LINE_COMMENT to lineCommentDelimiters,
            Mode.MULTI_LINE_COMMENT to commentDelimiters,
            Mode.ENUM to enumDelimiters,
        )

        val namespaceRegex = Regex("[\\s\\n]namespace[\\s\\n]")
        val classRegex = Regex("[\\s\\n]class[\\s\\n]")
        val structRegex = Regex("[\\s\\n]struct[\\s\\n]")
        val enumRegex = Regex("[\\s\\n]enum[\\s\\n]")
        val constRegex = Regex("[\\s\\n]const[\\s\\n]")

        val namespacePath = mutableListOf<String>()
        val nestedClassPath = mutableListOf<String>()

        val classes = mutableListOf<CppClassComments>()
        val currentClass: CppClassComments? get() = classes.lastOrNull()
        val enums = mutableListOf<CppEnumComments>()
        val currentEnum: CppEnumComments? get() = enums.lastOrNull()
        var lastCommentElement: CommentElement? = null

        init {
            do {
                val delims = delimiters[mode]!!
                val (part, delim) = tokenizer.readUntil(delims)

                when (modeStack.last()) {
                    Mode.ROOT -> processRoot(part, delim)
                    Mode.DOC_COMMENT -> processDocComment(part)
                    Mode.LINE_COMMENT -> processLineComment(part)
                    Mode.LINE_DOC_COMMENT -> processLineDocComment(part)
                    Mode.NAMESPACE -> processNamespace(part, delim)
                    Mode.CLASS -> processClass(part, delim)
                    Mode.ENUM -> processEnum(part, delim)
                    Mode.INNER_BLOCK -> processBlock(part, delim)
                    Mode.MULTI_LINE_COMMENT -> modeStack.removeLast()
                }
            } while (delim != null)
        }

        private fun consumeDocComment(): String? {
            val text = latestCommentString?.text
            latestCommentString = null
            return text
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
                "///" -> {
                    modeStack += Mode.LINE_DOC_COMMENT
                    true
                }
                "//" -> {
                    modeStack += Mode.LINE_COMMENT
                    true
                }
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
                    enterClass(part, "class", delim)
                    Mode.CLASS
                }
                part.contains(structRegex) -> {
                    enterClass(part, "struct", delim)
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

        private fun enterClass(part: String, classType: String, delim: String?) {
            val className = part.substringAfter(classType).substringBefore(":").trim()
            val superType = part.substringAfter(":", "")

            if (delim?.endsWith("{") == true) {
                val cppClass = CppClassComments(namespacePath.joinToString("::"), className, superType, consumeDocComment())
                classes += cppClass
                nestedClassPath += className
                lastCommentElement = cppClass
            }
        }

        private fun enterEnum(enumName: String) {
            val prefixedName = (currentClass?.className ?: "") + enumName
            val cppEnum = CppEnumComments(namespacePath.joinToString("::"), prefixedName, consumeDocComment() ?: currentClass?.comment)
            enums += cppEnum
            lastCommentElement = cppEnum
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
                val enumValue = CppEnumComment(valueName, value, consumeDocComment())
                currentEnum?.enumValues?.put(valueName, enumValue)
                lastCommentElement = enumValue
            }
            if (delim == "}") {
                popMode()
            }
            latestCommentString = null
        }

        fun processDocComment(part: String) {
            val commentLines = part.trimIndent().lines()
            if (commentLines.any { it.isNotBlank() }) {
                val text = commentLines
                    .dropWhile { it.isBlank() }
                    .dropLastWhile { it.isBlank() }
                    .joinToString("\n")
                latestCommentString = DocPart(text, DocType.Block)
            }
            popMode()
        }

        fun processLineDocComment(part: String) {
            val commentLine = part.trim().removePrefix("///").trim()
            if (commentLine.isNotBlank()) {
                val prev = latestCommentString
                val prevText = if (prev?.type == DocType.DocLine) "${prev.text}\n" else ""
                latestCommentString = DocPart("$prevText$commentLine", DocType.DocLine)
            }
            popMode()
        }

        fun processLineComment(part: String) {
            var trimmed = part.trim()
            if (trimmed.startsWith("!")) {
                // doc line comment
                trimmed = trimmed.substring(1).trim()
                if (trimmed.startsWith("<")) {
                    lastCommentElement?.let {
                        if (it.comment == null) {
                            it.comment = trimmed.substring(1).trim()
                        }
                    }
                } else {
                    latestCommentString = DocPart(trimmed, DocType.Line)
                }
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
                    val isConst = " $it".contains(constRegex)
                    val (pName, pType) = splitNameAndType(it.substringBefore("="))
                    CppMethodParameter(pName, pType, isConst, isOptional)
                }
            val funComments = currentClass?.methods?.getOrPut(funName) { mutableListOf() }
            val cppMethod = CppMethodComment(funName, retType, params, consumeDocComment())
            funComments?.add(cppMethod)
            lastCommentElement = cppMethod
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
            val cppAttrib = CppAttributeComment(attrName, type, consumeDocComment())
            currentClass?.attributes?.put(attrName, cppAttrib)
            lastCommentElement = cppAttrib
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
            LINE_DOC_COMMENT,
            LINE_COMMENT,
            NAMESPACE,
            CLASS,
            ENUM,
            INNER_BLOCK
        }
    }

    private class CppTokenizer(cppSourceLines: List<String>) {
        var position = 0
        val cppSource: String = cppSourceLines.joinToString("\n")

        fun readUntil(delimiters: List<String>): Pair<String, String?> {
            val (delim, pos) = delimiters
                .map { it to cppSource.indexOf(it, position) }
                .filter { it.second >= 0 }
                .minByOrNull { it.second } ?: (null to cppSource.length)

            val part = cppSource.substring(position until pos)
            position = pos + (delim?.length ?: 0)

            // pad read part with a single whitespace so that element regexs can identify elements right in
            // the beginning
            return " $part" to delim
        }
    }
}

interface CommentElement {
    var comment: String?
}

sealed class CppComments(val namespace: String, val className: String, override var comment: String?) : CommentElement {
    abstract val isNotEmpty: Boolean
}

class CppClassComments(namespace: String, className: String, val superType: String, comment: String?)
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
    override var comment: String?
) : CommentElement

data class CppAttributeComment(
    val attributeName: String,
    val type: String,
    override var comment: String?
) : CommentElement

data class CppMethodParameter(
    val name: String,
    val type: String,
    val isConst: Boolean,
    val isOptional: Boolean
)

data class CppEnumComment(
    val valueName: String,
    val value: String,
    override var comment: String?
) : CommentElement

data class DocPart(val text: String, val type: DocType)

enum class DocType {
    Block,
    DocLine,
    Line,
}
