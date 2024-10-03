package de.fabmax.webidl.generator.jni.java

import de.fabmax.webidl.generator.prependIndent
import de.fabmax.webidl.model.IdlAttribute
import de.fabmax.webidl.model.IdlFunction
import de.fabmax.webidl.model.IdlInterface
import de.fabmax.webidl.parser.CppAttributeComment
import de.fabmax.webidl.parser.CppClassComments
import de.fabmax.webidl.parser.CppMethodComment
import java.io.Writer
import java.util.*
import kotlin.math.abs

internal class JavaClass(idlElement: IdlInterface, idlPkg: String, packagePrefix: String) :
    JavaType(idlElement, idlPkg, packagePrefix)
{
    var protectedDefaultContructor = true
    var generatePointerWrapMethods = true

    var superClass: JavaClass? = null
    val imports = mutableListOf<JavaType>()
    val importFqns = TreeSet<String>()

    var comments: CppClassComments? = null

    fun getMethodComment(method: IdlFunction): CppMethodComment? {
        val comments = this.comments ?: return null
        val funcs = comments.methods[method.name]?.filter { it.comment != null } ?: return null

        val bestFunc = funcs.find { it.matchesParameters(method) }
        return if (bestFunc == null && funcs.isNotEmpty()) {
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
        val comments = this.comments ?: return null
        return comments.attributes[attribute.name]
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
        generateTypeComment(w, comments)

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
                private static native int __sizeOf();
                public static final int SIZEOF = __sizeOf();
                public static final int ALIGNOF = 8;
                
                public static $name wrapPointer(long address) {
                    return address != 0L ? new $name(address) : null;
                }
                
                public static $name arrayGet(long baseAddress, int index) {
                    if (baseAddress == 0L) throw new NullPointerException("baseAddress is 0");
                    return wrapPointer(baseAddress + (long) SIZEOF * index);
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