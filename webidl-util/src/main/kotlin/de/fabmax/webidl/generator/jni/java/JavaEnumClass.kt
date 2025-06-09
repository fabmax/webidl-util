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
            w.write("    $enumVal(get$enumVal())${sep}\n")
        }

        // constructor
        w.write("""
            public final int value;
            
            $name(int value) {
                this.value = value;
            }
        """.trimIndent().prependIndent(4))

        // native value getters, including library loader / initializer call
        w.write("\n\n")
        idlElement.unprefixedValues.forEach { enumVal ->
            w.write("""
                private static native int _get$enumVal();
                private static int get$enumVal() {
            """.trimIndent().prependIndent(4))
            if (staticCode.isNotEmpty()) {
                w.write("\n")
                staticCode.lines().forEach {
                    w.write("        ${it.trim()}\n")
                }
            }
            w.write("        return _get$enumVal();\n    }\n\n")
        }

        w.append("""
            private static final $name[] enumLookup;
            private static final boolean isIndexedLookup;
            
            static {
                var minNativeVal = Integer.MAX_VALUE;
                var maxNativeVal = 0;
                var enumValues = values();
                for (int i = 0; i < enumValues.length; i++) {
                    minNativeVal = Math.min(minNativeVal, enumValues[i].value);
                    maxNativeVal = Math.max(maxNativeVal, enumValues[i].value);
                }
                if (minNativeVal >= 0 && maxNativeVal < 256) {
                    isIndexedLookup = true;
                    enumLookup = new $name[maxNativeVal + 1];
                    for (int i = 0; i < enumValues.length; i++) {
                        enumLookup[enumValues[i].value] = enumValues[i];
                    }
                } else {
                    isIndexedLookup = false;
                    enumLookup = enumValues;
                }
            }
            
            public static $name forValue(int value) {
                if (isIndexedLookup) {
                    var enumValue = enumLookup[value];
                    if (enumValue == null) {
                        throw new IllegalArgumentException("Unknown value for enum ${name}: " + value);
                    }
                    return enumValue;
                } else {
                    for (int i = 0; i < enumLookup.length; i++) {
                        if (enumLookup[i].value == value) {
                            return enumLookup[i];
                        }
                    }
                    throw new IllegalArgumentException("Unknown value for enum ${name}: " + value);
                }
            }
        """.trimIndent().prependIndent(4)).append("\n")
    }

    fun generateSource(w: Writer) {
        w.use {
            generatePackage(w)
            generateClassStart(w)
            w.write("}\n")
        }
    }
}