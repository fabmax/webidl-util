package de.fabmax.webidl.generator.jni.java

import de.fabmax.webidl.generator.prependIndent
import de.fabmax.webidl.model.IdlDecoratedElement
import de.fabmax.webidl.model.IdlEnum
import de.fabmax.webidl.model.IdlInterface
import de.fabmax.webidl.parser.CppCommentParser
import java.io.File
import java.io.Writer
import java.util.*

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

    var comments: CppCommentParser.CppClassComments? = null

    init {
        javaPkg = when {
            packagePrefix.isEmpty() -> idlPkg
            idlPkg.isEmpty() -> packagePrefix
            else -> "$packagePrefix.$idlPkg"
        }
        fqn = if (javaPkg.isEmpty()) name else "$javaPkg.$name"
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
            val comment = DoxygenToJavadoc.makeJavadocString(comments!!.comment, idlElement as? IdlInterface, null)
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