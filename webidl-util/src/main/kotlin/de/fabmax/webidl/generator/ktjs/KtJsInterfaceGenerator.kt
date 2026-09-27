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
    var moduleName = "module"

    /**
     * Name of the emscripten loader Promise returning the loaded library
     */
    var modulePromiseName = "Module"

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
        generatePrototypeWrappers(model)
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

    private fun String.trimIndentEmptyBlanks(): String = trimIndent().lines()
        .joinToString("\n") { it.ifBlank { "" } }

    private fun generateLoader(model: IdlModel) {
        generateExpectLoader(model)
        generateActualLoader(model, "js")
        generateActualLoader(model, "wasmJs")
    }

    private fun generateExpectLoader(model: IdlModel) {
        val localClsName = firstCharToUpper("${model.name}Loader")
        moduleMemberName = firstCharToLower(model.name)
        loaderClassName = localClsName
        createOutFileWriter("$localClsName.kt").use { w ->
            w.writeHeader()
            if (packagePrefix.isNotEmpty()) {
                w.write("\npackage $packagePrefix\n\n")
            }
            w.write("""
                import kotlin.js.JsAny

                internal expect object $localClsName {
                    internal val $moduleMemberName: JsAny
                    val isLoaded: Boolean
                    suspend fun loadModule()
                }
                
                sealed external interface DestroyableNative : JsAny
                
                expect fun DestroyableNative.destroy()
            """.trimIndentEmptyBlanks())
        }
    }

    private fun generateActualLoader(model: IdlModel, platform: String) {
        val localClsName = firstCharToUpper("${model.name}Loader")
        moduleMemberName = firstCharToLower(model.name)
        loaderClassName = localClsName
        createOutFileWriter("$localClsName.$platform.kt").use { w ->
            w.writeHeader()
            if (packagePrefix.isNotEmpty()) {
                w.write("\npackage $packagePrefix\n\n")
            }
            w.write("""   
                import kotlinx.coroutines.await       
                import kotlin.js.JsAny
                import kotlin.js.JsModule
                import kotlin.js.Promise
                import kotlin.js.js

                @JsModule("$moduleName")
                private external val $modulePromiseName: () -> Promise<JsAny>

                actual object $localClsName {
                    private var ${moduleMemberName}Module: JsAny? = null
                    private val ${moduleMemberName}Promise = $modulePromiseName()
                    private var _isLoaded = false
                    actual val isLoaded: Boolean get() = _isLoaded

                    internal actual val $moduleMemberName: JsAny get() = requireNotNull(${moduleMemberName}Module) {
                        "Module '$moduleName' is not loaded. Call loadModule() first"
                    }

                    actual suspend fun loadModule() {
                        if (!isLoaded) {
                            ${moduleMemberName}Promise.then<JsAny> { ${moduleMemberName}Module = it; it }
                        }
                        ${moduleMemberName}Promise.await<JsAny>()
                        _isLoaded = true
                    }
                    
                    fun checkIsLoaded() {
                        if (!isLoaded) {
                            throw IllegalStateException("Module '$moduleName' is not loaded. Call loadModule() first.")
                        }
                    }
                }
                
                private fun destroyNative(module: JsAny, obj: DestroyableNative): Unit = js("module.destroy(obj)")
                actual fun DestroyableNative.destroy() = destroyNative(${localClsName}.${moduleMemberName}, this)
            """.trimIndentEmptyBlanks())
        }
    }

    private fun generatePrototypeWrappers(model: IdlModel) {
        val staticClasses = model.interfaces.filter {
            it.functions.size + it.attributes.size > 0 &&
            it.functions.all { f -> f.isStatic } &&
            it.attributes.all { a -> a.isStatic } &&
            it.constructors.isEmpty()
        }.sortedBy { it.name }
        if (staticClasses.isEmpty()) {
            return
        }

        createOutFileWriter("prototypes/Prototypes.kt").use { w ->
            val pkgPre = if (packagePrefix.isNotEmpty()) "${packagePrefix}." else ""
            w.write("@file:Suppress(\"UNCHECKED_CAST_TO_EXTERNAL_INTERFACE\", \"unused\")\n\npackage ${pkgPre}prototypes\n\n")
            val loaderName = "${model.name}Loader"
            w.appendLine("import kotlin.js.JsAny")
            w.appendLine("import kotlin.js.js")
            w.appendLine("import ${pkgPre}${loaderName}")
            staticClasses.forEach { staticClass ->
                w.appendLine("import ${pkgPre}${staticClass.name}")
            }
            w.appendLine()

            staticClasses.forEach { staticClass ->
                w.appendLine("val ${staticClass.name}: ${staticClass.name} = ${staticClass.name}($moduleLocation)")
                w.appendLine("private fun ${staticClass.name}(module: JsAny): ${staticClass.name} = js(\"module.${staticClass.name}.prototype\")")
                w.appendLine()
            }
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
        val interfaces = getInterfacesByPackage(pkg).filter { it.matchesPlatform("emscripten") }
        val enums = getEnumsByPackage(pkg).filter { it.matchesPlatform("emscripten") }
        if (interfaces.isEmpty() && enums.isEmpty()) {
            return
        }

        createOutFileWriter(outPath).use { w ->
            if (ktPkg.isNotEmpty()) {
                w.writeHeader()
                w.append("""
                    @file:Suppress("ClassName", "FunctionName", "UNUSED_PARAMETER", "unused", "NOTHING_TO_INLINE")

                    package $ktPkg
                    
                    import kotlin.js.JsAny
                    import kotlin.js.js
                """.trimIndentEmptyBlanks()).append("\n\n")
                interfaces.forEach {
                    if (it.hasDecorator(IdlDecorator.JS_IMPLEMENTATION)) {
                        it.generateCallback(this, w)
                    } else {
                        it.generate(this, w)
                    }
                }
                enums.forEach {
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
            (func.returnType as? IdlSimpleType) ?: error("Unsupported type ${func.returnType::class.java.name}")
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
        var destroyMarker = ""
        if (!hasDecorator(IdlDecorator.NO_DELETE)) {
            destroyMarker = ", DestroyableNative"
        }

        w.write("external interface $name : JsAny$destroyMarker")
        if (superInterfaces.isNotEmpty()) {
            w.write(", ${superInterfaces.joinToString(", ")}")
        }

        val nonCtorFuns = emscriptenFunctions.filter { it.name != name }
        if (nonCtorFuns.isNotEmpty() || attributes.isNotEmpty()) {
            w.write(" {\n")

            // pointer / object address
            if (superInterfaces.isEmpty()) {
                w.append("""
                    /**
                     * Native object address.
                     */
                    val ptr: Int
                """.trimIndentEmptyBlanks().prependIndent("    ")).append("\n\n")
            }

            emscriptenAttributes.forEach { attr ->
                (attr.type as? IdlSimpleType) ?: error("Unsupported type ${attr.type::class.java.name}")
                w.append("""
                    /**
                     * ${makeTypeDoc(attr.type, attr.decorators)}
                     */
                """.trimIndentEmptyBlanks().prependIndent("    ")).append("\n")

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
                val modifier = if (func.isOverride(this, model)) "override " else ""
                val argsStr = func.parameters.joinToString(", ") { "${it.name}: ${model.ktType(it.type, it.isNullable())}" }
                (func.returnType as? IdlSimpleType) ?: error("Unsupported type ${func.returnType::class.java.name}")
                val retType = if (func.returnType.typeName != "void") {
                    ": ${model.ktType(func.returnType, func.hasDecorator(IdlDecorator.NULLABLE))}"
                } else {
                    ""
                }
                w.write("    ${modifier}fun ${func.name}($argsStr)$retType\n\n")
            }

            w.write("}")
        }
        w.write("\n\n")
        generateExtensionConstructor(w)
        generateExtensionPointerWrapper(w)
        generateExtensionAttributes(w)
        generateExtensionEnumFuns(w)
    }

    private fun IdlInterface.generateExtensionConstructor(w: Writer) {
        emscriptenFunctions.filter { it.isCtor }.forEach { ctor ->
            // basic javadoc with some extended type info
            val paramDocs = mutableMapOf<String, String>()
            ctor.parameters.forEach { param -> paramDocs[param.name] = makeTypeDoc(param.type, param.decorators) }
            generateJavadoc(paramDocs, "", w, "")

            // extension constructor function
            val argsStr = ctor.parameters.joinToString(", ", transform = { "${it.name}: ${model.ktType(it.type, it.isNullable())}" })
            val argNames = ctor.parameters.joinToString(", ", transform = { it.name })
            val argsWithModule = (if (argsStr.isEmpty()) "" else "$argsStr, ") + "_module: JsAny = $moduleLocation"
            w.append("""
                fun $name($argsWithModule): $name = js("new _module.$name($argNames)")
            """.trimIndentEmptyBlanks()).append("\n\n")
        }
    }

    private fun IdlInterface.generateExtensionPointerWrapper(w: Writer) {
        w.append("""
            fun ${name}FromPointer(ptr: Int, _module: JsAny = $moduleLocation): $name = js("_module.wrapPointer(ptr, _module.${name})")
        """.trimIndentEmptyBlanks()).append("\n\n")
    }

    private fun IdlInterface.generateExtensionAttributes(w: Writer) {
        val gets = emscriptenFunctions.filter { get ->
            get.name.startsWith("get") && get.parameters.isEmpty() && functions.none {
                it.name == get.name.replace("get", "set")
            }
        }
        gets.forEach { get ->
            val attribName = firstCharToLower(get.name.substring(3))
            if (get.returnType.isEnum) {
                val typeName = get.returnType.simpleTypeName
                w.append("""
                    val $name.$attribName: $typeName
                        get() = $typeName.forValue(${get.name}())
                """.trimIndentEmptyBlanks()).append("\n")
            } else {
                w.append("""
                    val $name.$attribName
                        get() = ${get.name}()
                """.trimIndentEmptyBlanks()).append("\n")
            }
        }
        if (gets.isNotEmpty()) {
            w.append('\n')
        }

        val getSets = emscriptenFunctions.filter { get ->
            get.name.startsWith("get") && get.parameters.isEmpty() && functions.any {
                (get.returnType as? IdlSimpleType) ?: error("Unsupported type ${get.returnType::class.java.name}")
                it.name == get.name.replace("get", "set")
                        && it.parameters.size == 1
                        && (it.parameters[0].type as? IdlSimpleType)?.typeName == get.returnType.typeName
            }
        }
        getSets.forEach { get ->
            val attribName = firstCharToLower(get.name.substring(3))
            val setName = "s${get.name.substring(1)}"
            if (get.returnType.isEnum) {
                val typeName = get.returnType.simpleTypeName
                w.append("""
                    var $name.$attribName: $typeName
                        get() = $typeName.forValue(${get.name}())
                        set(value) { $setName(value.value) }
                """.trimIndentEmptyBlanks()).append("\n")
            } else {
                w.append("""
                    var $name.$attribName
                        get() = ${get.name}()
                        set(value) { $setName(value) }
                """.trimIndentEmptyBlanks()).append("\n")
            }
        }
        if (getSets.isNotEmpty()) {
            w.appendLine()
        }

        val arrayGetSets = emscriptenAttributes.filter { it.type is IdlSimpleType && it.type.isArray }
        arrayGetSets.forEach { attr ->
            val ktType = model.ktType(attr.type, attr.isNullable())
            w.appendLine("inline fun $name.get${attr.name.capitalizeFirstChar()}(index: Int) = get_${attr.name}(index)")
            w.appendLine("inline fun $name.set${attr.name.capitalizeFirstChar()}(index: Int, value: ${ktType}) = set_${attr.name}(index, value)")
        }
        if (arrayGetSets.isNotEmpty()) {
            w.appendLine()
        }

        val enumAttrs = emscriptenAttributes.filter { it.type.isEnum && !it.isStatic }
        enumAttrs.forEach { attr ->
            val typeName = (attr.type as IdlSimpleType).typeName
            if (attr.isReadonly) {
                w.appendLine("val $name.${attr.name}Enum: $typeName get() = $typeName.forValue(${attr.name})")
            } else {
                w.appendLine("var $name.${attr.name}Enum: $typeName")
                w.appendLine("    get() = $typeName.forValue(${attr.name})")
                w.appendLine("    set(value) { ${attr.name} = value.value }")
            }
        }
        if (enumAttrs.isNotEmpty()) {
            w.appendLine()
        }
    }

    private fun IdlInterface.generateExtensionEnumFuns(w: Writer) {
        val funs = emscriptenFunctions.filter { func ->
            !func.isCtor && func.parameters.any { it.type.isEnum }
        }
        funs.forEach { func ->
            val argsStr = func.parameters.joinToString(", ", transform = { "${it.name}: ${model.ktType(it.type, it.isNullable(), enumsAsInts = false)}" })
            val argNames = func.parameters.joinToString(", ", transform = { if (it.type.isEnum) "${it.name}.value" else it.name })
            if (func.returnType.isEnum) {
                val typeName = func.returnType.simpleTypeName
                w.appendLine("fun $name.${func.name}($argsStr) = $typeName.forValue(${func.name}($argNames))")
            } else {
                w.appendLine("fun $name.${func.name}($argsStr) = ${func.name}($argNames)")
            }
        }
        if (funs.isNotEmpty()) {
            w.appendLine()
        }
    }

    private fun IdlFunction.isOverride(parentIf: IdlInterface, model: IdlModel): Boolean {
        for (superIf in parentIf.superInterfaces) {
            val sif = model.interfaces.find { it.name == superIf }
            if (sif != null && sif.hasFunction(name, parameters, model)) {
                return true
            }
        }
        return false
    }

    private fun IdlInterface.hasFunction(funName: String, parameters: List<IdlFunctionParameter>, model: IdlModel): Boolean {
        val func = functionsByName[funName]
        if (func != null && func.parameters == parameters) {
            return true
        }
        for (superIf in superInterfaces) {
            val sif = model.interfaces.find { it.name == superIf }
            if (sif != null && sif.hasFunction(funName, parameters, model)) {
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
            val enumVals = mutableListOf<String>()
            w.write("value class $name private constructor(val value: Int) {\n")
            w.write("    companion object {\n")
            values.forEach { enumVal ->
                var clampedName = enumVal
                if (enumVal.contains("::")) {
                    clampedName = enumVal.substring(enumVal.indexOf("::") + 2)
                }
                w.write("        val $clampedName: $name = $name(${name}_$clampedName($moduleLocation))\n")
                enumVals += clampedName
            }
            w.appendLine("        fun forValue(value: Int) = when(value) {")
            enumVals.forEach { w.appendLine("            $it.value -> $it") }
            w.appendLine("            else -> error(\"Invalid enum value \$value for enum $name\")")
            w.appendLine("        }")
            w.write("    }\n}\n\n")
            enumVals.forEach {
                w.appendLine("private fun ${name}_$it(module: JsAny): Int = js(\"module._emscripten_enum_${name}_$it()\")")
            }
            w.appendLine()
        }
    }

    private fun makeTypeDoc(type: IdlType, decorators: List<IdlDecorator> = emptyList()): String {
        val decoString = when {
            type.isEnum -> " (enum)"
            decorators.isNotEmpty() -> " ${decorators.joinToString(", ", "(", ")")}"
            else -> ""
        }
        val typeString = when {
            type is IdlSimpleType && type.isComplexType -> "WebIDL type: [${type.typeName}]"
            else -> "WebIDL type: ${(type as? IdlSimpleType)?.typeName}"
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

    private val IdlFunction.isCtor: Boolean get() = name == parentInterface?.name

    private val IdlType.isEnum: Boolean get() = this is IdlSimpleType && model.enums.any { it.name == typeName }

    private val IdlType.simpleTypeName: String get() = (this as IdlSimpleType).typeName

    private fun IdlModel.ktType(type: IdlType, isNullable: Boolean, enumsAsInts: Boolean = true): String {
        (type as? IdlSimpleType) ?: error("Unsupported type ${type::class.java.name}")
        return if (type.isEnum) {
            if (enumsAsInts) "Int" else type.typeName
        } else {
            val isPrimitive = type.typeName in idlTypeMap.keys
            var typeStr = idlTypeMap.getOrDefault(type.typeName, type.typeName)
            if (!isPrimitive && (isNullable || allTypesNullable)) {
                typeStr += "?"
            }
            typeStr
        }
    }

    private fun String.capitalizeFirstChar(): String = replaceFirstChar { it.uppercaseChar()}

    companion object {
        val idlTypeMap = mapOf(
            "boolean" to "Boolean",
            "float" to "Float",
            "double" to "Double",
            "byte" to "Byte",
            "DOMString" to "String",
            "USVString" to "String",
            "octet" to "Byte",
            "short" to "Short",
            "long" to "Int",
            "long long" to "Long",
            "unsigned short" to "Short",
            "unsigned long" to "Int",
            "unsigned long long" to "Long",
            "void" to "Unit",
            "any" to "Int",
            "VoidPtr" to "JsAny"
        )
    }
}