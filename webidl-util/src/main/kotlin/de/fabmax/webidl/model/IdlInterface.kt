package de.fabmax.webidl.model

class IdlInterface(builder: Builder) : IdlDecoratedElement(builder) {
    val attributes = List(builder.attributes.size) { builder.attributes[it].build() }
    val functions: List<IdlFunction>
    val functionsByName: Map<String, IdlFunction>
    val superInterfaces = builder.superInterfaces.toList()
    val sourcePackage = builder.sourcePackage
    val isMixin = builder.isMixin

    init {
        functions = builder.functions.flatMap { it.build() }
        functionsByName = functions.associateBy { it.name }
    }

    fun finishModel(parentModel: IdlModel) {
        this.parentModel = parentModel
        attributes.forEach { it.finishModel(this) }
        functions.forEach { it.finishModel(this) }
    }

    override fun toString(indent: String): String {
        val subIndent = "$indent    "
        val str = StringBuilder()
        str.append(decoratorsToStringOrEmpty(indent, "\n"))
        str.append("${indent}interface $name { ")
        if (functions.isNotEmpty()) {
            str.append("\n")
            str.append(functions.joinToString("\n", postfix = "\n", transform = { it.toString(subIndent) }))
        }
        if (attributes.isNotEmpty()) {
            str.append("\n")
            str.append(attributes.joinToString("\n", postfix = "\n", transform = { it.toString(subIndent) }))
        }
        str.append("$indent};")
        superInterfaces.forEach { str.append("\n$indent$name implements $it;") }
        return str.toString()
    }

    class Builder(name: String) : IdlDecoratedElement.Builder(name) {
        val attributes = mutableListOf<IdlAttribute.Builder>()
        val functions = mutableListOf<IdlFunction.Builder>()
        val superInterfaces = mutableSetOf<String>()
        var sourcePackage = ""
        var isMixin = false

        fun addAttribute(attribute: IdlAttribute.Builder) {
            attributes += attribute
        }

        fun addFunction(function: IdlFunction.Builder) {
            functions += function
        }

        fun build() = IdlInterface(this)
    }
}