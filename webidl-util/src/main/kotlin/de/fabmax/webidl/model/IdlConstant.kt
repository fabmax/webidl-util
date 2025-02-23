package de.fabmax.webidl.model

class IdlConstant private constructor(builder: Builder) : IdlDecoratedElement(builder) {
    val type = builder.type
    var defaultValue: String = builder.defaultValue

    var parentDictionary: IdlDictionary? = null
        private set

    fun finishModel(parentDictionary: IdlDictionary?) {
        this.parentDictionary = parentDictionary
        this.parentModel = parentDictionary?.parentModel ?: error("Parent model missing")
    }

    override fun toString(indent: String): String {
        val str = StringBuilder(indent)
        str.append("const ${type.typeName} $name = $defaultValue;")
        return str.toString()
    }

    class Builder(name: String, var type: IdlType) : IdlDecoratedElement.Builder(name) {
        var defaultValue: String = ""

        fun build() = IdlConstant(this)
    }

}