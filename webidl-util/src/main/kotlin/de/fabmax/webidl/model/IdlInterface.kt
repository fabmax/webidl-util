package de.fabmax.webidl.model

import de.fabmax.webidl.parser.ParserException

class IdlInterface(builder: Builder) : IdlDecoratedElement(builder) {
    val attributes = List(builder.attributes.size) { builder.attributes[it].build() }
    val functions: List<IdlFunction> = builder.functions.flatMap { it.build() }
    val constructors: List<IdlConstructor> = builder.constructors.flatMap { it.build() }
    val functionsByName: Map<String, IdlFunction> = functions.associateBy { it.name }
    val superInterfaces = builder.superInterfaces.toList()
    val sourcePackage = builder.sourcePackage
    val isMixin = builder.isMixin
    val isPartial = builder.isPartial
    val setLike = builder.setLike?.build()

    init {
        if (setLike != null) {
            if (functions.isNotEmpty()) throw ParserException("functions and setlike are mutually exclusive")
            if (attributes.isNotEmpty()) throw ParserException("attributes and setlike are mutually exclusive")
            if (constructors.isNotEmpty()) throw ParserException("constructors and setlike are mutually exclusive")
        }
    }

    fun finishModel(parentModel: IdlModel) {
        this.parentModel = parentModel
        attributes.forEach { it.finishModel(this) }
        functions.forEach { it.finishModel(this) }
        constructors.forEach { it.finishModel(this) }
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
        val constructors = mutableListOf<IdlConstructor.Builder>()
        val superInterfaces = mutableSetOf<String>()
        var sourcePackage = ""
        var isMixin = false
        var isPartial = false
        var setLike: IdlSetLike.Builder? = null

        fun addAttribute(attribute: IdlAttribute.Builder) {
            attributes += attribute
        }

        fun addFunction(function: IdlFunction.Builder) {
            functions += function
        }

        fun addConstructor(constructor: IdlConstructor.Builder) {
            constructors += constructor
        }

        fun build() = IdlInterface(this)
    }
}