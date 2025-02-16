package de.fabmax.webidl.generator.jni.nat

import de.fabmax.webidl.model.*

internal fun IdlInterface.getNativeType(model: IdlModel): NativeType {
    return NativeType(model, IdlType(name, false), isValue = false, isRef = false, isConst = false)
}

internal fun IdlFunction.getNativeType(model: IdlModel): NativeType {
    return NativeType(model, returnType, hasDecorator(IdlDecorator.VALUE), hasDecorator(IdlDecorator.REF), hasDecorator(IdlDecorator.CONST))
}

internal fun IdlFunctionParameter.getNativeType(model: IdlModel): NativeType {
    return NativeType(model, type, hasDecorator(IdlDecorator.VALUE), hasDecorator(IdlDecorator.REF), hasDecorator(IdlDecorator.CONST))
}

internal fun IdlAttribute.getNativeType(model: IdlModel): NativeType {
    return NativeType(model, type, hasDecorator(IdlDecorator.VALUE), hasDecorator(IdlDecorator.REF), hasDecorator(IdlDecorator.CONST))
}

internal class NativeType(model: IdlModel, val idlType: IdlType, val isValue: Boolean, val isRef: Boolean, val isConst: Boolean) {
    val prefix = idlType.getPrefix(model)
    val isPrimitive = idlType.isPrimitive()

    val jniTypeName: String = if (isPrimitive) {
        idlPrimitiveTypeMapJni[idlType.typeName]!!
    } else {
        "$prefix${idlType.typeName}"
    }

    val isEnum = idlType.isEnum(model)

    private fun IdlType.isPrimitive(): Boolean {
        return typeName in idlPrimitiveTypeMapJni.keys
    }

    private fun IdlType.getPrefix(model: IdlModel): String {
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
            isPrimitive -> jniTypeName
            idlType.isString -> "jstring"
            idlType.typeName == "VoidPtr" -> "jlong"
            idlType.typeName == "any" -> "jlong"
            // enums are integer constants
            isEnum -> "jint"
            // all non-primitive types are passed in as a jlong containing a pointer
            else -> "jlong"
        }
    }

    fun nativeType(): String {
        val typePrefix = if (isConst) "const " else ""
        val typeSuffix = when {
            isRef -> "&"
            isValue -> ""
            else -> "*"
        }
        return when {
            isPrimitive -> idlPrimitiveTypeMapNative[idlType.typeName]!!
            idlType.isString -> "const char*"
            idlType.typeName == "VoidPtr" -> "void*"
            idlType.typeName == "any" -> "void*"
            // enums are integer constants
            isEnum -> idlType.typeName
            // non primitive pointer/ reference / value
            else -> "${typePrefix}${prefix}${idlType.typeName}$typeSuffix"
        }
    }

    fun castJniToNative(value: String): String {
        return when {
            // nothing to cast, types should match
            isPrimitive -> value
            // value is an int which is casted into the corresponding enum
            isEnum -> "(${idlType.typeName}) $value"

            // fixme: this is a memory leak: chars allocated by GetStringUTFChars() will never be released, but we
            //  don't know when it's safe to release them, so for now let's hope it won't be a big problem
            idlType.isString -> "${JniNativeGenerator.NativeFuncRenderer.ENV}->GetStringUTFChars($value, 0)"

            idlType.typeName == "VoidPtr" -> "(void*) $value"
            idlType.typeName == "any" -> "(void*) $value"

            // objects are given as a pointer inside a jlong
            isValue || isRef -> "*(($jniTypeName*) $value)"

            else -> "($jniTypeName*) $value"
        }
    }

    fun castNativeToJni(value: String): String {
        return when {
            // explicitly cast to type to avoid warnings on signed / unsigned integer conversions
            isPrimitive -> "(${idlPrimitiveTypeMapJni[idlType.typeName]}) $value"
            // value is an enum which is casted into an int
            isEnum -> "(jint) $value"

            idlType.isString -> "${JniNativeGenerator.NativeFuncRenderer.ENV}->NewStringUTF($value)"

            // objects are returned as a pointer insider a jlong
            isValue || isRef -> "(jlong) &$value"

            else -> "(jlong) $value"
        }
    }

    companion object {
        val idlPrimitiveTypeMapJni = mapOf(
            "boolean" to "jboolean",
            "float" to "jfloat",
            "double" to "jdouble",
            "byte" to "jbyte",
            "octet" to "jbyte",
            "short" to "jshort",
            "long" to "jint",
            "long long" to "jlong",
            "unsigned short" to "jshort",
            "unsigned long" to "jint",
            "unsigned long long" to "jlong",
            "void" to "void"
        )

        val idlPrimitiveTypeMapNative = mapOf(
            "boolean" to "bool",
            "float" to "float",
            "double" to "double",
            "byte" to "char",
            "octet" to "unsigned char",
            "short" to "short",
            "long" to "int",
            "long long" to "long long",
            "unsigned short" to "unsigned short",
            "unsigned long" to "unsigned int",
            "unsigned long long" to "unsigned long long",
            "void" to "void"
        )
    }
}

internal object JavaTypeSignature {

    fun getJniNativeFunctionName(func: IdlFunction, packagePrefix: String, platform: String): String {
        val parentIf = func.parentInterface ?: throw IllegalStateException("parentInterface of function ${func.name} not set")
        val parentModel = parentIf.parentModel ?: throw IllegalStateException("parentModel of interface ${parentIf.name} not set")

        var name = packagePrefix
        if (name.isNotEmpty()) { name += "." }
        name += parentIf.sourcePackage
        if (name.isNotEmpty() && !name.endsWith(".")) { name += "." }
        name += "${parentIf.name}._${func.name}"
        name = name
            .replace("_", "_1")
            .replace(".", "_")

        val isCtor = func.name == parentIf.name
        val isOverloaded = parentIf.functions.count { it.matchesPlatform(platform) && it.name == func.name } > 1
        val nameSuffix = if (isOverloaded) {
            "__" + getFunctionTypeSuffix(func, !func.isStatic && !isCtor, parentModel)
        } else {
            ""
        }

        return "Java_$name$nameSuffix"
    }

    fun getFunctionTypeSuffix(func: IdlFunction, receivesAddress: Boolean, model: IdlModel): String {
        var signature = ""
        if (receivesAddress) { signature += "J" } // long address
        return signature + func.parameters.joinToString("") {
            getTypeSignature(it.type, model, true)
        }
    }

    fun getJavaFunctionSignature(func: IdlFunction, model: IdlModel = func.parentModel!!): String {
        val paramsSig = func.parameters.joinToString(""){ getTypeSignature(it.type, model, false) }
        val returnSig = getTypeSignature(func.returnType, model, false)
        return "($paramsSig)$returnSig"
    }

    private fun getTypeSignature(type: IdlType, model: IdlModel, isFunctionName: Boolean): String {
        return if (type.isEnum(model)) {
            "I"
        } else {
            when(type.typeName) {
                "boolean" -> "Z"
                "float" -> "F"
                "double" -> "D"
                "byte" -> "B"
                "DOMString" -> if (isFunctionName) "Ljava_lang_String_2" else "Ljava/lang/String;"
                "USVString" -> if (isFunctionName) "Ljava_lang_String_2" else "Ljava/lang/String;"
                "octet" -> "B"
                "short" -> "S"
                "long" -> "I"
                "long long" -> "J"
                "unsigned short" -> "S"
                "unsigned long" -> "I"
                "unsigned long long" -> "J"
                "void" -> "V"
                "any" -> "J"
                "VoidPtr" -> "J"
                else -> "J"
            }
        }
    }
}
