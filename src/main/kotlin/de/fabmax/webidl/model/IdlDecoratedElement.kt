package de.fabmax.webidl.model

abstract class IdlDecoratedElement protected constructor(builder: Builder) : IdlElement(builder) {
    val decorators = builder.decorators.toList()

    fun decoratorsToStringOrEmpty(prefix: String = "", postfix: String = ""): String {
        return if (decorators.isNotEmpty()) {
            "$prefix[${decorators.joinToString(", ")}]$postfix"
        } else  {
            ""
        }
    }

    fun hasDecorator(key: String) = decorators.any { it.key == key }

    fun getDecoratorValue(key: String, default: String) = decorators.find { it.key == key }?.value ?: default

    override fun toString(indent: String) = decoratorsToStringOrEmpty(indent)

    abstract class Builder(name: String) : IdlElement.Builder(name) {
        val decorators = mutableSetOf<IdlDecorator>()
        fun addDecorator(decorator: IdlDecorator) { decorators += decorator }
    }
}