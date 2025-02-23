package de.fabmax.webidl.model

class IdlNamespace(builder: Builder) : IdlDecoratedElement(builder) {
    val constantes = List(builder.constantes.size) { builder.constantes[it].build() }
    val sourcePackage = builder.sourcePackage

    fun finishModel(parentModel: IdlModel) {
        this.parentModel = parentModel
    }

    override fun toString(indent: String): String {
        val subIndent = "$indent    "
        val str = StringBuilder()
        str.append(decoratorsToStringOrEmpty(indent, "\n"))
        str.append("${indent}namespace $name { ")
        if (constantes.isNotEmpty()) {
            str.append("\n")
            str.append(constantes.joinToString("\n", postfix = "\n", transform = { it.toString(subIndent) }))
        }

        str.append("$indent};")
        return str.toString()
    }

    class Builder(name: String) : IdlDecoratedElement.Builder(name) {
        val constantes = mutableListOf<IdlConstant.Builder>()
        var sourcePackage = ""

        fun build() = IdlNamespace(this)

        fun addConstant(builder: IdlConstant.Builder) {
            constantes += builder
        }
    }
}