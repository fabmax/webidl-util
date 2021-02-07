package de.fabmax.webidl.generator.jni

import de.fabmax.webidl.generator.CodeGenerator
import de.fabmax.webidl.model.*
import java.io.File
import java.io.Writer
import java.util.*

class JniJavaGenerator : CodeGenerator() {

    var packagePrefix = ""

    /**
     * List of WebIDL interfaces / classes which should be stack allocatable (i.e. can be created
     * at a user-specified address). This can be beneficial for small high-frequency objects.
     */
    val stackAllocatableClasses = mutableSetOf<String>()

    /**
     * If true simple address-based allocation methods are generated for stack allocatable classes.
     */
    var generateSimpleStackAllocators = true

    /**
     * If true interface-based allocation methods are generated for stack allocatable classes.
     */
    var generateInterfaceStackAllocators = true

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

    /**
     * Java code inserted into a static { } block in NativeObject and each enum class. Can be used
     * to call a native lib loader.
     */
    var onClassLoad = ""

    private lateinit var nativeObject: JavaClass
    private val typeMap = mutableMapOf<String, JavaClass>()

    init {
        outputDirectory = "./generated/java"
    }

    override fun generate(model: IdlModel) {
        deleteDirectory(File(outputDirectory))

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
            staticCode = onClassLoad
        }
        createOutFileWriter(nativeObject.fileName).use { w ->
            nativeObject.generateSource(w) {
                append("""
                    protected long address = 0L;
                    protected boolean isStackAllocated = false;
                    
                    protected NativeObject(long address) {
                        this.address = address;
                    }
                    
                    public static NativeObject wrapPointer(long address) {
                        return new NativeObject(address);
                    }
                    
                    public long getAddress() {
                        return address;
                    }
                    
                    @FunctionalInterface
                    public interface Allocator<T> {
                        long on(T allocator, int alignment, int size);
                    }
                """.trimIndent().prependIndent("    ")).append('\n')
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
                    // no need to include static onClassLoad code here as it is inserted into NativeObject, which
                    // is the super class of all IdlInterfaces
                }
            }
            for (idlEn in model.getEnumsByPackage(idlPkg)) {
                typeMap[idlEn.name] = JavaClass(idlEn.name, true, idlPkg, packagePrefix).apply {
                    protectedDefaultContructor = false
                    generatePointerWrapMethods = false
                    staticCode = onClassLoad
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

        if (name in stackAllocatableClasses) {
            generateSizeOf(w)
            w.append("    // Placed Constructors\n\n")
            ctorFunctions.forEach { ctor ->
                generatePlacedConstructor(ctor, w)
            }
        }

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

    private fun generateSizeOf(w: Writer) {
        w.append("""
            private static native int __sizeOf();
            public static final int SIZEOF = __sizeOf();
            public static final int ALIGNOF = 8;
        """.trimIndent().prependIndent("    ")).append("\n\n")
    }

    private fun IdlInterface.generatePlacedConstructor(ctorFunc: IdlFunction, w: Writer) {
        val nativeToJavaParams = ctorFunc.parameters.zip(ctorFunc.parameters.map { JavaType(it.type) })
        var nativeArgs = nativeToJavaParams.joinToString { (nat, java) -> "${java.internalType} ${nat.name}" }
        var javaArgs = nativeToJavaParams.joinToString { (nat, java) -> "${java.javaType} ${nat.name}" }
        var callArgs = nativeToJavaParams.joinToString { (nat, java) -> java.unbox(nat.name, nat.isNullable(this, ctorFunc)) }

        if (nativeArgs.isNotEmpty()) {
            nativeArgs = ", $nativeArgs"
        }
        if (javaArgs.isNotEmpty()) {
            javaArgs = ", $javaArgs"
        }
        if (callArgs.isNotEmpty()) {
            callArgs = ", $callArgs"
        }

        val paramDocs = mutableMapOf<String, String>()
        nativeToJavaParams.forEach { (nat, java) ->
            paramDocs[nat.name] = makeTypeDoc(java, nat.decorators)
        }

        if (generateSimpleStackAllocators) {
            val pDocs = mutableMapOf("address" to "where the object is allocated")
            pDocs.putAll(paramDocs)
            generateJavadoc(pDocs, "", w)
            w.append("""
                public static $name malloc(long address$javaArgs) {
                    __placement_new_${ctorFunc.name}(address$callArgs);
                    $name mallocedObj = wrapPointer(address);
                    mallocedObj.isStackAllocated = true;
                    return mallocedObj;
                }
            """.trimIndent().prependIndent("    ")).append("\n\n")
        }
        if (generateInterfaceStackAllocators) {
            val pDocs = mutableMapOf(
                "allocator" to "object to use for allocation",
                "allocate" to "method to call on allocator to obtain the target address"
            )
            pDocs.putAll(paramDocs)
            generateJavadoc(pDocs, "", w)
            w.append("""
                public static <T> $name malloc(T allocator, Allocator<T> allocate$javaArgs) {
                    long address = allocate.on(allocator, ALIGNOF, SIZEOF); 
                    __placement_new_${ctorFunc.name}(address$callArgs);
                    $name mallocedObj = wrapPointer(address);
                    mallocedObj.isStackAllocated = true;
                    return mallocedObj;
                }
            """.trimIndent().prependIndent("    ")).append("\n\n")
        }
        w.append("    private static native void __placement_new_${ctorFunc.name}(long address$nativeArgs);\n\n")
    }

    private fun IdlInterface.generateConstructor(ctorFunc: IdlFunction, w: Writer) {
        val nativeToJavaParams = ctorFunc.parameters.zip(ctorFunc.parameters.map { JavaType(it.type) })
        val nativeArgs = nativeToJavaParams.joinToString { (nat, java) -> "${java.internalType} ${nat.name}" }
        val javaArgs = nativeToJavaParams.joinToString { (nat, java) -> "${java.javaType} ${nat.name}" }
        val callArgs = nativeToJavaParams.joinToString { (nat, java) -> java.unbox(nat.name, nat.isNullable(this, ctorFunc)) }

        val paramDocs = mutableMapOf<String, String>()
        nativeToJavaParams.forEach { (nat, java) ->
            paramDocs[nat.name] = makeTypeDoc(java, nat.decorators)
        }
        generateJavadoc(paramDocs, "", w)

        w.append("""
            public $name($javaArgs) {
                address = _${ctorFunc.name}($callArgs);
            }
            private static native long _${ctorFunc.name}($nativeArgs);
        """.trimIndent().prependIndent("    ")).append("\n\n")
    }

    private fun generateDestructor(w: Writer) {
        w.append("""
            public void destroy() {
                if (address == 0L) {
                    throw new IllegalStateException(this + " is already deleted");
                }
                if (isStackAllocated) {
                    throw new IllegalStateException(this + " is stack allocated and cannot be deleted");
                }
                _delete_native_instance(address);
                address = 0L;
            }
            private static native long _delete_native_instance(long address);
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

        val paramDocs = mutableMapOf<String, String>()
        nativeToJavaParams.forEach { (nat, java) ->
            paramDocs[nat.name] = makeTypeDoc(java, nat.decorators)
        }
        val returnDoc = if (returnType.idlType.isVoid) "" else makeTypeDoc(returnType, func.decorators)
        generateJavadoc(paramDocs, returnDoc, w)

        w.append("""
            public$staticMod ${returnType.javaType} ${func.name}($javaArgs) {
                ${returnType.boxedReturn("_${func.name}($callArgs)", "$name.${func.name}" in nullableReturnValues)};
            }
            private static native ${returnType.internalType} _${func.name}($nativeArgs);
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

        generateJavadoc(emptyMap(), makeTypeDoc(javaType, attrib.decorators), w)
        w.append("""
            public$staticMod ${javaType.javaType} $methodName($arrayModPub) {
                ${javaType.boxedReturn("_$methodName($addressCall$arrayCallMod)", attrib.isNullable(this))};
            }
            private static native ${javaType.internalType} _$methodName($addressSig$arrayModPriv);
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

        generateJavadoc(mapOf("value" to makeTypeDoc(javaType, attrib.decorators)), "", w)
        w.append("""
            public$staticMod void $methodName($arrayModPub${javaType.javaType} value) {
                _$methodName($addressCall$arrayCallMod, ${javaType.unbox("value", attrib.isNullable(this))});
            }
            private static native void _$methodName($nativeSig);
        """.trimIndent().prependIndent("    ")).append("\n\n")
    }

    private fun makeTypeDoc(javaType: JavaType, decorators: List<IdlDecorator> = emptyList()): String {
        val decoString = when {
            javaType.isIdlEnum -> " [enum]"
            decorators.isNotEmpty() -> " $decorators"
            else -> ""
        }
        val typeString = when {
            javaType.idlType.isComplexType -> "{@link ${javaType.idlType.typeName}}"
            else -> javaType.idlType.typeName
        }
        return "$typeString$decoString"
    }

    private fun generateJavadoc(paramDocs: Map<String, String>, returnDoc: String, w: Writer) {
        if (paramDocs.isNotEmpty() || returnDoc.isNotEmpty()) {
            w.append("    /**\n")
            if (paramDocs.isNotEmpty()) {
                val maxNameLen = paramDocs.keys.map { it.length }.maxOf { it }
                paramDocs.forEach { (name, doc) ->
                    w.append(String.format(Locale.ENGLISH, "     * @param %-${maxNameLen}s %s\n", name, doc))
                }
            }
            if (returnDoc.isNotEmpty()) {
                w.append("     * @return $returnDoc\n")
            }
            w.append("     */\n")
        }
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
        var staticCode = ""

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

            if (staticCode.isNotEmpty()) {
                w.write("    static {\n")
                staticCode.lines().forEach {
                    w.write("        ${it.trim()}\n")
                }
                w.write("    }\n\n")
            }

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
        val isIdlEnum: Boolean
            get() = typeMap[idlType.typeName]?.isEnum ?: false

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
                isIdlEnum -> {
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