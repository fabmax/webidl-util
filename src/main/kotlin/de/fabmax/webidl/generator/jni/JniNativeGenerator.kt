package de.fabmax.webidl.generator.jni

import de.fabmax.webidl.generator.CodeGenerator
import de.fabmax.webidl.model.*
import java.io.File
import java.io.Writer

class JniNativeGenerator : CodeGenerator() {

    var packagePrefix = ""

    private lateinit var model: IdlModel

    init {
        outputDirectory = "./generated/native"
    }

    override fun generate(model: IdlModel) {
        this.model = model
        createOutFileWriter("glue.h").use {
            model.generateGlueCpp(it)
        }
    }

    private fun IdlModel.generateGlueCpp(w: Writer) {
        w.append("""
            /*
             * JNI glue code. You should not edit this file.
             * Generated from WebIDL model '$name' by webidl-util.
             */
            #include <jni.h>
            
            extern "C" {
        """.trimIndent()).append("\n")

        collectPackages().forEach { pkg ->
            getInterfacesByPackage(pkg).forEach { it.generate(w) }
            getEnumsByPackage(pkg).forEach { it.generate(w) }
        }
        w.write("\n} // /extern \"C\"\n")
    }

    private fun IdlEnum.generate(w: Writer) {
        w.write("\n// $name\n")
        values.zip(unprefixedValues).forEach { (pv, uv) ->
            w.append("""
                JNIEXPORT jint JNICALL ${nativeFunName(sourcePackage, name, "get$uv")}(JNIEnv*, jclass) {
                    return $pv;
                }
            """.trimIndent()).append("\n")
        }
    }

    private fun IdlInterface.generate(w: Writer) {
        w.write("\n// $name\n")
        functions.forEach { func ->
            val isOverloaded = functions.filter { it.name == func.name }.count() > 1
            if (func.isStatic) {
                generateStaticFunction(func, isOverloaded, w)
            } else {
                if (func.name == name) {
                    generateCtor(func, isOverloaded, w)
                } else {
                    generateFunction(func, isOverloaded, w)
                }
            }
        }
        if (!hasDecorator("NoDelete")) {
            generateDtor(w)
        }
        attributes.forEach {
            generateGet(it, w)
            if (!it.isReadonly) {
                generateSet(it, w)
            }
        }
    }

    private fun IdlInterface.generateStaticFunction(func: IdlFunction, isOverloaded: Boolean, w: Writer) {
        val ifPrefix = getDecoratorValue("Prefix", "")
        val natType = func.getNativeType()
        val funcName = "${func.name}${func.getFunctionSuffix(isOverloaded)}"
        val suffix = func.getFunctionSuffix(isOverloaded)
        val args = generateFuncArgs(func)
        val funcArgs = func.parameters.joinToString(", ") { it.getNativeType().castJniToNative(it.name) }

        w.append("JNIEXPORT ${natType.jniType()} JNICALL ${nativeFunName(sourcePackage, name, funcName, suffix)}(JNIEnv* env, jclass$args) {\n")
        w.append("    (void) env;    // avoid unused parameter warning / error\n")
        if (natType.isValue) {
            // we can't pass value objects via JNI, use a static variable to cache the value and return a pointer to that one
            w.append("    static ${natType.typeName} cache;\n")
        }
        if (func.returnType.typeName == "void") {
            w.append("    $ifPrefix$name::${func.name}($funcArgs);\n")
        } else {
            var returnStr = "$ifPrefix$name::${func.name}($funcArgs)"
            if (natType.isValue) {
                w.append("    cache = $returnStr;\n")
                returnStr = "cache"
            }
            w.append("    return ${natType.castNativeToJni(returnStr)};\n")
        }
        w.append("}\n")
    }

    private fun IdlInterface.generateFunction(func: IdlFunction, isOverloaded: Boolean, w: Writer) {
        val ifPrefix = getDecoratorValue("Prefix", "")
        val natType = func.getNativeType()
        val suffix = func.getFunctionSuffix(isOverloaded)
        val args = generateFuncArgs(func)
        val funcArgs = func.parameters.joinToString(", ") { it.getNativeType().castJniToNative(it.name) }

        w.append("JNIEXPORT ${natType.jniType()} JNICALL ${nativeFunName(sourcePackage, name, func.name, suffix)}(JNIEnv* env, jobject, jlong address$args) {\n")
        w.append("    (void) env;    // avoid unused parameter warning / error\n")
        if (natType.isValue) {
            // we can't pass value objects via JNI, use a static variable to cache the value and return a pointer to that one
            w.append("    static ${natType.typeName} cache;\n")
        }
        w.append("    $ifPrefix$name* self = ($ifPrefix$name*) address;\n")
        if (func.returnType.typeName == "void") {
            w.append("    self->${func.name}($funcArgs);\n")
        } else {
            var returnStr = "self->${func.name}($funcArgs)"
            if (natType.isValue) {
                w.append("    cache = $returnStr;\n")
                returnStr = "cache"
            }
            w.append("    return ${natType.castNativeToJni(returnStr)};\n")
        }
        w.append("}\n")
    }

