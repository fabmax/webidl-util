package de.fabmax.webidl.model

import java.util.*

class IdlModel private constructor(builder: Builder) : IdlElement(builder) {
    val interfaces: List<IdlInterface>
    val dictionaries: List<IdlDictionary>
    val namespaces: List<IdlNamespace>
    val enums: List<IdlEnum>
    val typeDefs: List<IdlTypeDef>

    val interfacesByName: Map<String, IdlInterface>

    init {
        interfaces = List(builder.interfaces.size) { builder.interfaces[it].build() }
        dictionaries = List(builder.dictionaries.size) { builder.dictionaries[it].build() }
        namespaces = List(builder.namespaces.size) { builder.namespaces[it].build() }
        typeDefs = List(builder.typeDefs.size) { builder.typeDefs[it].build() }
        interfacesByName = interfaces.associateBy { it.name }
        enums = List(builder.enums.size) { builder.enums[it].build() }

        dictionaries.forEach { it.finishModel(this) }
        interfaces.forEach { it.finishModel(this) }
        enums.forEach { it.finishModel(this) }
    }

    fun collectPackages(): SortedSet<String> {
        val packages = sortedSetOf<String>()
        packages += interfaces.map { it.sourcePackage }.distinct()
        packages += enums.map { it.sourcePackage }.distinct()
        return packages
    }

    fun getInterfacesByPackage(sourcePackage: String): List<IdlInterface> {
        return interfaces.filter { it.sourcePackage == sourcePackage }
    }

    fun getEnumsByPackage(sourcePackage: String): List<IdlEnum> {
        return enums.filter { it.sourcePackage == sourcePackage }
    }

    fun validate() {
        interfaces.forEach { intrf ->
            intrf.functions.forEach { func ->
                if (intrf.functions.any { f2 -> f2 !== func && f2.name == func.name && f2.parameters.size == func.parameters.size }) {
                    println("WARN: overloaded function with same parameter count: ${intrf.name}.${func.name} (works for JNI bindings but bot for javascript)")
                }
            }
        }
    }

    override fun toString(indent: String): String {
        val str = StringBuilder()

        for (pkg in collectPackages()) {
            if (pkg.isNotEmpty()) {
                str.append("$indent// [package=$pkg]\n\n")
            }
            interfaces.filter { it.sourcePackage == pkg }.forEach { str.append(it.toString(indent)).append("\n\n") }
            enums.filter { it.sourcePackage == pkg }.forEach { str.append(it.toString(indent)).append("\n\n") }
        }
        return str.toString()
    }

    class Builder : IdlElement.Builder("root") {
        val interfaces = mutableListOf<IdlInterface.Builder>()
        val dictionaries = mutableListOf<IdlDictionary.Builder>()
        val implements = mutableListOf<Pair<String, String>>()
        val includes = mutableListOf<Pair<String, String>>()
        val enums = mutableListOf<IdlEnum.Builder>()
        val typeDefs = mutableListOf<IdlTypeDef.Builder>()
        val namespaces = mutableListOf<IdlNamespace.Builder>()

        fun addInterface(idlInterface: IdlInterface.Builder) { interfaces += idlInterface }
        fun addDictionary(idlDictionary: IdlDictionary.Builder) { dictionaries += idlDictionary }
        fun addImplements(concreteInterface: String, superInterface: String) { implements += concreteInterface to superInterface }
        fun addIncludes(concreteInterface: String, superInterface: String) { includes += concreteInterface to superInterface }
        fun addEnum(idlEnum: IdlEnum.Builder) { enums += idlEnum }
        fun addTypeDef(idlTypeDef: IdlTypeDef.Builder) { typeDefs += idlTypeDef }
        fun addNamespace(idlNamespace: IdlNamespace.Builder) { namespaces += idlNamespace }

        fun build(): IdlModel {
            implements.forEach { (ci, si) ->
                val i = interfaces.find { it.name == ci } ?: throw NoSuchElementException("interface \"$ci\" not found for implements statement: $ci implements $si;")
                val superInterface = interfaces.find { it.name == si } ?: throw NoSuchElementException("interface \"$si\" not found for implements statement: $ci implements $si;")
                if (superInterface.isMixin) error("implements statement is only allowed for non-mixin interfaces: $ci implements $si;")
                i.superInterfaces += si
            }
            includes.forEach { (ci, si) ->
                val i = interfaces.find { it.name == ci } ?: throw NoSuchElementException("interface \"$ci\" not found for includes statement: $ci includes $si;")
                val superInterface = interfaces.find { it.name == si } ?: throw NoSuchElementException("interface \"$si\" not found for includes statement: $ci includes $si;")
                if (superInterface.isMixin.not()) error("includes statement is only allowed for mixin interfaces: $ci includes $si;")
                i.superInterfaces += si
            }
            return IdlModel(this)
        }
    }
}