package de.fabmax.webidl.model

class IdlConstant private constructor(builder: Builder) : IdlDecoratedElement(builder) {
    val type = builder.type
    var defaultValue: String = builder.defaultValue

    override fun toString(indent: String): String {
        val str = StringBuilder(indent)
        str.append("const $type $name = $defaultValue;")
        return str.toString()
    }

    class Builder(name: String, var type: IdlType) : IdlDecoratedElement.Builder(name) {
        lateinit var defaultValue: String

        fun build() = IdlConstant(this)
    }

}