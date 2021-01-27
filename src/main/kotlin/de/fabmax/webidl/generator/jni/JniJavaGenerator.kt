package de.fabmax.webidl.generator.jni

import de.fabmax.webidl.generator.CodeGenerator
import de.fabmax.webidl.model.*
import java.io.File
import java.io.Writer

class JniJavaGenerator : CodeGenerator() {

    var packagePrefix = ""

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

    private lateinit var nativeObject: JavaClass
    private val typeMap = mutableMapOf<String, JavaClass>()

    init {
        outputDirectory = "./generated/java"
    }

    override fun generate(model: IdlModel) {
        super.generate(model)

        generateFrameworkClasses()
        makeClassMappings(model)

        for (pkg in model.collectPackages()) {
            model.generatePackage(pkg)
        }
    }

    private fun generateFrameworkClasses() {
        nativeObject = JavaClass("NativeObject", false, "", packagePrefix).apply {
            protectedDefaultContructor = true
            generatePointerWrapMethods = false
        }
        createOutFileWriter(nativeObject.fileName).use { w ->
            nativeObject.generateSource(w) {
                append("""
                    protected long address = 0L;
                    
                    protected NativeObject(long address) {
                        this.address = address;
                    }
                    
                    public static NativeObject wrapPointer(long address) {
                        return new NativeObject(address);
                    }
                    
                    public long getAddress() {
                        return address;
                    }
                """.trimIndent().prependIndent("    "))
            }
        }
    }

    private fun makeClassMappings(model: IdlModel) {
        typeMap.clear()
        typeMap[nativeObject.name] = nativeObject

        // 1st pass collect all interface/class and enum types
        for (idlPkg in model.collectPackages()) {
            for (idlIf in model.getInterfacesByPackage(idlPkg)) {
                typeMap[idlIf.name] = JavaClass(idlIf.name, false, idlPkg, packagePrefix).apply {
                    protectedDefaultContructor = !idlIf.hasDefaultConstructor()
                    generatePointerWrapMethods = true
                }
            }
            for (idlEn in model.getEnumsByPackage(idlPkg)) {
                typeMap[idlEn.name] = JavaClass(idlEn.name, true, idlPkg, packagePrefix).apply {
                    protectedDefaultContructor = false
                    generatePointerWrapMethods = false
                }
            }
        }

        // 2nd pass collect imports and set super classes
        for (idlIf in model.interfaces) {
            val imports = mutableSetOf<String>()

            idlIf.superInterfaces.forEach { imports += it }
            if (idlIf.superInterfaces.isEmpty()) {
                imports += nativeObject.name
            }
            idlIf.functions.forEach { func ->
                if (func.returnType.isComplexType) {
                    imports += func.returnType.typeName
                }
                func.parameters.forEach { param ->
                    if (param.type.isComplexType) {
                        imports += param.type.typeName
                    }
                }
            }
            idlIf.attributes.forEach { attrib ->
                if (attrib.type.isComplexType) {
                    imports += attrib.type.typeName
                }
            }

            val javaClass = typeMap[idlIf.name] ?: throw IllegalStateException("Unknown idl type: ${idlIf.name}")
            if (idlIf.superInterfaces.isEmpty()) {
                // every generated class derives from NativeObject
                javaClass.superClass = nativeObject
            } else {
                if (idlIf.superInterfaces.size > 1) {
                    System.err.println("warning: ${idlIf.name} implements ${idlIf.superInterfaces}")
                    System.err.println("warning: multiple super interfaces are not supported!")
                }
                javaClass.superClass = typeMap[idlIf.superInterfaces[0]] ?: throw IllegalStateException("Unknown idl type: ${idlIf.superInterfaces[0]}")
            }

            imports.forEach {
                javaClass.imports += typeMap[it] ?: throw IllegalStateException("Unknown idl type: $it")
            }
        }
    }

    private fun IdlInterface.hasDefaultConstructor(): Boolean {
        return functions.any { it.name == name && it.parameters.isEmpty() }
    }

    private fun IdlModel.generatePackage(idlPkg: String) {
        getInterfacesByPackage(idlPkg).forEach { idlIf ->
            val javaClass = typeMap[idlIf.name] ?: throw IllegalStateException("Unknown idl type: $name")
            createOutFileWriter(javaClass.path).use {
                idlIf.generate(javaClass, it)
            }
        }
        getEnumsByPackage(idlPkg).forEach { idlEn ->
            val javaClass = typeMap[idlEn.name] ?: throw IllegalStateException("Unknown idl type: $name")
            createOutFileWriter(javaClass.path).use {
                idlEn.generate(javaClass, it)
            }
        }
    }

