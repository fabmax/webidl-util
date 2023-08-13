package de.fabmax.webidl.generator.jni.java

import de.fabmax.webidl.generator.CodeGenerator
import de.fabmax.webidl.generator.indent
import de.fabmax.webidl.generator.prependIndent
import de.fabmax.webidl.model.*
import de.fabmax.webidl.parser.*
import java.io.File
import java.io.Writer
import java.util.*

class JniJavaGenerator : CodeGenerator() {

    var packagePrefix = ""

    /**
     * If true simple address-based allocation methods are generated for stack allocatable classes.
     */
    var generateSimpleStackAllocators = true

    /**
     * If true interface-based allocation methods are generated for stack allocatable classes.
     */
    var generateInterfaceStackAllocators = true

    /**
     * Java code inserted into a static { } block in NativeObject and each enum class. Can be used
     * to call a native lib loader.
     */
    var onClassLoad = ""

    /**
     * List of directories to traverse for .cpp / .h files to grab comments from.
     */
    val parseCommentsFromDirectories = mutableListOf<String>()

    private lateinit var nativeObject: JavaClass
    private lateinit var platformChecks: JavaClass
    private val typeMap = mutableMapOf<String, JavaType>()
    private lateinit var model: IdlModel

    private val comments = mutableMapOf<String, CppComments>()

    private var classesWithComments = 0
    private var classesWithoutComments = 0
    private var methodsWithComments = 0
    private var methodsWithoutComments = 0
    private var attributesWithComments = 0
    private var attributesWithoutComments = 0
    private var enumsWithComments = 0
    private var enumsWithoutComments = 0

    init {
        outputDirectory = "./generated/java"
    }

    override fun generate(model: IdlModel) {
        this.model = model
        DoxygenToJavadoc.model = model
        DoxygenToJavadoc.packagePrefix = packagePrefix

        deleteDirectory(File(outputDirectory))
        parseCppComments()

        generateFrameworkClasses(model)
        makeClassMappings(model)

        for (pkg in model.collectPackages()) {
            model.generatePackage(pkg)
        }

        println("Done generating JNI classes!")
        println("  %4d classes, (%.1f %% with javadoc)".format(classesWithComments + classesWithoutComments,
            classesWithComments * 100f / (classesWithComments + classesWithoutComments)))
        println("  %4d methods, (%.1f %% with javadoc)".format(methodsWithComments + methodsWithoutComments,
            methodsWithComments * 100f / (methodsWithComments + methodsWithoutComments)))
        println("  %4d attributes, (%.1f %% with javadoc)".format(attributesWithComments + attributesWithoutComments,
            attributesWithComments * 100f / (attributesWithComments + attributesWithoutComments)))
        println("  %4d enum values, (%.1f %% with javadoc)".format(enumsWithComments + enumsWithoutComments,
            enumsWithComments * 100f / (enumsWithComments + enumsWithoutComments)))
    }

    private fun parseCppComments() {
        parseCommentsFromDirectories.map { File(it) }.forEach { f ->
            if (!f.exists()) {
                System.err.println("Comment path does not exist: ${f.absolutePath}")
            } else {
                println("Parsing comments from .h files in $f")
                CppCommentParser.parseComments(f).forEach { comments[it.className] = it }
            }
        }
    }

    private fun generateFrameworkClasses(model: IdlModel) {
        nativeObject = generateNativeObject(model)
        platformChecks = generatePlatformChecks(model)
        generateJavaNativeRef(model, nativeObject)
    }

    private fun getPlatformMask(element: IdlDecoratedElement): Int {
        var mask = 0
        element.getPlatforms().forEach {
            val bit = platformBits.getOrDefault(it, PLATFORM_BIT_OTHER)
            if (bit == PLATFORM_BIT_OTHER) {
                System.err.println("Unrecognized platform name: $it")
            }
            mask = mask or bit
        }
        return mask
    }

    private fun generateCheckPlatform(element: IdlDecoratedElement, javaClass: JavaClass): String {
        if (!element.hasDecorator(IdlDecorator.PLATFORMS)) {
            return ""
        }
        return "\n${indent(16)}${platformChecks.name}.requirePlatform(${getPlatformMask(element)}, \"${javaClass.fqn}\");"
    }

