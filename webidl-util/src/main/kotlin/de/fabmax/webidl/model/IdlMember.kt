package de.fabmax.webidl.model

class IdlMember private constructor(builder: Builder) : IdlDecoratedElement(builder) {
    val type = builder.type
    val isRequired = builder.isRequired
    var defaultValue: String? = builder.defaultValue

    var parentDictionary: IdlDictionary? = null
        private set

    fun finishModel(parentDictionary: IdlDictionary?) {
        this.parentDictionary = parentDictionary
        this.parentModel = parentDictionary?.parentModel ?: error("Parent model missing")
    }

    override fun toString(indent: String): String {
        val str = StringBuilder(indent)
        str.append(decoratorsToStringOrEmpty(postfix = " "))
        if (isRequired) {
            str.append("required ")
        }
        str.append(type).append(" ").append(name)
        if (defaultValue != null) { str.append(" = $defaultValue")}
        str.append(";")
        return str.toString()
    }

    class Builder(name: String, var type: IdlType) : IdlDecoratedElement.Builder(name) {
        var isRequired = false
        var defaultValue: String? = null

        fun build() = IdlMember(this)
    }

}