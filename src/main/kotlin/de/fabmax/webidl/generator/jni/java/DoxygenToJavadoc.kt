package de.fabmax.webidl.generator.jni.java

import de.fabmax.webidl.model.IdlFunction
import de.fabmax.webidl.model.IdlInterface
import de.fabmax.webidl.model.IdlModel
import kotlin.math.max

object DoxygenToJavadoc {

    var model: IdlModel? = null
    var packagePrefix = ""

    fun makeJavadocString(
        doxygen: String,
        idlClass: IdlInterface?,
        idlFunction: IdlFunction? = null
    ): String {
        val javadocStr = StringBuilder("/**\n")

        var wasBlank = false
        var removeBlock = false
        for (l in doxygen.lines()) {
            if (removeBlock && l.startsWith(" ")) {
                continue
            } else {
                removeBlock = false
            }

            if (l.contains("\\return") && idlFunction?.returnType?.isVoid != false) {
                idlFunction?.let {
                    println("warn: comment has return but function not: ${it.parentInterface?.name}.${it.name}")
                }
            }

            var doc = l.replace('\t', ' ')
                .makeHtmlSafe()
                .replace("\\brief ", "")
                .replace("\\note ", "<b>Note:</b> ")
                .replace("\\note", "<b>Note:</b>")
                .replace("\\return ", "@return ")

            if (doc.contains("@see ") || doc.contains("\\see ")) {
                doc = processSee(doc, idlClass)
            }

            if (doc.contains("\\param")) {
                processParam(doc, idlFunction).let { (valid, p) ->
                    removeBlock = !valid
                    doc = p
                }
                if (removeBlock) {
                    continue
                }
            }

            if (wasBlank && doc.isBlank()) {
                // avoid multiple adjacent blank lines
                continue
            }

            if (wasBlank && doc.isNotBlank() && !doc.startsWith("@")) {
                javadocStr.append(" * <p>\n")
            }
            wasBlank = doc.isBlank()
            if (!wasBlank) {
                doc.lines().forEach {
                    javadocStr.append(" * $it\n")
                }
            }
        }
        return javadocStr.append(" */").toString()
    }

    private fun String.makeHtmlSafe(): String {
        // escape a few common existing valid html tags
        val escaped = replace("<b>", "%[b]") .replace("</b>", "%[/b]")
            .replace("<i>", "%[i]").replace("</i>", "%[/i]")
            .replace("<h1>", "%[h1]").replace("</h1>", "%[/h1]")
            .replace("<h2>", "%[h2]").replace("</h2>", "%[/h2]")
            .replace("<h3>", "%[h3]").replace("</h3>", "%[/h3]")

        // replace html chars
        val htmlSafe = escaped.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

        // unescape tags
        return htmlSafe.replace("%[b]", "<b>").replace("%[/b]", "</b>")
            .replace("%[i]", "<i>").replace("%[/i]", "</i>")
            .replace("%[h1]", "<h1>").replace("%[/h1]", "</h1>")
            .replace("%[h2]", "<h2>").replace("%[/h2]", "</h2>")
            .replace("%[h3]", "<h3>").replace("%[/h3]", "</h3>")
    }

    private fun processParam(paramLine: String, idlFunction: IdlFunction?): Pair<Boolean, String> {
        if (idlFunction == null) {
            return false to ""
        }
        if (isValidParamName(paramLine, idlFunction)) {
            val param = paramLine.replace("\\param[in] ", "@param ")
                .replace("\\param[out] ", "@param ")
            return true to param
        }
        return false to ""
    }

    private fun processSee(seeLine: String, idlClass: IdlInterface?): String {
        if (model == null || idlClass == null) {
            // don't use @see javadoc tag, because that results in lots of errors for invalid references
            return seeLine.replace("@see ", "<b>See also:</b> ")
                .replace("\\see ", "<b>See also:</b> ")
        }
        val seeStart = max(seeLine.indexOf("@see "), seeLine.indexOf("\\see "))
        if (seeStart < 0) {
            return  ""
        }

        val seeValues = seeLine.substring(seeStart + 5).trim().split(" ").filter { it.isNotBlank() }
        val lines = mutableListOf<String>()
        for (value in seeValues) {
            if (model!!.interfacesByName.contains(value)) {
                // check if see value is a class name
                model!!.interfacesByName[value]?.let {
                    lines += "@see ${qualifiedClassName(it, idlClass)}"
                }

            } else {
                makeDocRef(value, idlClass, model!!)?.let { lines += it }
            }
        }
        return lines.joinToString("\n")
    }

    private fun makeDocRef(name: String, idlClass: IdlInterface, model: IdlModel): String? {
        val stripped = if (name.endsWith("()")) name.substring(0 until name.length - 2) else name

        // check if see value is a method name
        if (stripped.contains('.')) {
            val className = stripped.substring(0 until stripped.indexOf('.'))
            val methodName = stripped.substring(stripped.indexOf('.') + 1)
            val clazz = model.interfacesByName[className]
            if (clazz != null && clazz.functionsByName.contains(methodName)) {
                return "@see ${qualifiedClassName(clazz, idlClass)}#${methodName}"
            }
        } else {
            if (idlClass.functionsByName.containsKey(stripped)) {
                return "@see #${stripped}"
            }
        }
        return null
    }

    private fun qualifiedClassName(targetClass: IdlInterface, fromInterface: IdlInterface): String {
        return if (targetClass.sourcePackage == fromInterface.sourcePackage) {
            targetClass.name
        } else {
            "${if (packagePrefix.isNotEmpty()) "${packagePrefix}." else ""}${targetClass.sourcePackage}.${targetClass.name}"
        }
    }

    private fun isValidParamName(paramLine: String, function: IdlFunction): Boolean {
        val paramName = getParamName(paramLine)
        return function.parameters.any { it.name == paramName }
    }

    private fun getParamName(paramLine: String): String {
        val paramIdx = paramLine.indexOf("\\param")
        if (paramIdx < 0) return ""

        var s = paramLine.substring(paramIdx)
        val nameIndex = s.indexOfFirst { it.isWhitespace() } + 1
        if (nameIndex !in s.indices) return ""
        s = s.substring(nameIndex).trim()

        val nameEndIndex = s.indexOfFirst { it.isWhitespace() }
        if (nameEndIndex < 0) return s
        return s.substring(0 until nameEndIndex)
    }

}