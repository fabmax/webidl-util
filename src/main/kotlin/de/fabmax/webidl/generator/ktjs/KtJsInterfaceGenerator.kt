package de.fabmax.webidl.generator.ktjs

import de.fabmax.webidl.generator.CodeGenerator
import de.fabmax.webidl.generator.indent
import de.fabmax.webidl.model.*
import java.io.File
import java.io.Writer
import java.util.*

class KtJsInterfaceGenerator : CodeGenerator() {

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

    private var loaderClassName = ""
    private var moduleMemberName = ""
    private val moduleLocation: String
        get() = "$loaderClassName.$moduleMemberName"

    private lateinit var model: IdlModel

    init {
        outputDirectory = "./generated/ktJs"
    }

    override fun generate(model: IdlModel) {
        this.model = model
        deleteDirectory(File(outputDirectory))
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
                            ${moduleMemberName}Promise.then { module: dynamic ->
                                $moduleMemberName = module
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
                    
                    fun destroy(nativeObject: Any) {
                        physXJs.destroy(nativeObject)
                    }
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
                w.append("""
                    @file:Suppress("UnsafeCastFromDynamic", "ClassName", "FunctionName", "UNUSED_VARIABLE", "UNUSED_PARAMETER", "unused")

                    package $ktPkg
                """.trimIndent()).append("\n\n")
                getInterfacesByPackage(pkg).forEach {
                    if (it.hasDecorator(IdlDecorator.JS_IMPLEMENTATION)) {
                        it.generateCallback(this, w)
                    } else {
                        it.generate(this, w)
                    }
                }
                getEnumsByPackage(pkg).forEach {
                    it.generate(w)
                }
            }
        }
    }

    private fun IdlInterface.generateCallback(model: IdlModel, w: Writer) {
        val superIf = getDecoratorValue("JSImplementation", "")
        w.write("external interface $name : $superIf {\n")

        fun callbackType(cbParam: IdlFunctionParameter): String {
            return if (cbParam.type.isComplexType) {
                // emscripten / webidl binder passes callback parameter objects as naked pointers
                "Int"
            } else {
                model.ktType(cbParam.type, cbParam.isNullable())
            }
        }

        // functions of callback interfaces are members, which have to be set
        functions.filter { it.name != name }.forEach { func ->
            val paramDocs = mutableMapOf<String, String>()
            func.parameters.forEach { param -> paramDocs[param.name] = makeTypeDoc(param.type, param.decorators) }
            val returnDoc = if (func.returnType.isVoid) "" else makeTypeDoc(func.returnType, func.decorators)
            generateJavadoc(paramDocs, returnDoc, w, withAnnotations = false)

            val argsStr = func.parameters.joinToString(", ") { "${it.name}: ${callbackType(it)}" }
            val retType = if (func.returnType.typeName != "void") {
                model.ktType(func.returnType, func.hasDecorator(IdlDecorator.NULLABLE))
            } else {
                "Unit"
            }
            w.write("    var ${func.name}: ($argsStr) -> $retType\n\n")
        }

        w.write("}\n\n")
        generateExtensionConstructor(w)
    }

    private fun IdlInterface.generate(model: IdlModel, w: Writer) {
        w.write("external interface $name")
        if (superInterfaces.isNotEmpty()) {
            w.write(" : ${superInterfaces.joinToString(", ")}")
        }

        val nonCtorFuns = functions.filter { it.name != name }
        if (nonCtorFuns.isNotEmpty() || attributes.isNotEmpty()) {
            w.write(" {\n")

            // pointer / object address
            if (superInterfaces.isEmpty()) {
                w.append("""
                    /**
                     * Native object address.
                     */
                    val ptr: Int
                """.trimIndent().prependIndent("    ")).append("\n\n")
            }

            attributes.forEach { attr ->
                w.append("""
                    /**
                     * ${makeTypeDoc(attr.type, attr.decorators)}
                     */
                """.trimIndent().prependIndent("    ")).append("\n")

                if (attr.type.isArray) {
                    val ktType = model.ktType(attr.type, attr.isNullable())
                    w.append("    fun get_${attr.name}(index: Int): ${ktType}\n")
                    w.append("    fun set_${attr.name}(index: Int, value: ${ktType})\n")
                } else {
                    w.append("    var ${attr.name}: ${model.ktType(attr.type, attr.isNullable())}\n")
                }

            }
            if (attributes.isNotEmpty() && nonCtorFuns.isNotEmpty()) {
                w.write("\n")
            }
            nonCtorFuns.forEach { func ->
                // basic javadoc with some extended type info
                val paramDocs = mutableMapOf<String, String>()
                func.parameters.forEach { param -> paramDocs[param.name] = makeTypeDoc(param.type, param.decorators) }
                val returnDoc = if (func.returnType.isVoid) "" else makeTypeDoc(func.returnType, func.decorators)
                generateJavadoc(paramDocs, returnDoc, w)

                // function declaration
                val isOverride = if (func.isOverride(this, model)) "override " else ""
                val argsStr = func.parameters.joinToString(", ") { "${it.name}: ${model.ktType(it.type, it.isNullable())}" }
                val retType = if (func.returnType.typeName != "void") {
                    ": ${model.ktType(func.returnType, func.hasDecorator(IdlDecorator.NULLABLE))}"
                } else {
                    ""
                }
                w.write("    ${isOverride}fun ${func.name}($argsStr)$retType\n\n")
            }

            w.write("}")
        }
        w.write("\n\n")
        generateExtensionConstructor(w)
        generateExtensionPointerWrapper(w)
        if (!hasDecorator(IdlDecorator.NO_DELETE)) {
            generateExtensionDestructor(w)
        }
        generateExtensionAttributes(w)
    }

    private fun IdlInterface.generateExtensionConstructor(w: Writer) {
        functions.filter { it.name == name }.forEach { ctor ->
            // basic javadoc with some extended type info
            val paramDocs = mutableMapOf<String, String>()
            ctor.parameters.forEach { param -> paramDocs[param.name] = makeTypeDoc(param.type, param.decorators) }
            generateJavadoc(paramDocs, "", w, "")

            // extension constructor function
            val argsStr = ctor.parameters.joinToString(", ", transform = { "${it.name}: ${model.ktType(it.type, it.isNullable())}" })
            val argNames = ctor.parameters.joinToString(", ", transform = { it.name })
            val argsStrInternal = "_module: dynamic" + if (argsStr.isEmpty()) "" else ", $argsStr"
            val argNamesInternal = moduleLocation + if (argNames.isEmpty()) "" else ", $argNames"
            w.append("""
                fun $name($argsStr): $name {
                    fun _$name($argsStrInternal) = js("new _module.$name($argNames)")
                    return _$name($argNamesInternal)
                }
            """.trimIndent()).append("\n\n")
        }
    }

    private fun IdlInterface.generateExtensionPointerWrapper(w: Writer) {
        w.append("""
            fun ${name}FromPointer(ptr: Int): $name {
                fun _${name}_wrap(_module: dynamic, ptr: Int) = js("_module.wrapPointer(ptr, _module.${name})")
                return _${name}_wrap($moduleLocation, ptr)
            }
        """.trimIndent()).append("\n\n")
    }

    private fun IdlInterface.generateExtensionDestructor(w: Writer) {
        w.append("""
            fun $name.destroy() {
                $loaderClassName.destroy(this)
            }
        """.trimIndent()).append("\n\n")
    }

    private fun IdlInterface.generateExtensionAttributes(w: Writer) {
        val gets = functions.filter { get ->
            get.name.startsWith("get") && get.parameters.isEmpty() && functions.none {
                it.name == get.name.replace("get", "set")
            }
        }
        gets.forEach { get ->
            val attribName = firstCharToLower(get.name.substring(3))
            w.append("""
                val $name.$attribName
                    get() = ${get.name}()
            """.trimIndent()).append("\n")
        }
        if (gets.isNotEmpty()) {
            w.append('\n')
        }

        val getSets = functions.filter { get ->
            get.name.startsWith("get") && get.parameters.isEmpty() && functions.any {
                it.name == get.name.replace("get", "set")
                        && it.parameters.size == 1
                        && it.parameters[0].type.typeName == get.returnType.typeName
            }
        }
        getSets.forEach { get ->
            val attribName = firstCharToLower(get.name.substring(3))
            val setName = "s${get.name.substring(1)}"
            w.append("""
                var $name.$attribName
                    get() = ${get.name}()
                    set(value) { $setName(value) }
            """.trimIndent()).append("\n")
        }
        if (getSets.isNotEmpty()) {
            w.append('\n')
        }
    }

    private fun IdlFunction.isOverride(parentIf: IdlInterface, model: IdlModel): Boolean {
        for (superIf in parentIf.superInterfaces) {
            val sif = model.interfaces.find { it.name == superIf }
            if (sif != null && sif.hasFunction(name, model)) {
                return true
            }
        }
        return false
    }

    private fun IdlInterface.hasFunction(funName: String, model: IdlModel): Boolean {
        if (functions.any { it.name == funName }) {
            return true
        }
        for (superIf in superInterfaces) {
            val sif = model.interfaces.find { it.name == superIf }
            if (sif != null && sif.hasFunction(funName, model)) {
                return true
            }
        }
        return false
    }

    private fun IdlFunctionParameter.isNullable(): Boolean {
        return hasDecorator(IdlDecorator.NULLABLE)
    }

    private fun IdlAttribute.isNullable(): Boolean {
        return hasDecorator(IdlDecorator.NULLABLE)
    }


    private fun IdlEnum.generate(w: Writer) {
        if (values.isNotEmpty()) {
            w.write("object $name {\n")
            values.forEach { enumVal ->
                var clampedName = enumVal
                if (enumVal.contains("::")) {
                    clampedName = enumVal.substring(enumVal.indexOf("::") + 2)
                }
                w.write("    val $clampedName: Int get() = $moduleLocation._emscripten_enum_${name}_$clampedName()\n")
            }
            w.write("}\n\n")
        }
    }

    private fun makeTypeDoc(type: IdlType, decorators: List<IdlDecorator> = emptyList()): String {
        val decoString = when {
            type.isEnum() -> " (enum)"
            decorators.isNotEmpty() -> " ${decorators.joinToString(", ", "(", ")")}"
            else -> ""
        }
        val typeString = when {
            type.isComplexType -> "WebIDL type: [${type.typeName}]"
            else -> "WebIDL type: ${type.typeName}"
        }
        return "$typeString$decoString"
    }

    private fun generateJavadoc(paramDocs: Map<String, String>, returnDoc: String, w: Writer, indent: String = indent(4), withAnnotations: Boolean = true) {
        if (paramDocs.isNotEmpty() || returnDoc.isNotEmpty()) {
            val anno = if (withAnnotations) "@" else ""
            w.append("$indent/**\n")
            if (paramDocs.isNotEmpty()) {
                val maxNameLen = paramDocs.keys.map { it.length }.maxOf { it }
                paramDocs.forEach { (name, doc) ->
                    w.append(String.format(Locale.ENGLISH, "$indent * ${anno}param %-${maxNameLen}s %s\n", name, doc))
                }
            }
            if (returnDoc.isNotEmpty()) {
                w.append("$indent * ${anno}return $returnDoc\n")
            }
            w.append("$indent */\n")
        }
    }

    private fun IdlType.isEnum(): Boolean {
        return model.enums.any { it.name == typeName }
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