package de.fabmax.webidl.model

class IdlSetLike private constructor(builder: Builder) : IdlElement(builder) {
    val type = builder.type

    var parentInterface: IdlInterface? = null
        private set

    fun finishModel(parentInterface: IdlInterface) {
        this.parentModel = parentInterface.parentModel
        this.parentInterface = parentInterface
    }

    override fun toString(indent: String): String {
        val str = StringBuilder(indent)
        str.append("readonly setlike<$type>").append(";")
        return str.toString()
    }

    class Builder(var type: IdlType) : IdlElement.Builder("setlike") {

        fun build() = IdlSetLike(this)
    }

}