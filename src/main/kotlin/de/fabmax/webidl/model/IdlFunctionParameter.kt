package de.fabmax.webidl.model

class IdlFunctionParameter private constructor(builder: Builder) : IdlDecoratedElement(builder) {
    val type = builder.type
    val isOptional = builder.isOptional

    override fun toString(indent: String): String {
        val opt = if (isOptional) "optional " else ""
        return "$indent$opt${decoratorsToStringOrEmpty(postfix = " ")}$type $name"
    }

    class Builder(name: String, var type: IdlType) : IdlDecoratedElement.Builder(name) {
        var isOptional = false

        fun build() = IdlFunctionParameter(this)
    }
}