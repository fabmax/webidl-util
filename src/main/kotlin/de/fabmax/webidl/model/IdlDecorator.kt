package de.fabmax.webidl.model

data class IdlDecorator(val key: String, val value: String?) {
    override fun toString(): String {
        return if (value != null) "$key=$value" else key
    }
}