package de.fabmax.webidl.parser

import java.io.File

object CppCommentParser {

    private val cachedComments = mutableMapOf<File, List<CppClassComments>?>()

    fun parseComments(file: File): List<CppClassComments> {
        return if (file.isDirectory) {
            file.walk()
                .filter { it.name.endsWith(".h", true) || it.name.endsWith(".cpp", true) }
                .flatMap { parseCommentsFile(it) ?: emptyList() }
                .toList()
        } else {
            parseCommentsFile(file) ?: emptyList()
        }
    }

    private fun parseCommentsFile(cppFile: File): List<CppClassComments>? {
        if (cppFile in cachedComments) {
            return cachedComments[cppFile]
        }

        val comments = try {
            val parser = CppTokenParser()
            cppFile.readLines().forEach { line -> parser.nextLine(line) }
            parser.commentedClasses.filter { it.isNotEmpty }
        } catch (e: Exception) {
            System.err.println("Failed parsing C++ source \"$cppFile\"")
            e.printStackTrace()
            null
        }
        cachedComments[cppFile] = comments
        return comments
    }

    private class CppTokenParser {
        var namespace = ""
        var currentClassName = ""
        var currentComment: StringBuilder? = null
        var currentElement: CppElement? = null
        var prevToken = ""

        var currentClass: CppClassComments? = null
        var classBlockDepth = -1

        var blockDepth = 0

        val isInComment: Boolean get() = currentElement == CppElement.DocComment
        val isInClass: Boolean get() = currentClass != null

        val commentedClasses = mutableListOf<CppClassComments>()

        fun nextLine(line: String) {
            if (isInComment) {
                val endIdx = line.indexOf(CppElement.DocCommentEnd.token)
                if (endIdx >= 0) {
                    currentComment!!.append(line.substring(0 until endIdx))
                    currentElement = null
                } else {
                    currentComment!!.append(line).append('\n')
                }
            } else {
                val lineCommentIdx = line.indexOf("//")
                val truncLine = if (lineCommentIdx >= 0) line.substring(0 until lineCommentIdx) else line

                truncLine.splitToSequence(' ', '\t')
                    .filter { it.isNotBlank() }
                    .forEach { tok ->
                        if (!isInComment) {
                            nextNonCommentToken(tok)
                            prevToken = tok
                        } else if (tok.contains(CppElement.DocCommentEnd.token)) {
                            currentElement = null
                        }
                    }

                if (!isInComment && line.isNotBlank()) {
                    // there was a line with some content, which any past comment was meant for, now it's consumed...
                    // this assumes sane source code formatting
                    currentComment = null
                }
            }
        }

        fun nextNonCommentToken(token: String) {
            when (currentElement) {
                CppElement.Namespace -> {
                    namespace = token
                    currentElement = null
                }
                CppElement.Class -> onClassToken(token)
                CppElement.Struct -> onClassToken(token)
                else -> currentElement = null
            }

            currentElement = CppElement.values().find { it.token == token }
            if (currentElement == CppElement.DocComment) {
                // start of comment block
                currentComment = StringBuilder()

            } else {
                val increaseDepth = token.count { it == '{' }
                val decreaseDepth = token.count { it == '}' }
                blockDepth += increaseDepth
                blockDepth -= decreaseDepth

                if (decreaseDepth > 0 && blockDepth <= classBlockDepth) {
                    currentClass = null
                    classBlockDepth = -1
                    //println("exit class")
                }

                if (isInClass && token.contains('(') && currentComment != null) {
                    var funcName = token.substring(0 until token.indexOf('('))
                    if (funcName.isBlank()) {
                        funcName = prevToken
                    }
                    currentClass?.let {
                        val commentString = currentComment?.toString()?.trimIndent() ?: ""
                        if (commentString.isNotBlank()) {
                            it.functionsComments[funcName] = CppMemberComment(funcName, commentString)
                        }
                    }
                    //println("  function: $token, has comment: ${currentComment != null}")
                }
            }
        }

        fun onClassToken(className: String) {
            currentClassName = className.filter { it.isLetterOrDigit() || it == '_' }
            currentElement = null

            val cppClass = CppClassComments(currentClassName, currentComment?.toString()?.trimIndent() ?: "")
            classBlockDepth = blockDepth
            currentClass = cppClass
            commentedClasses += cppClass

            //println("enter class $className, $classBlockDepth")
        }
    }

    class CppClassComments(val name: String, val comment: String) {
        val functionsComments = mutableMapOf<String, CppMemberComment>()

        val isNotEmpty: Boolean get() = !comment.isBlank() || functionsComments.isNotEmpty()
    }

    class CppMemberComment(val name: String, val comment: String)

    enum class CppElement(val token: String) {
        Namespace("namespace"),
        Class("class"),
        Struct("struct"),
        DocComment("/**"),
        DocCommentEnd("*/")
    }
}