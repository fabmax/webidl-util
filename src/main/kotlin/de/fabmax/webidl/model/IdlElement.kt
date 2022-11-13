package de.fabmax.webidl.model

abstract class IdlElement protected constructor(builder: Builder) {
    val name = builder.name

    var parentModel: IdlModel? = null
        protected set

    override fun toString() = toString("")
    open fun toString(indent: String) = "$indent$name"

    abstract class Builder(var name: String)
}