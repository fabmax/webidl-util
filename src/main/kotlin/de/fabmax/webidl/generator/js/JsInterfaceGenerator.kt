package de.fabmax.webidl.generator.js

import de.fabmax.webidl.generator.CodeGenerator
import de.fabmax.webidl.model.*
import java.io.File
import java.io.Writer

class JsInterfaceGenerator : CodeGenerator() {

    var packagePrefix = ""

    /**
     * Module name, used for loading the module with "require('moduleName')"
     */
    var moduleName = "Module"

    /**
     * Added as a comment in every generated file
     */
    var fileHeader = "Generated from WebIDL by webidl-util"

    /**
     * If true, all types (interface members and function parameters) are generated as nullable.
     */
    var allTypesNullable = false

    /**
     * List of interface attributes, which will be generated with nullable types. Expected format:
     * "InterfaceName.attributeName"
     */
    val nullableAttributes = mutableSetOf<String>()

    /**
     * List of functions, which will be generated with nullable return types. Expected format:
     * "InterfaceName.functionName"
     */
    val nullableReturnValues = mutableSetOf<String>()

    /**
     * List of function parameters, which will be generated with nullable types. Expected format:
     * "InterfaceName.functionName" to "parameterName"
     */
    val nullableParameters = mutableSetOf<Pair<String, String>>()

    private var loaderClassName = ""
    private var moduleMemberName = ""
    private val moduleLocation: String
        get() = "$loaderClassName.$moduleMemberName"

    init {
        outputDirectory = "./generated/ktJs"
    }

    override fun generate(model: IdlModel) {
        super.generate(model)

        generateLoader(model)

        for (pkg in model.collectPackages()) {
            val (ktPkg, file) = makeFileNameAndPackage(pkg, model)
            model.generatePackage(pkg, ktPkg, file.path)
        }
    }

    private fun makeFileNameAndPackage(idlPkg: String, model: IdlModel): Pair<String, File> {
        var ktPackage = ""
        val fileName = when {
            idlPkg.isEmpty() -> {
                model.name
            }
            !idlPkg.contains('.') -> {
                idlPkg
            }
            else -> {
                val split = idlPkg.lastIndexOf('.')
                val fName = idlPkg.substring(split + 1)
                ktPackage = idlPkg.substring(0, split)
                fName
            }
        }

        // do not consider package prefix for file path
        // this way the output directory can point into a package within a project and does not need to target the
        // top-level source directory of a project
        val path = File(ktPackage.replace('.', '/'), firstCharToUpper("$fileName.kt"))

        if (packagePrefix.isNotEmpty()) {
            ktPackage = if (ktPackage.isEmpty()) {
                packagePrefix
            } else {
                "$packagePrefix.$ktPackage"
            }
        }
        return ktPackage to path
    }

    private fun generateLoader(model: IdlModel) {
        val localClsName = firstCharToUpper("${model.name}Loader")
        moduleMemberName = firstCharToLower(model.name)
        //loaderClassName = "$packagePrefix.$localClsName"
        loaderClassName = localClsName
        createOutFileWriter("$localClsName.kt").use { w ->
            w.writeHeader()
            if (packagePrefix.isNotEmpty()) {
                w.write("\npackage $packagePrefix\n\n")
            }
            w.write("""                
                import kotlin.js.Promise

                object $localClsName {
                    @JsName("$moduleMemberName")
                    internal var $moduleMemberName: dynamic = null
                    @Suppress("UnsafeCastFromDynamic")
                    private val ${moduleMemberName}Promise: Promise<dynamic> = js("require('$moduleName')")()

                    private var isLoading = false
                    private var isLoaded = false
                
                    private val onLoadListeners = mutableListOf<() -> Unit>()

                    fun loadModule() {
                        if (!isLoading) {
                            isLoading = true
                            physxJsPromise.then { module: dynamic ->
                                physxJs = module
                                isLoaded = true
                                onLoadListeners.forEach { it() }
                            }
                        }
                    }
                    
                    fun addOnLoadListener(listener: () -> Unit) {
                        if (isLoaded) {
                            listener()
                        } else {
                            onLoadListeners += listener
                        }
                    }
                    
                    fun checkIsLoaded() {
                        if (!isLoaded) {
                            throw IllegalStateException("Module '$moduleName' is not loaded. Call loadModule() first and wait for loading to be finished.")
                        }
                    }
                    
                    fun destroy(nativeObject: Any) = $moduleMemberName.destroy(nativeObject)
                }
            """.trimIndent())
        }
    }

