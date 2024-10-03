package de.fabmax.webidl.model

class IdlFunction private constructor(builder: Builder) : IdlDecoratedElement(builder) {
    val returnType = builder.returnType
    val isStatic = builder.isStatic
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
        if (isStatic) {
            str.append("static ")
        }
        str.append(returnType).append(" ")
        str.append(name)
        str.append("(").append(parameters.joinToString(", ")).append(");")
        return str.toString()
    }

    class Builder(name: String, var returnType: IdlType) : IdlDecoratedElement.Builder(name) {
        var explodeOptionalFunctionParams = true

        var isStatic = false
        val parameters = mutableListOf<IdlFunctionParameter.Builder>()

        fun addParameter(parameter: IdlFunctionParameter.Builder) { parameters += parameter }

        fun build(): List<IdlFunction> {
            val funcs = mutableListOf<IdlFunction>()

            if (explodeOptionalFunctionParams) {
                // check for optional parameters an return multiple functions correspondingly
                val optionalCnt = parameters.filter { it.isOptional }.count()
                val nonOptionalParams = parameters.filter { !it.isOptional }
                val optionalParams = parameters.filter { it.isOptional }

                for (nOptional in 0..optionalCnt) {
                    val subBuilder = Builder(name, returnType)
                    subBuilder.explodeOptionalFunctionParams = false
                    subBuilder.decorators += decorators
                    subBuilder.isStatic = isStatic
                    subBuilder.parameters += nonOptionalParams
                    for (iOptional in 0 until nOptional) {
                        subBuilder.parameters += optionalParams[iOptional]
                    }
                    funcs += IdlFunction(subBuilder)
                }

            } else {
                // return built function as is
                funcs += IdlFunction(this)
            }

            return funcs
        }
    }
}