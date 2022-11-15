package de.fabmax.webidl.generator.jni.java

import de.fabmax.webidl.generator.prependIndent
import de.fabmax.webidl.model.*
import de.fabmax.webidl.parser.CppAttributeComment
import de.fabmax.webidl.parser.CppClassComments
import de.fabmax.webidl.parser.CppComments
import de.fabmax.webidl.parser.CppMethodComment
import java.io.File
import java.io.Writer
import java.util.*
import kotlin.math.abs

internal class JavaClass(val idlElement: IdlDecoratedElement, idlPkg: String, packagePrefix: String) {
    val name: String = idlElement.name
    val isEnum: Boolean = idlElement is IdlEnum
    val javaPkg: String
    val fqn: String
    val fileName = "$name.java"

    // only use idlPkg instead of prefixed / full java package for path construction
    // this way the output directory can point into a package within a project and does not need to target the
    // top-level source directory of a project
    val path = if (idlPkg.isEmpty()) fileName else File(idlPkg.replace('.', '/'), fileName).path

    var visibility = "public"
    var modifier = ""
    var protectedDefaultContructor = true
    var generatePointerWrapMethods = true
    var staticCode = ""

    var superClass: JavaClass? = null
    val imports = mutableListOf<JavaClass>()
    val importFqns = TreeSet<String>()

    var comments: CppComments? = null

    init {
        javaPkg = when {
            packagePrefix.isEmpty() -> idlPkg
            idlPkg.isEmpty() -> packagePrefix
            else -> "$packagePrefix.$idlPkg"
        }
        fqn = if (javaPkg.isEmpty()) name else "$javaPkg.$name"
    }

    fun getMethodComment(method: IdlFunction): CppMethodComment? {
        val comments = this.comments as? CppClassComments ?: return null
        val funcs = comments.methods[method.name]?.filter { it.comment != null } ?: return null

        val bestFunc = funcs.find { it.matchesParameters(method) }
        return if (bestFunc == null && funcs.isNotEmpty()) {
//            println("$name.${method.name}: warn no method with matching param names found, choosing any")
//            println("    idl parameters: ${method.parameters.map { "${it.name}: ${it.type.typeName}" }}")
//            println("    cpp candidates:")
//            funcs.forEach { f -> println("                    ${f.parameters.map { "${it.name}: ${it.type}" }}") }

            funcs.minBy { abs(it.parameters.size - method.parameters.size) }
        } else {
            bestFunc
        }
    }

    private fun CppMethodComment.matchesParameters(idlMethod: IdlFunction): Boolean {
        if (parameters.size < idlMethod.parameters.size) {
            // cpp method signature has too few parameters
            return false
        }
        if (parameters.count { !it.isOptional } > idlMethod.parameters.size) {
            // cpp method has more non-optional params than idl method
            return false
        }
        return parameters.zip(idlMethod.parameters).all { (cpp, idl) ->
            cpp.name == idl.name || cpp.type.contains(idl.type.typeName)
        }
    }

    fun getAttributeComment(attribute: IdlAttribute): CppAttributeComment? {
        val comments = this.comments as? CppClassComments ?: return null
        return comments.attributes[attribute.name]
    }

    fun generatePackage(w: Writer) {
        if (javaPkg.isNotEmpty()) {
            w.write("package $javaPkg;\n\n")
        }
    }

    fun generateImports(w: Writer) {
        imports.filter { javaPkg != it.javaPkg }.forEach { import ->
            importFqns += import.fqn
        }
        importFqns.forEach { fqn ->
            w.write("import ${fqn};\n")
        }
        w.write("\n")
    }

    fun generateClassStart(w: Writer) {
        if (comments?.comment?.isNotBlank() == true) {
            val comment = DoxygenToJavadoc.makeJavadocString(comments!!.comment!!, idlElement as? IdlInterface, null)
            w.write(comment)
            w.write("\n")
            if (comment.contains("@deprecated")) {
                w.write("@Deprecated\n")
            }
        }

        w.write("$visibility ")
        if (modifier.isNotEmpty()) {
            w.write("$modifier ")
        }
        w.write("class $name ")
        superClass?.let { w.write("extends ${it.name} ") }
        w.write("{\n\n")

        if (staticCode.isNotEmpty()) {
            w.write("    static {\n")
            staticCode.lines().forEach {
                w.write("        ${it.trim()}\n")
            }
            w.write("    }\n\n")
        }

        if (protectedDefaultContructor) {
            w.append("""
                protected $name() { }
            """.trimIndent().prependIndent(4)).append("\n\n")
        }

        if (generatePointerWrapMethods) {
            w.append("""
                public static $name wrapPointer(long address) {
                    return address != 0L ? new $name(address) : null;
                }
                
                protected $name(long address) {
                    super(address);
                }
            """.trimIndent().prependIndent(4)).append("\n\n")
        }
    }

    fun generateSource(w: Writer, body: Writer.() -> Unit) {
        w.use {
            generatePackage(w)
            generateImports(w)
            generateClassStart(w)
            body(w)
            w.append("}\n")
        }
    }
}