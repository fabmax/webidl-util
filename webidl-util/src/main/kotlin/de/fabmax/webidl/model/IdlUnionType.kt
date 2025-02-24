package de.fabmax.webidl.model

import de.fabmax.webidl.model.IdlType.Companion.basicTypes
import de.fabmax.webidl.model.IdlType.Companion.isValidTypeName

data class IdlUnionType(val types: List<IdlSimpleType>) : IdlType {

    override val isVoid = false
    override val isString = false
    override val isVoidPtr =false
    override val isAny = false

    override val isAnyOrVoidPtr = false
    override val isPrimitive = false
    override val isComplexType = false



    override fun toString(): String {
        return "(${types.joinTo(buffer = StringBuilder(), separator = " or ") { it.toString() }})"
    }
}