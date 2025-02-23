package de.fabmax.webidl.model

class IdlTypeDef private constructor(builder: Builder) : IdlDecoratedElement(builder) {
    val type = builder.type ?: throw IllegalArgumentException("Missing type")

    override fun toString(indent: String): String {
        val str = StringBuilder(indent)
        str.append("typedef ${decoratorsToStringOrEmpty(postfix = " ")} $type $name;")
        return str.toString()
    }

    class Builder : IdlDecoratedElement.Builder("") {
        var type: IdlType? = null

        fun build() = IdlTypeDef(this)
    }

}