package de.fabmax.webidl.model

class IdlAttribute private constructor(builder: Builder) : IdlDecoratedElement(builder) {
    val type = builder.type
    val isStatic = builder.isStatic
    val isReadonly = builder.isReadonly

    var parentInterface: IdlInterface? = null
        private set

    fun finishModel(parentInterface: IdlInterface) {
        this.parentModel = parentInterface.parentModel
        this.parentInterface = parentInterface
    }

    override fun toString(indent: String): String {
        val str = StringBuilder(indent)
        str.append(decoratorsToStringOrEmpty(postfix = " "))
        if (isStatic) {
            str.append("static ")
        }
        if (isReadonly) {
            str.append("readonly ")
        }
        str.append("attribute ").append(type).append(" ").append(name).append(";")
        return str.toString()
    }

    class Builder(name: String, var type: IdlType) : IdlDecoratedElement.Builder(name) {
        var isStatic = false
        var isReadonly = false

        fun build() = IdlAttribute(this)
    }
}