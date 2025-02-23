package de.fabmax.webidl.model

class IdlNamespace(builder: Builder) : IdlDecoratedElement(builder) {
    val constants = List(builder.constants.size) { builder.constants[it].build() }

    override fun toString(indent: String): String {
        val subIndent = "$indent    "
        val str = StringBuilder()
        str.append(decoratorsToStringOrEmpty(indent, "\n"))
        str.append("${indent}namespace $name { ")
        if (constants.isNotEmpty()) {
            str.append("\n")
            str.append(constants.joinToString("\n", postfix = "\n", transform = { it.toString(subIndent) }))
        }

        str.append("$indent};")
        return str.toString()
    }

    class Builder(name: String) : IdlDecoratedElement.Builder(name) {
        val constants = mutableListOf<IdlConstant.Builder>()

        fun build() = IdlNamespace(this)

        fun addConstant(builder: IdlConstant.Builder) {
            constants += builder
        }
    }
}