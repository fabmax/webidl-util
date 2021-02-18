package de.fabmax.webidl.generator.jni.java

import de.fabmax.webidl.generator.prependIndent
import java.io.File
import java.io.Writer
import java.util.*

internal class JavaClass(val name: String, val isEnum: Boolean, idlPkg: String, packagePrefix: String) {
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

    init {
        javaPkg = when {
            packagePrefix.isEmpty() -> idlPkg
            idlPkg.isEmpty() -> packagePrefix
            else -> "$packagePrefix.$idlPkg"
        }
        fqn = if (javaPkg.isEmpty()) name else "$javaPkg.$name"
    }

    private fun generatePackage(w: Writer) {
        if (javaPkg.isNotEmpty()) {
            w.write("package $javaPkg;\n\n")
        }
    }

    private fun generateImports(w: Writer) {
        imports.filter { javaPkg != it.javaPkg }.forEach { import ->
            importFqns += import.fqn
        }
        importFqns.forEach { fqn ->
            w.write("import ${fqn};\n")
        }
        w.write("\n")
    }

    private fun generateClassStart(w: Writer) {
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