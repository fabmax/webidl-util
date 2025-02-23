package de.fabmax.webidl.model

sealed interface IdlType {

    val isVoid: Boolean
    val isString: Boolean
    val isVoidPtr: Boolean
    val isAny: Boolean
    val isAnyOrVoidPtr: Boolean
    val isPrimitive: Boolean
    val isComplexType: Boolean

    companion object {
        internal val basicTypes = setOf("boolean", "float", "double", "byte", "DOMString", "USVString", "octet",
            "short", "long", "long long", "unsigned short", "unsigned long", "unsigned long long", "void",
            "any", "VoidPtr")

        internal val parameterizedTypes = setOf("sequence", "record", "FrozenArray", "Promise")

        private val interfaceNameRegex = Regex("[a-zA-Z_]\\w*")

        fun isValidTypeName(typeName: String): Boolean {
            val nonArrayType = if (typeName.endsWith("[]")) {
                typeName.substring(0, typeName.length - 2).trim()
            } else {
                typeName
            }
            return nonArrayType in basicTypes ||
                    parameterizedTypes.any { nonArrayType.startsWith(it) } ||
                    interfaceNameRegex.matches(nonArrayType)
        }

        fun startsWithType(line: String): Boolean {
            val line = if (line.startsWith("(")) line.substringAfter("(") else line
            if (basicTypes.any { line.startsWith(it) }) {
                return true
            }
            if (parameterizedTypes.any { line.startsWith(it) }) {
                return true
            }
            return 0 == interfaceNameRegex.find(line)?.range?.start
        }
    }
}