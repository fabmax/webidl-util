package de.fabmax.webidl.model

import de.fabmax.webidl.model.IdlType.Companion.basicTypes
import de.fabmax.webidl.model.IdlType.Companion.isValidTypeName

data class IdlSimpleType(val typeName: String, val isArray: Boolean, val parameterTypes: List<String>? = null) : IdlType {

    override val isVoid = typeName == "void"
    override val isString = typeName == "DOMString" || typeName == "USVString"
    override val isVoidPtr = typeName == "VoidPtr"
    override val isAny = typeName == "any"

    override val isAnyOrVoidPtr = isVoidPtr || isAny
    override val isPrimitive = typeName in basicTypes && !isAnyOrVoidPtr
    override val isComplexType = !isPrimitive && !isAnyOrVoidPtr

    fun isEnum(model: IdlModel) = model.enums.any { it.name == typeName }

    fun isValid(): Boolean {
        return isValidTypeName(typeName)
    }

    override fun toString(): String {
        val sb = StringBuilder(typeName)
        if (isArray) {
            sb.append("[]")
        }
        return sb.toString()
    }
}