    private fun IdlInterface.generate(javaClass: JavaClass, w: Writer) = javaClass.generateSource(w) {
        val ctorFunctions = functions.filter { it.name == name }
        if (ctorFunctions.isNotEmpty()) {
            w.append("    // Constructors\n\n")
            ctorFunctions.forEach { ctor ->
                generateConstructor(ctor, w)
            }
        }

        if (!hasDecorator("NoDelete")) {
            w.append("    // Destructor\n\n")
            generateDestructor(w)
        }

        if (attributes.isNotEmpty()) {
            w.append("    // Attributes\n\n")
            attributes.forEach { attrib ->
                generateGet(attrib, w)
                if (!attrib.isReadonly) {
                    generateSet(attrib, w)
                }
            }
        }

        val nonCtorFunctions = functions.filter { it.name != name }
            if (nonCtorFunctions.isNotEmpty()) {
                w.append("    // Functions\n\n")
            nonCtorFunctions.forEach { func ->
                generateFunction(func, w)
            }
        }
    }

    private fun IdlFunctionParameter.isNullable(idlIf: IdlInterface, idlFunc: IdlFunction): Boolean {
        return ("${idlIf.name}.${idlFunc.name}" to name) in nullableParameters
    }

    private fun IdlAttribute.isNullable(idlIf: IdlInterface): Boolean {
        return "${idlIf.name}.$name" in nullableAttributes
    }

    private fun IdlInterface.generateConstructor(ctorFunc: IdlFunction, w: Writer) {
        val nativeToJavaParams = ctorFunc.parameters.zip(ctorFunc.parameters.map { JavaType(it.type) })
        val nativeArgs = nativeToJavaParams.joinToString { (nat, java) -> "${java.internalType} ${nat.name}" }
        val javaArgs = nativeToJavaParams.joinToString { (nat, java) -> "${java.javaType} ${nat.name}" }
        val callArgs = nativeToJavaParams.joinToString { (nat, java) -> java.unbox(nat.name, nat.isNullable(this, ctorFunc)) }

        w.append("""
            private native long _${ctorFunc.name}($nativeArgs);
            public ${ctorFunc.name}($javaArgs) {
                address = _${ctorFunc.name}($callArgs);
            }
        """.trimIndent().prependIndent("    ")).append("\n\n")
    }

    private fun generateDestructor(w: Writer) {
        w.append("""
            private native long _delete_native_instance(long address);
            public void destroy() {
                if (address == 0L) {
                    throw new IllegalStateException(this + " is already deleted");
                }
                _delete_native_instance(address);
                address = 0L;
            }
        """.trimIndent().prependIndent("    ")).append("\n\n")
    }

    private fun IdlInterface.generateFunction(func: IdlFunction, w: Writer) {
        val nativeToJavaParams = func.parameters.zip(func.parameters.map { JavaType(it.type) })
        val staticMod = if (func.isStatic) " static" else ""
        val javaArgs = nativeToJavaParams.joinToString { (nat, java) -> "${java.javaType} ${nat.name}" }
        val returnType = JavaType(func.returnType)

        var nativeArgs = if (func.isStatic) "" else "long address"
        var callArgs = if (func.isStatic) "" else "address"
        if (nativeToJavaParams.isNotEmpty()) {
            if (nativeArgs.isNotEmpty()) { nativeArgs += ", " }
            if (callArgs.isNotEmpty()) { callArgs += ", " }
            nativeArgs += nativeToJavaParams.joinToString { (nat, java) -> "${java.internalType} ${nat.name}" }
            callArgs += nativeToJavaParams.joinToString { (nat, java) -> java.unbox(nat.name, nat.isNullable(this, func)) }
        }

        w.append("""
            private$staticMod native ${returnType.internalType} _${func.name}($nativeArgs);
            public$staticMod ${returnType.javaType} ${func.name}($javaArgs) {
                ${returnType.boxedReturn("_${func.name}($callArgs)", "$name.${func.name}" in nullableReturnValues)};
            }
        """.trimIndent().prependIndent("    ")).append("\n\n")
    }

    private fun IdlInterface.generateGet(attrib: IdlAttribute, w: Writer) {
        val javaType = JavaType(attrib.type)
        val methodName = "get${firstCharToUpper(attrib.name)}"

        val staticMod = if (attrib.isStatic) " static" else ""
        val arrayModPriv = if (attrib.type.isArray) ", int index" else ""
        val arrayModPub = if (attrib.type.isArray) "int index" else ""
        val arrayCallMod = if (attrib.type.isArray) ", index" else ""
        val addressSig = if (attrib.isStatic) "" else "long address"
        val addressCall = if (attrib.isStatic) "" else "address"

        w.append("""
            private$staticMod native ${javaType.internalType} _$methodName($addressSig$arrayModPriv);
            public$staticMod ${javaType.javaType} $methodName($arrayModPub) {
                ${javaType.boxedReturn("_$methodName($addressCall$arrayCallMod)", attrib.isNullable(this))};
            }
        """.trimIndent().prependIndent("    ")).append("\n\n")
    }

