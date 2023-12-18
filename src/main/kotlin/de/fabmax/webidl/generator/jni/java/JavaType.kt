package de.fabmax.webidl.generator.jni.java

import de.fabmax.webidl.model.IdlDecoratedElement
import de.fabmax.webidl.model.IdlDecorator
import de.fabmax.webidl.model.IdlInterface
import de.fabmax.webidl.parser.CppComments
import java.io.File
import java.io.Writer

internal abstract class JavaType(val idlElement: IdlDecoratedElement, idlPkg: String, packagePrefix: String) {
    val name: String = idlElement.name
    val javaPkg: String
    val fqn: String
    val fileName = "$name.java"

    // only use idlPkg instead of prefixed / full java package for path construction
    // this way the output directory can point into a package within a project and does not need to target the
    // top-level source directory of a project
    val path = if (idlPkg.isEmpty()) fileName else File(idlPkg.replace('.', '/'), fileName).path

    var visibility = "public"
    var modifier = ""
    var staticCode = ""

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

    fun generateTypeComment(w: Writer, comments: CppComments?) {
        if (comments?.comment?.isNotBlank() == true) {
            val comment = DoxygenToJavadoc.makeJavadocString(comments.comment!!, idlElement as? IdlInterface, null)
            w.write(comment)
            w.write("\n")
            if (comment.contains("@deprecated") || idlElement.hasDecorator(IdlDecorator.DEPRECATED)) {
                w.write("@Deprecated\n")
            }
        } else {
            if (idlElement.hasDecorator(IdlDecorator.DEPRECATED)) {
                w.write("@Deprecated\n")
            }
        }
    }
}