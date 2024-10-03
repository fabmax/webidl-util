package de.fabmax.webidl.model

data class IdlType(val typeName: String, val isArray: Boolean) {

    val isVoid = typeName == "void"
    val isString = typeName == "DOMString"
    val isVoidPtr = typeName == "VoidPtr"
    val isAny = typeName == "any"

    val isAnyOrVoidPtr = isVoidPtr || isAny
    val isPrimitive = typeName in basicTypes && !isAnyOrVoidPtr
    val isComplexType = !isPrimitive && !isAnyOrVoidPtr

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

    companion object {
        private val basicTypes = setOf("boolean", "float", "double", "byte", "DOMString", "octet",
            "short", "long", "long long", "unsigned short", "unsigned long", "unsigned long long", "void",
            "any", "VoidPtr")

        private val interfaceNameRegex = Regex("[a-zA-Z_]\\w*")

        fun isValidTypeName(typeName: String): Boolean {
            val nonArrayType = if (typeName.endsWith("[]")){
                typeName.substring(0, typeName.length - 2).trim()
            } else{
                typeName
            }
            return nonArrayType in basicTypes || interfaceNameRegex.matches(nonArrayType)
        }

        fun startsWithType(line: String): Boolean {
            if (basicTypes.any { line.startsWith(it) }) {
                return true
            }
            return 0 == interfaceNameRegex.find(line)?.range?.start
        }
    }
}