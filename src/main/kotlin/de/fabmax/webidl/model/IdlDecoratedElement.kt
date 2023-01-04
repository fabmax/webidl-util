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

    fun matchesPlatform(platformName: String): Boolean {
        if (platformName.isBlank()) {
            return true
        }
        val platforms = getDecoratorValue(IdlDecorator.PLATFORMS, "").split(",").filter { it.isNotBlank() }
        return platforms.isEmpty() || platformName in platforms
    }

    override fun toString(indent: String) = decoratorsToStringOrEmpty(indent)

    abstract class Builder(name: String) : IdlElement.Builder(name) {
        val decorators = mutableSetOf<IdlDecorator>()
        fun addDecorator(decorator: IdlDecorator) { decorators += decorator }
    }
}