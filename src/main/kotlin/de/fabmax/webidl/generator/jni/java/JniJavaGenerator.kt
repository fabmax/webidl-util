package de.fabmax.webidl.generator.jni.java

import de.fabmax.webidl.generator.CodeGenerator
import de.fabmax.webidl.generator.indent
import de.fabmax.webidl.generator.prependIndent
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
    val externallyAllocatableClasses = mutableSetOf<String>()

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
        nativeObject = JavaClass(NATIVE_OBJECT_NAME, false, "", packagePrefix).apply {
            protectedDefaultContructor = true
            generatePointerWrapMethods = false
            staticCode = onClassLoad
        }
        nativeObject.generateSource(createOutFileWriter(nativeObject.fileName)) {
            append("""
                protected long address = 0L;
                protected boolean isExternallyAllocated = false;
                
                protected NativeObject(long address) {
                    this.address = address;
                }
                
                public static NativeObject wrapPointer(long address) {
                    return new NativeObject(address);
                }
                
                protected void checkNotNull() {
                    if (address == 0L) {
                        throw new NullPointerException("Native address of " + this + " is 0");
                    }
                }
                
                public long getAddress() {
                    return address;
                }
                
                @Override
                public boolean equals(Object o) {
                    if (this == o) return true;
                    if (!(o instanceof NativeObject)) return false;
                    NativeObject that = (NativeObject) o;
                    return address == that.address;
                }
            
                @Override
                public int hashCode() {
                    return (int) (address ^ (address >>> 32));
                }
                
                @FunctionalInterface
                public interface Allocator<T> {
                    long on(T allocator, int alignment, int size);
                }
            """.trimIndent().prependIndent(4)).append('\n')
        }

        val jniThreadManager = JavaClass("JniThreadManager", false, "", packagePrefix).apply {
            protectedDefaultContructor = false
            generatePointerWrapMethods = false
            staticCode = onClassLoad
            importFqns += "java.util.concurrent.atomic.AtomicBoolean"
        }
        jniThreadManager.generateSource(createOutFileWriter(jniThreadManager.fileName)) {
            append("""
                private static AtomicBoolean isInitialized = new AtomicBoolean(false);
                private static boolean isInitSuccess = false;
                
                public static boolean init() {
                    if (!isInitialized.getAndSet(true)) {
                        isInitSuccess = _init();
                    }
                    return isInitSuccess;
                }
                private static native boolean _init();
            """.trimIndent().prependIndent(4))
        }

        val javaNativeRef = JavaClass("JavaNativeRef", false, "", packagePrefix).apply {
            protectedDefaultContructor = false
            generatePointerWrapMethods = false
            staticCode = onClassLoad
            superClass = nativeObject
        }

        createOutFileWriter(javaNativeRef.fileName).use { w ->
            javaNativeRef.generatePackage(w)
            javaNativeRef.generateImports(w)

            w.append("""
            public class JavaNativeRef<T> extends NativeObject {
                static {
                    ${javaNativeRef.staticCode}
                }
                
                private static native long _new_instance(Object javaRef);
                private static native void _delete_instance(long address);
                private static native Object _get_java_ref(long address);

                public static <T> JavaNativeRef<T> fromNativeObject(NativeObject nativeObj) {
                    return new JavaNativeRef<T>(nativeObj != null ? nativeObj.address : 0L);
                }

                protected JavaNativeRef(long address) {
                    super(address);
                }

                public JavaNativeRef(Object javaRef) {
                    address = _new_instance(javaRef);
                }
                
                @SuppressWarnings("unchecked")
                public T get() {
                    checkNotNull();
                    return (T) _get_java_ref(address);
                }
                
                public void destroy() {
                    checkNotNull();
                    _delete_instance(address);
                }
            }
            """.trimIndent())
        }
    }

    private fun makeClassMappings(model: IdlModel) {
        typeMap.clear()
        typeMap[nativeObject.name] = nativeObject

        // 1st pass collect all interface/class and enum types
        for (idlPkg in model.collectPackages()) {
            for (idlIf in model.getInterfacesByPackage(idlPkg)) {
                typeMap[idlIf.name] = JavaClass(idlIf.name, false, idlPkg, packagePrefix).apply {
                    protectedDefaultContructor = !idlIf.hasDefaultConstructor() && !idlIf.isCallback()
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
            if (idlIf.superInterfaces.isEmpty() && !idlIf.isCallback()) {
                imports += nativeObject.name
            }
            idlIf.functions.forEach { func ->
                if (func.returnType.isComplexType) {
                    imports += func.returnType.typeName
                } else if (func.returnType.isAnyOrVoidPtr) {
                    imports += nativeObject.name
                }
                func.parameters.forEach { param ->
                    if (param.type.isComplexType) {
                        imports += param.type.typeName
                    } else if (param.type.isAnyOrVoidPtr) {
                        imports += nativeObject.name
                    }
                }
            }
            idlIf.attributes.forEach { attrib ->
                if (attrib.type.isComplexType) {
                    imports += attrib.type.typeName
                } else if (attrib.type.isAnyOrVoidPtr) {
                    imports += nativeObject.name
                }
            }

            val javaClass = typeMap[idlIf.name] ?: throw IllegalStateException("Unknown idl type: ${idlIf.name}")
            when {
                idlIf.isCallback() -> {
                    // callback classes implement the type specified by JSImplementation decorator
                    val superType = idlIf.getDecoratorValue("JSImplementation", "")
                    javaClass.superClass = typeMap[superType] ?: throw IllegalStateException("Unknown JSImplementation type: $superType")
                    if (idlIf.superInterfaces.isNotEmpty()) {
                        System.err.println("warning: ${idlIf.name} is callback implementation of $superType and additionally implements ${idlIf.superInterfaces}")
                        System.err.println("warning: multiple super interfaces are not supported!")
                    }
                }
                idlIf.superInterfaces.isEmpty() -> {
                    // every generated class derives from NativeObject
                    javaClass.superClass = nativeObject
                }
                else -> {
                    if (idlIf.superInterfaces.size > 1) {
                        System.err.println("warning: ${idlIf.name} implements ${idlIf.superInterfaces}")
                        System.err.println("warning: multiple super interfaces are not supported!")
                    }
                    javaClass.superClass = typeMap[idlIf.superInterfaces[0]] ?: throw IllegalStateException("Unknown idl type: ${idlIf.superInterfaces[0]}")
                }
            }

            imports.forEach {
                javaClass.imports += typeMap[it] ?: throw IllegalStateException("Unknown idl type: $it")
            }
        }
    }

    private fun IdlInterface.isCallback(): Boolean {
        return hasDecorator("JSImplementation")
    }

    private fun IdlInterface.hasDefaultConstructor(): Boolean {
        return functions.any { it.name == name && it.parameters.isEmpty() }
    }

    private fun IdlModel.generatePackage(idlPkg: String) {
        getInterfacesByPackage(idlPkg).forEach { idlIf ->
            val javaClass = typeMap[idlIf.name] ?: throw IllegalStateException("Unknown idl type: $name")
            if (idlIf.isCallback()) {
                // generate callback class
                idlIf.generateCallback(javaClass)
            } else {
                // generate regular class
                idlIf.generate(javaClass)
            }
        }
        getEnumsByPackage(idlPkg).forEach { idlEn ->
            val javaClass = typeMap[idlEn.name] ?: throw IllegalStateException("Unknown idl type: $name")
            idlEn.generate(javaClass)
        }
    }

    private fun IdlInterface.generateCallback(javaClass: JavaClass) = javaClass.generateSource(createOutFileWriter(javaClass.path)) {
        append("""
            protected ${javaClass.name}() {
                address = _${javaClass.name}();
            }
            private native long _${javaClass.name}();
            
            // Destructor
        """.trimIndent().prependIndent(4)).append("\n\n")
        generateDestructor(this)

        val nonCtorFunctions = functions.filter { it.name != name }
        if (nonCtorFunctions.isNotEmpty()) {
            append("    // Functions\n\n")
            nonCtorFunctions.forEach { func ->
                val nativeToJavaParams = func.parameters.zip(func.parameters.map { JavaType(it.type) })
                val returnType = JavaType(func.returnType)
                val returnPrefix = if (func.returnType.isVoid) "" else "return "
                val returnSuffix = if (func.returnType.isComplexType || func.returnType.isAnyOrVoidPtr) ".getAddress()" else ""
                val internalParams = nativeToJavaParams.joinToString { (nat, java) -> "${java.internalType} ${nat.name}" }
                val params = nativeToJavaParams.joinToString { (nat, java) -> "${java.javaType} ${nat.name}" }
                val callParams = nativeToJavaParams.joinToString { (nat, java) ->
                    if (nat.type.isPrimitive || java.isIdlEnum) {
                        nat.name
                    } else {
                        "${java.javaType}.wrapPointer(${nat.name})"
                    }
                }

                val defaultReturnVal = if (func.returnType.isVoid) {
                    " "
                } else if (func.returnType.isPrimitive) {
                    val returnVal = when (func.returnType.typeName) {
                        "boolean" -> "false"
                        "float" -> "0.0f"
                        "double" -> "0.0"
                        "DOMString" -> "\"\""
                        else -> "0"
                    }
                    "\n${indent(24)}return $returnVal;\n${indent(20)}"
                } else {
                    "\n${indent(24)}return null;\n${indent(20)}"
                }

                val paramDocs = mutableMapOf<String, String>()
                nativeToJavaParams.forEach { (nat, java) ->
                    paramDocs[nat.name] = makeTypeDoc(java, nat.decorators)
                }
                val returnDoc = if (returnType.idlType.isVoid) "" else makeTypeDoc(returnType, func.decorators)

                append("""
                    /*
                     * Called from native code
                     */
                    private ${returnType.internalType} _${func.name}($internalParams) {
                        $returnPrefix${func.name}($callParams)$returnSuffix;
                    }""".trimIndent().prependIndent(4)).append("\n\n")
                    
                generateJavadoc(paramDocs, returnDoc, this)
                append("""
                    public ${returnType.javaType} ${func.name}($params) {$defaultReturnVal}
                """.trimIndent().prependIndent(4)).append("\n\n")
            }
        }
    }

    private fun IdlInterface.generate(javaClass: JavaClass) = javaClass.generateSource(createOutFileWriter(javaClass.path)) {
        val ctorFunctions = functions.filter { it.name == name }

        if (name in externallyAllocatableClasses) {
            generateSizeOf(this)
            append("    // Placed Constructors\n\n")
            ctorFunctions.forEach { ctor ->
                generatePlacedConstructor(ctor, this)
            }
        }

        if (ctorFunctions.isNotEmpty()) {
            append("    // Constructors\n\n")
            ctorFunctions.forEach { ctor ->
                generateConstructor(ctor, this)
            }
        }

        if (!hasDecorator("NoDelete")) {
            append("    // Destructor\n\n")
            generateDestructor(this)
        }

        if (attributes.isNotEmpty()) {
            append("    // Attributes\n\n")
            attributes.forEach { attrib ->
                generateGet(attrib, this)
                if (!attrib.isReadonly) {
                    generateSet(attrib, this)
                }
            }
        }

        val nonCtorFunctions = functions.filter { it.name != name }
        if (nonCtorFunctions.isNotEmpty()) {
            append("    // Functions\n\n")
            nonCtorFunctions.forEach { func ->
                generateFunction(func, this)
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
        """.trimIndent().prependIndent(4)).append("\n\n")
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
            val pDocs = mutableMapOf("address" to "Pre-allocated memory, where the object is created.")
            pDocs.putAll(paramDocs)
            generateJavadoc(pDocs, "Stack allocated object of $name", w)
            w.append("""
                public static $name createAt(long address$javaArgs) {
                    __placement_new_${ctorFunc.name}(address$callArgs);
                    $name createdObj = wrapPointer(address);
                    createdObj.isExternallyAllocated = true;
                    return createdObj;
                }
            """.trimIndent().prependIndent(4)).append("\n\n")
        }
        if (generateInterfaceStackAllocators) {
            val pDocs = mutableMapOf(
                "<T>" to "Allocator class, e.g. LWJGL's MemoryStack.",
                "allocator" to "Object to use for allocation, e.g. an instance of LWJGL's MemoryStack.",
                "allocate" to "Method to call on allocator to obtain the target address, e.g. MemoryStack::nmalloc."
            )
            pDocs.putAll(paramDocs)
            generateJavadoc(pDocs, "Stack allocated object of $name", w)
            w.append("""
                public static <T> $name createAt(T allocator, Allocator<T> allocate$javaArgs) {
                    long address = allocate.on(allocator, ALIGNOF, SIZEOF); 
                    __placement_new_${ctorFunc.name}(address$callArgs);
                    $name createdObj = wrapPointer(address);
                    createdObj.isExternallyAllocated = true;
                    return createdObj;
                }
            """.trimIndent().prependIndent(4)).append("\n\n")
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
        """.trimIndent().prependIndent(4)).append("\n\n")
    }

    private fun generateDestructor(w: Writer) {
        w.append("""
            public void destroy() {
                if (address == 0L) {
                    throw new IllegalStateException(this + " is already deleted");
                }
                if (isExternallyAllocated) {
                    throw new IllegalStateException(this + " is externally allocated and cannot be manually destroyed");
                }
                _delete_native_instance(address);
                address = 0L;
            }
            private static native long _delete_native_instance(long address);
        """.trimIndent().prependIndent(4)).append("\n\n")
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
        val nullCheck = if (func.isStatic) "" else  "\n${indent(16)}checkNotNull();"

        val paramDocs = mutableMapOf<String, String>()
        nativeToJavaParams.forEach { (nat, java) ->
            paramDocs[nat.name] = makeTypeDoc(java, nat.decorators)
        }
        val returnDoc = if (returnType.idlType.isVoid) "" else makeTypeDoc(returnType, func.decorators)
        generateJavadoc(paramDocs, returnDoc, w)

        w.append("""
            public$staticMod ${returnType.javaType} ${func.name}($javaArgs) {$nullCheck
                ${returnType.boxedReturn("_${func.name}($callArgs)")};
            }
            private static native ${returnType.internalType} _${func.name}($nativeArgs);
        """.trimIndent().prependIndent(4)).append("\n\n")
    }

    private fun generateGet(attrib: IdlAttribute, w: Writer) {
        val javaType = JavaType(attrib.type)
        val methodName = "get${firstCharToUpper(attrib.name)}"

        val staticMod = if (attrib.isStatic) " static" else ""
        val arrayModPriv = if (attrib.type.isArray) ", int index" else ""
        val arrayModPub = if (attrib.type.isArray) "int index" else ""
        val arrayCallMod = if (attrib.type.isArray) ", index" else ""
        val addressSig = if (attrib.isStatic) "" else "long address"
        val addressCall = if (attrib.isStatic) "" else "address"
        val nullCheck = if (attrib.isStatic) "" else  "\n${indent(16)}checkNotNull();"

        val paramDocs = mutableMapOf<String, String>()
        if (attrib.type.isArray) {
            paramDocs["index"] = "Array index"
        }
        generateJavadoc(paramDocs, makeTypeDoc(javaType, attrib.decorators), w)
        w.append("""
            public$staticMod ${javaType.javaType} $methodName($arrayModPub) {$nullCheck
                ${javaType.boxedReturn("_$methodName($addressCall$arrayCallMod)")};
            }
            private static native ${javaType.internalType} _$methodName($addressSig$arrayModPriv);
        """.trimIndent().prependIndent(4)).append("\n\n")
    }

    private fun IdlInterface.generateSet(attrib: IdlAttribute, w: Writer) {
        val javaType = JavaType(attrib.type)
        val methodName = "set${firstCharToUpper(attrib.name)}"

        val staticMod = if (attrib.isStatic) " static" else ""
        val arrayModPub = if (attrib.type.isArray) "int index, " else ""
        val arrayCallMod = if (attrib.type.isArray) ", index" else ""
        val addressCall = if (attrib.isStatic) "" else "address"
        val nullCheck = if (attrib.isStatic) "" else  "\n${indent(16)}checkNotNull();"

        var nativeSig = if (attrib.isStatic) "" else "long address"
        if (attrib.type.isArray) {
            if (nativeSig.isNotEmpty()) { nativeSig += ", "}
            nativeSig += "int index"
        }
        if (nativeSig.isNotEmpty()) { nativeSig += ", "}
        nativeSig += "${javaType.internalType} value"

        val paramDocs = mutableMapOf<String, String>()
        if (attrib.type.isArray) {
            paramDocs["index"] = "Array index"
        }
        paramDocs["value"] = makeTypeDoc(javaType, attrib.decorators)
        generateJavadoc(paramDocs, "", w)
        w.append("""
            public$staticMod void $methodName($arrayModPub${javaType.javaType} value) {$nullCheck
                _$methodName($addressCall$arrayCallMod, ${javaType.unbox("value", attrib.isNullable(this))});
            }
            private static native void _$methodName($nativeSig);
        """.trimIndent().prependIndent(4)).append("\n\n")
    }

    private fun makeTypeDoc(javaType: JavaType, decorators: List<IdlDecorator> = emptyList()): String {
        val decoString = when {
            javaType.isIdlEnum -> " [enum]"
            decorators.isNotEmpty() -> " $decorators"
            else -> ""
        }
        val typeString = when {
            javaType.idlType.isComplexType -> "WebIDL type: {@link ${javaType.idlType.typeName}}"
            else -> "WebIDL type: ${javaType.idlType.typeName}"
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

    private fun IdlEnum.generate(javaClass: JavaClass) = javaClass.generateSource(createOutFileWriter(javaClass.path)) {
        unprefixedValues.forEach { enumVal ->
            write("    public static final int $enumVal = _get$enumVal();\n")
        }
        write("\n")
        unprefixedValues.forEach { enumVal ->
            write("    private static native int _get$enumVal();\n")
        }
    }

    private fun JavaType(idlType: IdlType) = JavaType(idlType, typeMap[idlType.typeName]?.isEnum ?: false)

    companion object {
        const val NATIVE_OBJECT_NAME = "NativeObject"

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