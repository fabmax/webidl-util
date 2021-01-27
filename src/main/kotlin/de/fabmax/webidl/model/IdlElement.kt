package de.fabmax.webidl.model

abstract class IdlElement protected constructor(builder: Builder) {
    val name = builder.name

    override fun toString() = toString("")
    open fun toString(indent: String) = "$indent$name"

    abstract class Builder(var name: String)
}