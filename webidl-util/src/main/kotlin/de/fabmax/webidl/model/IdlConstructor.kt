package de.fabmax.webidl.model

class IdlConstructor private constructor(builder: Builder) : IdlDecoratedElement(builder) {
    val parameters = List(builder.parameters.size) { builder.parameters[it].build() }

    var parentInterface: IdlInterface? = null
        private set

    fun finishModel(parentInterface: IdlInterface) {
        this.parentModel = parentInterface.parentModel
        this.parentInterface = parentInterface
        parameters.forEach { it.finishModel(this) }
    }

    override fun toString(indent: String): String {
        val str = StringBuilder(indent)
        str.append(decoratorsToStringOrEmpty(postfix = " "))
        str.append(name)
        str.append("(").append(parameters.joinToString(", ")).append(");")
        return str.toString()
    }

    class Builder(name: String) : IdlDecoratedElement.Builder(name) {
        var explodeOptionalFunctionParams = true

        val parameters = mutableListOf<IdlFunctionParameter.Builder>()

        fun addParameter(parameter: IdlFunctionParameter.Builder) { parameters += parameter }

        fun build(): List<IdlConstructor> {
            val funcs = mutableListOf<IdlConstructor>()

            if (explodeOptionalFunctionParams) {
                // check for optional parameters an return multiple functions correspondingly
                val optionalCnt = parameters.filter { it.isOptional }.count()
                val nonOptionalParams = parameters.filter { !it.isOptional }
                val optionalParams = parameters.filter { it.isOptional }

                for (nOptional in 0..optionalCnt) {
                    val subBuilder = Builder(name)
                    subBuilder.explodeOptionalFunctionParams = false
                    subBuilder.decorators += decorators
                    subBuilder.parameters += nonOptionalParams
                    for (iOptional in 0 until nOptional) {
                        subBuilder.parameters += optionalParams[iOptional]
                    }
                    funcs += IdlConstructor(subBuilder)
                }

            } else {
                // return built function as is
                funcs += IdlConstructor(this)
            }

            return funcs
        }
    }
}