    private fun Writer.writeHeader() {
        if (fileHeader.isNotEmpty()) {
            write("/*\n")
            fileHeader.lines().forEach { write(" * $it\n") }
            write(" */\n")
        }
    }

    private fun IdlModel.generatePackage(pkg: String, ktPkg: String, outPath: String) {
        createOutFileWriter(outPath).use { w ->
            if (ktPkg.isNotEmpty()) {
                w.writeHeader()
                w.write("""
                    @file:Suppress("UnsafeCastFromDynamic", "ClassName", "FunctionName", "UNUSED_VARIABLE", "UNUSED_PARAMETER", "unused")

                    package $ktPkg
                """.trimIndent())
                getInterfacesByPackage(pkg).forEach {
                    it.generate(this, w)
                }
                getEnumsByPackage(pkg).forEach {
                    it.generate(w)
                }
            }
        }
    }

    private fun IdlInterface.generate(model: IdlModel, w: Writer) {
        w.write("\n\nexternal interface $name")
        if (superInterfaces.isNotEmpty()) {
            w.write(" : ${superInterfaces.joinToString(", ")}")
        }

        val nonCtorFuns = functions.filter { it.name != name }
        if (nonCtorFuns.isNotEmpty() || attributes.isNotEmpty()) {
            w.write(" {\n")

            attributes.forEach { attr ->
                w.write("    var ${attr.name}: ${model.ktType(attr.type, attr.isNullable(this))}\n")
            }
            if (attributes.isNotEmpty() && nonCtorFuns.isNotEmpty()) {
                w.write("\n")
            }
            nonCtorFuns.forEach { func ->
                val funcName = "$name.${func.name}"
                val argsStr = func.parameters.joinToString(", ", transform = { "${it.name}: ${model.ktType(it.type, it.isNullable(this, funcName))}" })
                val retType = if (func.returnType.typeName != "void") {
                    ": ${model.ktType(func.returnType, nullableReturnValues.contains(funcName))}"
                } else {
                    ""
                }
                w.write("    fun ${func.name}($argsStr)$retType\n")
            }

            w.write("}")
        }

        functions.filter { it.name == name }.forEach { ctor ->
            val funcName = "$name.${ctor.name}"
            val argsStr = ctor.parameters.joinToString(", ", transform = { "${it.name}: ${model.ktType(it.type, it.isNullable(this, funcName))}" })
            val argNames = ctor.parameters.joinToString(", ", transform = { it.name })
            w.append('\n').append("""
                fun $name($argsStr): $name {
                    val module = $moduleLocation
                    return js("new module.$name($argNames)")
                }
            """.trimIndent())
        }
    }

    private fun IdlAttribute.isNullable(parentIf: IdlInterface): Boolean {
        return nullableAttributes.contains("${parentIf.name}.$name")
    }

    private fun IdlFunctionParameter.isNullable(parentIf: IdlInterface, funcName: String): Boolean {
        val paramName = "$parentIf.$funcName" to name
        return nullableParameters.contains(paramName)
    }

    private fun IdlEnum.generate(w: Writer) {
        if (values.isNotEmpty()) {
            w.write("\n\nobject $name {\n")
            values.forEach { enumVal ->
                var clampedName = enumVal
                if (enumVal.contains("::")) {
                    clampedName = enumVal.substring(enumVal.indexOf("::") + 2)
                }
                w.write("    val $clampedName: Int get() = $moduleLocation._emscripten_enum_${name}_$clampedName()\n")
            }
            w.write("}")
        }
    }

    private fun IdlModel.ktType(type: IdlType, isNullable: Boolean): String {
        return if (enums.any { it.name == type.typeName }) {
            "Int"
        } else {
            val isPrimitive = type.typeName in idlTypeMap.keys
            var typeStr = idlTypeMap.getOrDefault(type.typeName, type.typeName)
            if (!isPrimitive && (isNullable || allTypesNullable)) {
                typeStr += "?"
            }
            if (type.isArray) {
                typeStr = "Array<$typeStr>"
            }
            typeStr
        }
    }

    companion object {
        val idlTypeMap = mapOf(
            "boolean" to "Boolean",
            "float" to "Float",
            "double" to "Double",
            "byte" to "Byte",
            "DOMString" to "String",
            "octet" to "Byte",
            "short" to "Short",
            "long" to "Int",
            "long long" to "Long",
            "unsigned short" to "Short",
            "unsigned long" to "Int",
            "unsigned long long" to "Long",
            "void" to "Unit",
            "any" to "Int",
            "VoidPtr" to "Any"
        )
    }
}