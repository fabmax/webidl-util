package de.fabmax.webidl.model

data class IdlDecorator(val key: String, val value: String?) {
    override fun toString(): String {
        return if (value != null) "$key=$value" else key
    }

    companion object {
        const val CONST = "Const"
        const val JS_IMPLEMENTATION = "JSImplementation"
        const val NO_DELETE = "NoDelete"
        const val PREFIX = "Prefix"
        const val REF = "Ref"
        const val VALUE = "Value"

        // non-standard decorators
        const val NULLABLE = "Nullable"
        const val STACK_ALLOCATABLE = "StackAllocatable"
        const val PLATFORMS = "Platforms"
        const val DEPRECATED = "Deprecated"
    }
}