    private fun IdlInterface.generateSet(attrib: IdlAttribute, w: Writer) {
        val javaType = JavaType(attrib.type)
        val methodName = "set${firstCharToUpper(attrib.name)}"

        val staticMod = if (attrib.isStatic) " static" else ""
        val arrayModPub = if (attrib.type.isArray) "int index, " else ""
        val arrayCallMod = if (attrib.type.isArray) ", index" else ""
        val addressCall = if (attrib.isStatic) "" else "address"

        var nativeSig = if (attrib.isStatic) "" else "long address"
        if (attrib.type.isArray) {
            if (nativeSig.isNotEmpty()) { nativeSig += ", "}
            nativeSig += "int index"
        }
        if (nativeSig.isNotEmpty()) { nativeSig += ", "}
        nativeSig += "${javaType.internalType} value"

        w.append("""
            private$staticMod native void _$methodName($nativeSig);
            public$staticMod void $methodName($arrayModPub${javaType.javaType} value) {
                _$methodName($addressCall$arrayCallMod, ${javaType.unbox("value", attrib.isNullable(this))});
            }
        """.trimIndent().prependIndent("    ")).append("\n\n")
    }

    private fun IdlEnum.generate(javaClass: JavaClass, w: Writer) = javaClass.generateSource(w) {
        unprefixedValues.forEach { enumVal ->
            w.write("    public static final int $enumVal = _get$enumVal();\n")
        }
        w.write("\n")
        unprefixedValues.forEach { enumVal ->
            w.write("    private static native int _get$enumVal();\n")
        }
    }

    private class JavaClass(val name: String, val isEnum: Boolean, idlPkg: String, packagePrefix: String) {
        val javaPkg: String
        val fqn: String
        val fileName = "$name.java"

        // only use idlPkg instead of prefixed / full java package for path construction
        // this way the output directory can point into a package within a project and does not need to target the
        // top-level source directory of a project
        val path = if (idlPkg.isEmpty()) fileName else File(idlPkg.replace('.', '/'), fileName).path

        var visibility = "public"
        var modifier = ""
        var protectedDefaultContructor = true
        var generatePointerWrapMethods = true

        var superClass: JavaClass? = null
        val imports = mutableListOf<JavaClass>()

        init {
            javaPkg = when {
                packagePrefix.isEmpty() -> idlPkg
                idlPkg.isEmpty() -> packagePrefix
                else -> "$packagePrefix.$idlPkg"
            }
            fqn = if (javaPkg.isEmpty()) name else "$javaPkg.$name"
        }

        fun generatePackage(w: Writer) {
            if (javaPkg.isNotEmpty()) {
                w.write("package $javaPkg;\n\n")
            }
        }

        fun generateImports(w: Writer) {
            imports.filter { javaPkg != it.javaPkg }.sortedBy { it.fqn }.forEach { import ->
                w.write("import ${import.fqn};\n")
            }
            w.write("\n")
        }

        fun generateClassStart(w: Writer) {
            w.write("$visibility ")
            if (modifier.isNotEmpty()) {
                w.write("$modifier ")
            }
            w.write("class $name ")
            superClass?.let { w.write("extends ${it.name} ") }
            w.write("{\n\n")

            if (protectedDefaultContructor) {
                w.append("""
                    protected $name() { }
                """.trimIndent().prependIndent("    ")).append("\n\n")
            }

            if (generatePointerWrapMethods) {
                w.append("""
                    public static $name wrapPointer(long address) {
                        return new $name(address);
                    }
                    
                    protected $name(long address) {
                        super(address);
                    }
                """.trimIndent().prependIndent("    ")).append("\n\n")
            }
        }

        fun generateSource(w: Writer, body: Writer.() -> Unit) {
            generatePackage(w)
            generateImports(w)
            generateClassStart(w)
            body(w)
            w.append("}\n")
        }
    }

    private inner class JavaType(val idlType: IdlType) {
        val internalType: String
        val javaType: String
        private val requiresMarshalling: Boolean
            get() = internalType != javaType

        init {
            when {
                idlType.isPrimitive -> {
                    internalType = idlPrimitiveTypeMap[idlType.typeName] ?: throw IllegalStateException("Unknown idl type: ${idlType.typeName}")
                    javaType = internalType
                }
                idlType.isAnyOrVoidPtr -> {
                    internalType = "long"
                    javaType = nativeObject.name
                }
                typeMap[idlType.typeName]?.isEnum == true -> {
                    internalType = "int"
                    javaType = "int"
                }
                else -> {
                    internalType = "long"
                    javaType = idlType.typeName
                }
            }
        }

        fun boxedReturn(value: String, isNullable: Boolean = false): String {
            return when {
                idlType.isVoid -> value
                isNullable && requiresMarshalling -> "long tmp = $value;\n                return (tmp != 0L ? $javaType.wrapPointer(tmp) : null)"
                requiresMarshalling -> "return $javaType.wrapPointer($value)"
                else -> "return $value"
            }
        }

        fun unbox(value: String, isNullable: Boolean = false): String {
            return when {
                isNullable && requiresMarshalling -> "($value != null ? $value.getAddress() : 0L)"
                requiresMarshalling -> "$value.getAddress()"
                else -> value
            }
        }
    }

    companion object {
        val idlPrimitiveTypeMap = mapOf(
            "boolean" to "boolean",
            "float" to "float",
            "double" to "double",
            "byte" to "byte",
            "DOMString" to "String",
            "octet" to "byte",
            "short" to "short",
            "long" to "int",
            "long long" to "long",
            "unsigned short" to "short",
            "unsigned long" to "int",
            "unsigned long long" to "long",
            "void" to "void"
        )
    }
}