    private fun IdlInterface.generateCtor(func: IdlFunction, isOverloaded: Boolean, w: Writer) {
        val natType = getNativeType()
        val suffix = func.getFunctionSuffix(isOverloaded, true)
        val args = generateFuncArgs(func)
        val ctorArgs = func.parameters.joinToString(", ") { it.getNativeType().castJniToNative(it.name) }
        val ctorCall = "new ${natType.typeName}($ctorArgs)"

        w.append("""
            JNIEXPORT jlong JNICALL ${nativeFunName(sourcePackage, name, func.name, suffix)}(JNIEnv* env, jobject$args) {
                (void) env;    // avoid unused parameter warning / error
                return (jlong) $ctorCall;
            }
        """.trimIndent()).append("\n")
    }

    private fun generateFuncArgs(func: IdlFunction): String {
        var args = func.parameters.joinToString(", ") {
            it.getNativeType().jniType() + " ${it.name}"
        }
        if (args.isNotEmpty()) {
            args = ", $args"
        }
        return args
    }

    private fun IdlInterface.generateDtor(w: Writer) {
        val natType = getNativeType()
        w.append("""
            JNIEXPORT void JNICALL ${nativeFunName(sourcePackage, name, "delete_native_instance")}(JNIEnv*, jobject, jlong address) {
                delete ${natType.castJniToNative("address")};
            }
        """.trimIndent()).append("\n")
    }

    private fun IdlInterface.generateGet(attrib: IdlAttribute, w: Writer) {
        val ifPrefix = getDecoratorValue("Prefix", "")
        val natType = attrib.getNativeType()
        val methodName = nativeFunName(this, attrib, "get")
        val staticMod = if (attrib.isStatic) "jclass" else "jobject, jlong address"
        val arrayMod = if (attrib.type.isArray) ", jint index" else ""
        val arrayValueMod = if (attrib.type.isArray) "[index]" else ""
        val getSelf = if (attrib.isStatic) "" else "    $ifPrefix$name* self = ($ifPrefix$name*) address;\n"
        val returnStr = if (attrib.isStatic) "$ifPrefix$name::${attrib.name}" else "self->${attrib.name}"

        w.write("JNIEXPORT ${natType.jniType()} JNICALL $methodName(JNIEnv* env, $staticMod$arrayMod) {\n")
        w.write("    (void) env;    // avoid unused parameter warning / error\n")
        w.write(getSelf)
        w.write("    return ${natType.castNativeToJni("$returnStr$arrayValueMod")};\n")
        w.write("}\n")
    }

    private fun IdlInterface.generateSet(attrib: IdlAttribute, w: Writer) {
        val ifPrefix = getDecoratorValue("Prefix", "")
        val natType = attrib.getNativeType()
        val methodName = nativeFunName(this, attrib, "set")
        val staticMod = if (attrib.isStatic) "jclass" else "jobject, jlong address"
        val arrayMod = if (attrib.type.isArray) ", jint index" else ""
        val arrayValueMod = if (attrib.type.isArray) "[index]" else ""
        val getSelf = if (attrib.isStatic) "" else "    $ifPrefix$name* self = ($ifPrefix$name*) address;\n"
        val valueReceiveStr = if (attrib.isStatic) "$ifPrefix$name::${attrib.name}" else "self->${attrib.name}"

        w.write("JNIEXPORT void JNICALL $methodName(JNIEnv* env, $staticMod$arrayMod, ${natType.jniType()} value) {\n")
        w.write("    (void) env;    // avoid unused parameter warning / error\n")
        w.write(getSelf)
        w.write("    $valueReceiveStr$arrayValueMod = ${natType.castJniToNative("value")};\n")
        w.write("}\n")
    }

    private fun nativeFunName(idlIf: IdlInterface, attrib: IdlAttribute, funPrefix: String) =
        nativeFunName(idlIf.sourcePackage, idlIf.name, "$funPrefix${firstCharToUpper(attrib.name)}")

    private fun nativeFunName(srcPkg: String, className: String, functionName: String, nameSuffix: String = ""): String {
        var name = packagePrefix
        if (name.isNotEmpty()) { name += "." }
        name += srcPkg
        if (name.isNotEmpty() && !name.endsWith(".")) { name += "." }
        name += "$className._$functionName"
        name = name.replace("_", "_1")
        name = name.replace(".", "_")
        return "Java_$name$nameSuffix"
    }