    private fun makeClassMappings(model: IdlModel) {
        typeMap.clear()
        typeMap[nativeObject.name] = nativeObject

        // 1st pass collect all interface/class and enum types
        for (idlPkg in model.collectPackages()) {
            for (idlIf in model.getInterfacesByPackage(idlPkg)) {
                typeMap[idlIf.name] = JavaClass(idlIf, idlPkg, packagePrefix).apply {
                    protectedDefaultContructor = !idlIf.hasDefaultConstructor() && !idlIf.isCallback()
                    generatePointerWrapMethods = true
                    comments = this@JniJavaGenerator.comments[idlIf.name] as? CppClassComments
                    // no need to include static onClassLoad code here as it is inserted into NativeObject, which
                    // is the super class of all IdlInterfaces

                    if (idlIf.hasDecorator(IdlDecorator.PLATFORMS)
                        || idlIf.attributes.any { it.hasDecorator(IdlDecorator.PLATFORMS) }
                        || idlIf.functions.any { it.hasDecorator(IdlDecorator.PLATFORMS) }
                    ) {
                        imports += platformChecks
                    }
                    if (idlIf.hasDecorator(IdlDecorator.PLATFORMS)) {
                        staticCode = "PlatformChecks.requirePlatform(${getPlatformMask(idlIf)}, \"$fqn\");"
                    }
                }
            }
            for (idlEn in model.getEnumsByPackage(idlPkg)) {
                typeMap[idlEn.name] = JavaEnumClass(idlEn, idlPkg, packagePrefix).apply {
                    comments = this@JniJavaGenerator.comments[idlEn.name] as? CppEnumComments
                    staticCode = onClassLoad
                    if (idlEn.hasDecorator(IdlDecorator.PLATFORMS)) {
                        if (staticCode.isNotEmpty()) {
                            staticCode += "\n"
                        }
                        staticCode += "${platformChecks.fqn}.requirePlatform(${getPlatformMask(idlEn)}, \"$fqn\");"
                    }
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

            val javaType = typeMap[idlIf.name] ?: throw IllegalStateException("Unknown idl type: ${idlIf.name}")
            val javaClass = javaType as? JavaClass ?: throw IllegalStateException("Type is not a class: ${idlIf.name}")

            when {
                idlIf.isCallback() -> {
                    // callback classes implement the type specified by JSImplementation decorator
                    val superType = idlIf.getDecoratorValue("JSImplementation", "")
                    javaClass.superClass = (typeMap[superType] as? JavaClass) ?: throw IllegalStateException("Unknown JSImplementation type: $superType")
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
                    javaClass.superClass = (typeMap[idlIf.superInterfaces[0]] as? JavaClass) ?: throw IllegalStateException("Unknown idl type: ${idlIf.superInterfaces[0]}")
                }
            }

            imports.forEach {
                javaClass.imports += typeMap[it] ?: throw IllegalStateException("Unknown idl type: $it")
            }
        }
    }

    private fun IdlInterface.isCallback(): Boolean {
        return hasDecorator(IdlDecorator.JS_IMPLEMENTATION)
    }

    private fun IdlInterface.hasDefaultConstructor(): Boolean {
        return functions.any { it.name == name && it.parameters.isEmpty() }
    }

    private fun IdlModel.generatePackage(idlPkg: String) {
        getInterfacesByPackage(idlPkg).forEach { idlIf ->
            val javaClass = typeMap[idlIf.name] as? JavaClass ?: throw IllegalStateException("Unknown idl type: $name")
            if (idlIf.isCallback()) {
                // generate callback class
                idlIf.generateCallback(javaClass)
            } else {
                // generate regular class
                idlIf.generate(javaClass)
            }
        }
        getEnumsByPackage(idlPkg).forEach { idlEn ->
            val javaClass = typeMap[idlEn.name] as? JavaEnumClass ?: throw IllegalStateException("Unknown idl type: $name")
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
                    when {
                        nat.type.isPrimitive -> nat.name
                        java.isIdlEnum -> "${java.javaType}.forValue(${nat.name})"
                        else -> "${java.javaType}.wrapPointer(${nat.name})"
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

        if (javaClass.comments != null) {
            classesWithComments++
        } else {
            classesWithoutComments++
        }

        if (hasDecorator(IdlDecorator.STACK_ALLOCATABLE)) {
            append("    // Placed Constructors\n\n")
            ctorFunctions.forEach { ctor ->
                generatePlacedConstructor(javaClass, ctor, this)
            }
        }

        if (ctorFunctions.isNotEmpty()) {
            append("    // Constructors\n\n")
            ctorFunctions.forEach { ctor ->
                generateConstructor(javaClass, ctor, this)
            }
        }

        if (!hasDecorator(IdlDecorator.NO_DELETE)) {
            append("    // Destructor\n\n")
            generateDestructor(this)
        }

        if (attributes.isNotEmpty()) {
            append("    // Attributes\n\n")
            attributes.forEach { attrib ->
                generateGet(javaClass, attrib, this)
                if (!attrib.isReadonly) {
                    generateSet(javaClass, attrib, this)
                }
            }
        }

        val nonCtorFunctions = functions.filter { it.name != name }
        if (nonCtorFunctions.isNotEmpty()) {
            append("    // Functions\n\n")
            nonCtorFunctions.forEach { func ->
                generateFunction(javaClass, func, this)
            }
        }
    }

    private fun IdlFunctionParameter.isNullable(): Boolean {
        return hasDecorator(IdlDecorator.NULLABLE)
    }

    private fun IdlAttribute.isNullable(): Boolean {
        return hasDecorator(IdlDecorator.NULLABLE)
    }

    private fun IdlInterface.generatePlacedConstructor(javaClass: JavaClass, ctorFunc: IdlFunction, w: Writer) {
        val nativeToJavaParams = ctorFunc.parameters.zip(ctorFunc.parameters.map { JavaType(it.type) })
        var nativeArgs = nativeToJavaParams.joinToString { (nat, java) -> "${java.internalType} ${nat.name}" }
        var javaArgs = nativeToJavaParams.joinToString { (nat, java) -> "${java.javaType} ${nat.name}" }
        var callArgs = nativeToJavaParams.joinToString { (nat, java) -> java.unbox(nat.name, nat.isNullable()) }
        val platformCheck = generateCheckPlatform(ctorFunc, javaClass)

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
                public static $name createAt(long address$javaArgs) {$platformCheck
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

    private fun IdlInterface.generateConstructor(javaClass: JavaClass, ctorFunc: IdlFunction, w: Writer) {
        val nativeToJavaParams = ctorFunc.parameters.zip(ctorFunc.parameters.map { JavaType(it.type) })
        val nativeArgs = nativeToJavaParams.joinToString { (nat, java) -> "${java.internalType} ${nat.name}" }
        val javaArgs = nativeToJavaParams.joinToString { (nat, java) -> "${java.javaType} ${nat.name}" }
        val callArgs = nativeToJavaParams.joinToString { (nat, java) -> java.unbox(nat.name, nat.isNullable()) }
        val platformCheck = generateCheckPlatform(ctorFunc, javaClass)

        generateFunctionComment(javaClass, ctorFunc, w)
        w.append("""
            public $name($javaArgs) {$platformCheck
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

    private fun generateFunction(javaClass: JavaClass, func: IdlFunction, w: Writer) {
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
            callArgs += nativeToJavaParams.joinToString { (nat, java) -> java.unbox(nat.name, nat.isNullable()) }
        }
        val nullCheck = if (func.isStatic) "" else "\n${indent(16)}checkNotNull();"
        val platformCheck = generateCheckPlatform(func, javaClass)

        generateFunctionComment(javaClass, func, w)
        w.append("""
            public$staticMod ${returnType.javaType} ${func.name}($javaArgs) {$nullCheck$platformCheck
                ${returnType.boxedReturn("_${func.name}($callArgs)")};
            }
            private static native ${returnType.internalType} _${func.name}($nativeArgs);
        """.trimIndent().prependIndent(4)).append("\n\n")
    }

    private fun generateFunctionComment(javaClass: JavaClass, func: IdlFunction, w: Writer) {
        javaClass.getMethodComment(func)?.let {
            // we got a doc comment string from parsed c++ header
            methodsWithComments++
            val comment = DoxygenToJavadoc.makeJavadocString(it.comment ?: "", func.parentInterface, func)
            w.write(comment.prependIndent(4))
            w.write("\n")
            if (comment.contains("@deprecated")) {
                w.write("    @Deprecated\n")
            }

        } ?: run {
            // no doc string available, generate some minimal type info
            methodsWithoutComments++
            val nativeToJavaParams = func.parameters.zip(func.parameters.map { JavaType(it.type) })
            val returnType = JavaType(func.returnType)

            val paramDocs = mutableMapOf<String, String>()
            nativeToJavaParams.forEach { (nat, java) ->
                paramDocs[nat.name] = makeTypeDoc(java, nat.decorators)
            }
            val returnDoc = if (returnType.idlType.isVoid) "" else makeTypeDoc(returnType, func.decorators)
            generateJavadoc(paramDocs, returnDoc, w)
        }
    }

    private fun generateAttributeComment(comment: CppAttributeComment, attr: IdlAttribute, w: Writer) {
        val docStr = DoxygenToJavadoc.makeJavadocString(comment.comment ?: "", attr.parentInterface, null)
        w.write(docStr.prependIndent(4))
        w.write("\n")
        if (docStr.contains("@deprecated")) {
            w.write("    @Deprecated\n")
        }
    }

    private fun generateGet(javaClass: JavaClass, attrib: IdlAttribute, w: Writer) {
        val javaType = JavaType(attrib.type)
        val methodName = "get${firstCharToUpper(attrib.name)}"

        val staticMod = if (attrib.isStatic) " static" else ""
        val arrayModPriv = if (attrib.type.isArray) ", int index" else ""
        val arrayModPub = if (attrib.type.isArray) "int index" else ""
        val arrayCallMod = if (attrib.type.isArray) ", index" else ""
        val addressSig = if (attrib.isStatic) "" else "long address"
        val addressCall = if (attrib.isStatic) "" else "address"
        val nullCheck = if (attrib.isStatic) "" else  "\n${indent(16)}checkNotNull();"
        val platformCheck = generateCheckPlatform(attrib, javaClass)

        val cppComment = javaClass.getAttributeComment(attrib)
        if (cppComment != null) {
            attributesWithComments++
            generateAttributeComment(cppComment, attrib, w)
        } else {
            // no doc string available, generate some minimal type info
            attributesWithoutComments++
            val paramDocs = mutableMapOf<String, String>()
            if (attrib.type.isArray) {
                paramDocs["index"] = "Array index"
            }
            generateJavadoc(paramDocs, makeTypeDoc(JavaType(attrib.type), attrib.decorators), w)
        }

        w.append("""
            public$staticMod ${javaType.javaType} $methodName($arrayModPub) {$nullCheck$platformCheck
                ${javaType.boxedReturn("_$methodName($addressCall$arrayCallMod)")};
            }
            private static native ${javaType.internalType} _$methodName($addressSig$arrayModPriv);
        """.trimIndent().prependIndent(4)).append("\n\n")
    }

    private fun generateSet(javaClass: JavaClass, attrib: IdlAttribute, w: Writer) {
        val javaType = JavaType(attrib.type)
        val methodName = "set${firstCharToUpper(attrib.name)}"

        val staticMod = if (attrib.isStatic) " static" else ""
        val arrayModPub = if (attrib.type.isArray) "int index, " else ""
        val arrayCallMod = if (attrib.type.isArray) ", index" else ""
        val addressCall = if (attrib.isStatic) "" else "address"
        val nullCheck = if (attrib.isStatic) "" else  "\n${indent(16)}checkNotNull();"
        val platformCheck = generateCheckPlatform(attrib, javaClass)

        var nativeSig = if (attrib.isStatic) "" else "long address"
        if (attrib.type.isArray) {
            if (nativeSig.isNotEmpty()) { nativeSig += ", "}
            nativeSig += "int index"
        }
        if (nativeSig.isNotEmpty()) { nativeSig += ", "}
        nativeSig += "${javaType.internalType} value"

        val cppComment = javaClass.getAttributeComment(attrib)
        if (cppComment != null) {
            generateAttributeComment(cppComment, attrib, w)
        } else {
            val paramDocs = mutableMapOf<String, String>()
            if (attrib.type.isArray) {
                paramDocs["index"] = "Array index"
            }
            paramDocs["value"] = makeTypeDoc(javaType, attrib.decorators)
            generateJavadoc(paramDocs, "", w)
        }

        w.append("""
            public$staticMod void $methodName($arrayModPub${javaType.javaType} value) {$nullCheck$platformCheck
                _$methodName($addressCall$arrayCallMod, ${javaType.unbox("value", attrib.isNullable())});
            }
            private static native void _$methodName($nativeSig);
        """.trimIndent().prependIndent(4)).append("\n\n")
    }

    private fun makeTypeDoc(javaTypeMapping: JavaTypeMapping, decorators: List<IdlDecorator> = emptyList()): String {
        val decoString = when {
            javaTypeMapping.isIdlEnum -> " [enum]"
            decorators.isNotEmpty() -> " $decorators"
            else -> ""
        }
        val typeString = when {
            javaTypeMapping.idlType.isComplexType -> "WebIDL type: {@link ${javaTypeMapping.idlType.typeName}}"
            else -> "WebIDL type: ${javaTypeMapping.idlType.typeName}"
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

    private fun IdlEnum.generate(javaEnum: JavaEnumClass) {
        enumsWithComments += unprefixedValues.count { javaEnum.comments?.enumValues?.get(it)?.comment != null }
        javaEnum.generateSource(createOutFileWriter(javaEnum.path))
    }

    private fun JavaType(idlType: IdlType) = JavaTypeMapping(idlType, typeMap[idlType.typeName] is JavaEnumClass)

    companion object {
        const val PLATFORM_CHECKS_NAME = "PlatformChecks"
        const val NATIVE_OBJECT_NAME = "NativeObject"

        const val PLATFORM_BIT_WINDOWS = 1
        const val PLATFORM_BIT_LINUX = 2
        const val PLATFORM_BIT_MACOS = 4
        const val PLATFORM_BIT_OTHER = 0x80000000.toInt()

        val platformBits = mapOf(
            PLATFORM_NAME_WINDOWS to PLATFORM_BIT_WINDOWS,
            PLATFORM_NAME_LINUX to PLATFORM_BIT_LINUX,
            PLATFORM_NAME_MACOS to PLATFORM_BIT_MACOS,
        )

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