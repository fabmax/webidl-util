package de.fabmax.webidl.generator.jni.java

import de.fabmax.webidl.generator.prependIndent
import de.fabmax.webidl.model.IdlEnum
import de.fabmax.webidl.parser.CppEnumComments
import java.io.Writer

internal class JavaEnumClass(idlElement: IdlEnum, idlPkg: String, packagePrefix: String) :
    JavaType(idlElement, idlPkg, packagePrefix)
{
    var comments: CppEnumComments? = null

    fun generateClassStart(w: Writer) {
        idlElement as IdlEnum

        generateTypeComment(w, comments)
        w.write("$visibility ")
        if (modifier.isNotEmpty()) {
            w.write("$modifier ")
        }
        w.write("enum $name {\n\n")

        // enum constants
        for (i in idlElement.unprefixedValues.indices) {
            val enumVal = idlElement.unprefixedValues[i]
            comments?.enumValues?.get(enumVal)?.comment?.let {
                //enumsWithComments++
                val comment = DoxygenToJavadoc.makeJavadocString(it, null, null)
                w.write(comment.prependIndent(4))
                w.write("\n")
                if (comment.contains("@deprecated")) {
                    w.write("    @Deprecated\n")
                }
            }
            val sep = if (i == idlElement.unprefixedValues.lastIndex) ";" else ","
            w.write("    $enumVal(_get$enumVal())${sep}\n")
        }

        // static lib loader
        if (staticCode.isNotEmpty()) {
            w.write("\n    static {\n")
            staticCode.lines().forEach {
                w.write("        ${it.trim()}\n")
            }
            w.write("    }\n")
        }

        // constructor
        w.write("""
            public final int value;
            
            $name(int value) {
                this.value = value;
            }
        """.trimIndent().prependIndent(4))

        w.write("\n\n")
        // native value getters
        idlElement.unprefixedValues.forEach { enumVal ->
            w.write("    private static native int _get$enumVal();\n")
        }

        w.append("""
                public static $name forValue(int value) {
                    for (int i = 0; i < values().length; i++) {
                        if (values()[i].value == value) {
                            return values()[i];
                        }
                    }
                    throw new IllegalArgumentException("Unknown value for enum ${name}: " + value);
                }
            """.trimIndent().prependIndent(4)).append("\n\n")
    }

    fun generateSource(w: Writer) {
        w.use {
            generatePackage(w)
            generateClassStart(w)
            w.append("}\n")
        }
    }
}