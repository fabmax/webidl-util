package de.fabmax.webidl.model

import java.util.*
import kotlin.NoSuchElementException

class IdlModel private constructor(builder: Builder) : IdlElement(builder) {
    val interfaces: List<IdlInterface>
    val enums: List<IdlEnum>

    init {
        interfaces = List(builder.interfaces.size) { builder.interfaces[it].build() }
        enums = List(builder.enums.size) { builder.enums[it].build() }
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
        val implements = mutableListOf<Pair<String, String>>()
        val enums = mutableListOf<IdlEnum.Builder>()

        fun addInterface(idlInterface: IdlInterface.Builder) { interfaces += idlInterface }
        fun addImplements(concreteInterface: String, superInterface: String) { implements += concreteInterface to superInterface }
        fun addEnum(idlEnum: IdlEnum.Builder) { enums += idlEnum }

        fun build(): IdlModel {
            implements.forEach { (ci, si) ->
                val i = interfaces.find { it.name == ci } ?: throw NoSuchElementException("interface \"$ci\" not found for implements statement: $ci implements $si;")
                i.superInterfaces += si
            }
            return IdlModel(this)
        }
    }
}