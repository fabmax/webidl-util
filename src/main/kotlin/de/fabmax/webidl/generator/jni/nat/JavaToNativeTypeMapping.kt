package de.fabmax.webidl.generator.jni.nat

import de.fabmax.webidl.model.*

internal fun IdlInterface.getNativeType(model: IdlModel): NativeType {
    return NativeType(model, IdlType(name, false), isValue = false, isRef = false, isConst = false)
}

internal fun IdlFunction.getNativeType(model: IdlModel): NativeType {
    return NativeType(model, returnType, hasDecorator("Value"), hasDecorator("Ref"), hasDecorator("Const"))
}

internal fun IdlFunctionParameter.getNativeType(model: IdlModel): NativeType {
    return NativeType(model, type, hasDecorator("Value"), hasDecorator("Ref"), hasDecorator("Const"))
}

internal fun IdlAttribute.getNativeType(model: IdlModel): NativeType {
    return NativeType(model, type, hasDecorator("Value"), hasDecorator("Ref"), hasDecorator("Const"))
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
            idlType.typeName == "DOMString" -> "jstring"
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
            idlType.typeName == "DOMString" -> "const char*"
            idlType.typeName == "VoidPtr" -> "void*"
            idlType.typeName == "any" -> "void*"
            // enums are integer constants
            isEnum -> idlType.typeName
            // non primitive pointer/ reference / value
            else -> "$typePrefix${idlType.typeName}$typeSuffix"
        }
    }

    fun castJniToNative(inVal: String): String {
        return when {
            // nothing to cast, types should match
            isPrimitive -> inVal
            // inVal is an int which is casted into the corresponding enum
            isEnum -> "(${idlType.typeName}) $inVal"

            // fixme: this is a memory leak: chars allocated by GetStringUTFChars() will never be released, but we
            //  don't know when it's safe to release them, so for now let's hope it won't be a big problem
            idlType.isString -> "${JniNativeGenerator.NativeFuncRenderer.ENV}->GetStringUTFChars($inVal, 0)"

            idlType.typeName == "VoidPtr" -> "(void*) $inVal"
            idlType.typeName == "any" -> "(void*) $inVal"

            // objects are given as a pointer insider a jlong
            isValue || isRef -> "*(($jniTypeName*) $inVal)"

            else -> "($jniTypeName*) $inVal"
        }
    }

    fun castNativeToJni(outVal: String): String {
        return when {
            // explicitly cast to type to avoid warnings on size_t to int conversions
            isPrimitive -> "(${idlPrimitiveTypeMapJni[idlType.typeName]}) $outVal"
            // inVal should be an int which is casted into the corresponding enum
            isEnum -> "($jniTypeName) $outVal"

            idlType.typeName == "DOMString" -> "${JniNativeGenerator.NativeFuncRenderer.ENV}->NewStringUTF($outVal)"

            // objects are returned as a pointer insider a jlong
            isValue || isRef -> "(jlong) &$outVal"

            else -> "(jlong) $outVal"
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
    fun getFunctionTypeSuffix(func: IdlFunction, receivesAddress: Boolean, model: IdlModel): String {
        var signature = ""
        if (receivesAddress) { signature += "J" } // long address
        return signature + func.parameters.joinToString("") {
            getTypeSignature(it.type, model, true)
        }
    }

    fun getJavaFunctionSignature(func: IdlFunction, model: IdlModel): String {
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
