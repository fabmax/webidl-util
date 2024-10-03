package de.fabmax.webidl.model

class IdlEnum private constructor(builder: Builder) : IdlDecoratedElement(builder) {
    val values = builder.values
    val sourcePackage = builder.sourcePackage

    val unprefixedValues: List<String>

    init {
        unprefixedValues = mutableListOf<String>().apply {
            values.forEach {
                this += if (it.contains("::")) {
                    it.substring(it.indexOf("::") + 2)
                } else {
                    it
                }
            }
        }
    }

    fun finishModel(parentModel: IdlModel) {
        this.parentModel = parentModel
    }

    override fun toString(indent: String): String {
        val str = StringBuilder()
        str.append(decoratorsToStringOrEmpty(indent, "\n"))
        str.append("${indent}enum $name {\n")
        str.append(values.joinToString(",\n$indent    ", "$indent    ", "\n"))
        str.append("$indent};")
        return str.toString()
    }

    class Builder(name: String) : IdlDecoratedElement.Builder(name) {
        val values = mutableListOf<String>()
        var sourcePackage = ""

        fun addValue(value: String) { values += value }
        fun build() = IdlEnum(this)
    }
}