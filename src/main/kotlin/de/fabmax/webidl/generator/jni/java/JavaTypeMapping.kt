package de.fabmax.webidl.generator.jni.java

import de.fabmax.webidl.model.IdlType

internal class JavaTypeMapping(val idlType: IdlType, val isIdlEnum: Boolean) {
    val internalType: String
    val javaType: String

    private val requiresMarshalling: Boolean
        get() = internalType != javaType

    init {
        when {
            idlType.isPrimitive -> {
                internalType = JniJavaGenerator.idlPrimitiveTypeMap[idlType.typeName] ?: throw IllegalStateException("Unknown idl type: ${idlType.typeName}")
                javaType = internalType
            }
            idlType.isAnyOrVoidPtr -> {
                internalType = "long"
                javaType = JniJavaGenerator.NATIVE_OBJECT_NAME
            }
            isIdlEnum -> {
                internalType = "int"
                javaType = idlType.typeName
            }
            else -> {
                internalType = "long"
                javaType = idlType.typeName
            }
        }
    }

    fun boxedReturn(value: String): String {
        return when {
            idlType.isVoid -> value
            isIdlEnum -> "return $javaType.forValue($value)"
            requiresMarshalling -> "return $javaType.wrapPointer($value)"
            else -> "return $value"
        }
    }

    fun unbox(value: String, isNullable: Boolean = false): String {
        return when {
            isIdlEnum -> "$value.value"
            isNullable && requiresMarshalling -> "($value != null ? $value.getAddress() : 0L)"
            requiresMarshalling -> "$value.getAddress()"
            else -> value
        }
    }
}