    private fun IdlFunction.getFunctionSuffix(isOverloaded: Boolean, isCtor: Boolean = false): String {
        return if (!isOverloaded) {
            ""
        } else {
            var signature = "__"
            if (!isStatic && !isCtor) { signature += "J" } // long address
            signature += parameters.joinToString("") {
                val isPrimitive = it.type.typeName in idlTypeSignatureMap.keys
                val isEnum = model.enums.any { e -> e.name == it.type.typeName }
                when {
                    isPrimitive -> idlTypeSignatureMap[it.type.typeName]!!
                    isEnum -> "I"
                    else -> "J"
                }
            }
            signature
        }
    }

    fun IdlInterface.getNativeType(): NativeType {
        return NativeType(IdlType(name, false), isValue = false, isRef = false, isConst = false)
    }

    fun IdlFunction.getNativeType(): NativeType {
        return NativeType(returnType, hasDecorator("Value"), hasDecorator("Ref"), hasDecorator("Const"))
    }

    fun IdlFunctionParameter.getNativeType(): NativeType {
        return NativeType(type, hasDecorator("Value"), hasDecorator("Ref"), hasDecorator("Const"))
    }

    fun IdlAttribute.getNativeType(): NativeType {
        return NativeType(type, hasDecorator("Value"), hasDecorator("Ref"), hasDecorator("Const"))
    }

    inner class NativeType(val idlType: IdlType, val isValue: Boolean, val isRef: Boolean, val isConst: Boolean) {
        val prefix = idlType.getPrefix()
        val isEnum = model.enums.any { it.name == idlType.typeName }
        val isPrimitive = idlType.isPrimitive()

        val typeName: String = if (isPrimitive) {
            idlPrimitiveTypeMap[idlType.typeName]!!
        } else {
            "$prefix${idlType.typeName}"
        }

        private fun IdlType.isPrimitive(): Boolean {
            return typeName in idlPrimitiveTypeMap.keys
        }

        private fun IdlType.getPrefix(): String {
            return if (isPrimitive) {
                // primitive types have no prefix
                ""
            } else {
                // for now we look only for interfaces with the given name, enums are omitted because they can't
                // have [Prefix="..."] decorators (although it would make sense...)
                model.interfaces.find { it.name == typeName }?.getDecoratorValue("Prefix", "") ?: ""
            }
        }

        fun jniType(): String {
            return when {
                isPrimitive -> typeName
                idlType.typeName == "DOMString" -> "jstring"
                idlType.typeName == "VoidPtr" -> "jlong"
                idlType.typeName == "any" -> "jlong"
                // enums are integer constants
                isEnum -> "jint"
                // all non-primitive types are passed in as a jlong containing a pointer
                else -> "jlong"
            }
        }

        fun castJniToNative(inVal: String): String {
            return when {
                // nothing to cast, types should match
                isPrimitive -> inVal
                // inVal should be an int which is casted into the corresponding enum
                isEnum -> "($typeName) $inVal"

                // fixme: this is a memory leak: chars allocated by GetStringUTFChars() will never be released, but we
                //  don't know when it's safe to release them, so for now let's hope it won't be a big problem
                idlType.typeName == "DOMString" -> "env->GetStringUTFChars($inVal, 0)"

                idlType.typeName == "VoidPtr" -> "(void*) $inVal"
                idlType.typeName == "any" -> "(void*) $inVal"

                // objects are given as a pointer insider a jlong
                isValue || isRef -> "*(($typeName*) $inVal)"

                else -> "($typeName*) $inVal"
            }
        }

        fun castNativeToJni(outVal: String): String {
            return when {
                // explicitly cast to type to avoid warnings on size_t to int conversions
                isPrimitive -> "(${idlPrimitiveTypeMap[idlType.typeName]}) $outVal"
                // inVal should be an int which is casted into the corresponding enum
                isEnum -> "($typeName) $outVal"

                idlType.typeName == "DOMString" -> "env->NewStringUTF($outVal)"

                // objects are returned as a pointer insider a jlong
                isValue || isRef -> "(jlong) &$outVal"

                else -> "(jlong) $outVal"
            }
        }
    }

    companion object {
        val idlPrimitiveTypeMap = mapOf(
            "boolean" to "jboolean",
            "float" to "jfloat",
            "double" to "jdouble",
            "byte" to "jbyte",
            //"DOMString" to "jstring",
            "octet" to "jbyte",
            "short" to "jshort",
            "long" to "jint",
            "long long" to "jlong",
            "unsigned short" to "jshort",
            "unsigned long" to "jint",
            "unsigned long long" to "jlong",
            "void" to "void",
        )

        val idlTypeSignatureMap = mapOf(
            "boolean" to "Z",
            "float" to "F",
            "double" to "D",
            "byte" to "B",
            "DOMString" to "Ljava_lang_String_2",
            "octet" to "B",
            "short" to "S",
            "long" to "I",
            "long long" to "J",
            "unsigned short" to "S",
            "unsigned long" to "I",
            "unsigned long long" to "J",
            "void" to "V",
            "any" to "J",
            "VoidPtr" to "J"
        )
    }
}