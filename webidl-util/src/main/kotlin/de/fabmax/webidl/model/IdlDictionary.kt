package de.fabmax.webidl.model

class IdlDictionary(builder: Builder) : IdlDecoratedElement(builder) {
    val members = List(builder.members.size) { builder.members[it].build() }
    val superDictionaries = builder.superDictionaries.toList()
    val sourcePackage = builder.sourcePackage

    fun finishModel(parentModel: IdlModel) {
        this.parentModel = parentModel
        members.forEach { it.finishModel(this) }
    }

    override fun toString(indent: String): String {
        val subIndent = "$indent    "
        val str = StringBuilder()
        str.append(decoratorsToStringOrEmpty(indent, "\n"))
        str.append("${indent}dictionary $name { ")

        if (members.isNotEmpty()) {
            str.append("\n")
            str.append(members.joinToString("\n", postfix = "\n", transform = { it.toString(subIndent) }))
        }
        str.append("$indent};")
        superDictionaries.forEach { str.append("\n$indent$name implements $it;") }
        return str.toString()
    }

    class Builder(name: String) : IdlDecoratedElement.Builder(name) {
        val members = mutableListOf<IdlMember.Builder>()
        val superDictionaries = mutableSetOf<String>()
        var sourcePackage = ""

        fun addAttribute(attribute: IdlMember.Builder) { members += attribute }
        fun build() = IdlDictionary(this)
    }
}