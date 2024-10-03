package de.fabmax.webidl.model

class IdlFunctionParameter private constructor(builder: Builder) : IdlDecoratedElement(builder) {
    val type = builder.type
    val isOptional = builder.isOptional

    var parentFunction: IdlFunction? = null
        private set

    fun finishModel(parentFunction: IdlFunction) {
        parentModel = parentFunction.parentModel
        this.parentFunction = parentFunction
    }

    override fun toString(indent: String): String {
        val opt = if (isOptional) "optional " else ""
        return "$indent$opt${decoratorsToStringOrEmpty(postfix = " ")}$type $name"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as IdlFunctionParameter

        if (name != other.name) return false
        if (type != other.type) return false
        if (isOptional != other.isOptional) return false

        return true
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + isOptional.hashCode()
        return result
    }

    class Builder(name: String, var type: IdlType) : IdlDecoratedElement.Builder(name) {
        var isOptional = false

        fun build() = IdlFunctionParameter(this)
    }
}