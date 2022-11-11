package de.fabmax.webidl.generator.jni.nat

import de.fabmax.webidl.generator.CodeGenerator
import de.fabmax.webidl.generator.prependIndent
import de.fabmax.webidl.model.*
import java.io.Writer

class JniNativeGenerator : CodeGenerator() {

    var glueFileName = "glue.h"
    var packagePrefix = ""

    val externallyAllocatableClasses = mutableSetOf<String>()

    private lateinit var model: IdlModel

    init {
        outputDirectory = "./generated/native"
    }

    override fun generate(model: IdlModel) {
        this.model = model
        createOutFileWriter(glueFileName).use {
            model.generateGlueCpp(it)
        }
    }

    private fun IdlModel.generateGlueCpp(w: Writer) {
        w.append("""
            /*
             * JNI glue code. You should not edit this file.
             * Generated from WebIDL model '$name' by webidl-util.
             */
            #include <jni.h>
        """.trimIndent()).append("\n\n")

        val callbackGenerator = CallbackGenerator(this)
        callbackGenerator.generateJniThreadManager(w)
        callbackGenerator.generateCallbackClasses(w)

        w.append("extern \"C\" {\n")

        // generate bindings for JniThreadManager and JavaNativeRef (is always included and not part of the IDL file)
        generateJniSupportBindings(w)

        collectPackages().forEach { pkg ->
            getInterfacesByPackage(pkg).forEach {
                if (it.hasDecorator("JSImplementation")) {
                    it.generateCallbackInterface(w)
                } else {
                    it.generate(w)
                }
            }
            getEnumsByPackage(pkg).forEach { it.generate(w) }
        }
        w.write("\n} // /extern \"C\"\n")
    }

    private fun generateJniSupportBindings(w: Writer) {
        w.append('\n').append("""
            // JniThreadManager
            JNIEXPORT jboolean JNICALL ${nativeFunName("", "JniThreadManager", "init")}(JNIEnv* env, jclass) {
                return (jboolean) JniThreadManager::init(env);
            }
            JNIEXPORT void JNICALL ${nativeFunName("", "JniThreadManager", "delete_native_instance")}(JNIEnv*, jclass, jlong address) {
                delete (JniThreadManager*) address;
            }
            // JavaNativeRef
            JNIEXPORT jlong JNICALL ${nativeFunName("", "JavaNativeRef", "new_instance")}(JNIEnv* env, jclass, jobject javaRef) {
                return (jlong) new JavaNativeRef(env, javaRef);
            }
            JNIEXPORT void JNICALL ${nativeFunName("", "JavaNativeRef", "delete_instance")}(JNIEnv*, jclass, jlong address) {
                delete (JavaNativeRef*) address;
            }
            JNIEXPORT jobject JNICALL ${nativeFunName("", "JavaNativeRef", "get_java_ref")}(JNIEnv*, jclass, jlong address) {
                return ((JavaNativeRef*) address)->javaGlobalRef;
            }
        """.trimIndent()).append('\n')
    }

    private fun IdlEnum.generate(w: Writer) {
        w.write("\n// $name\n")
        values.zip(unprefixedValues).forEach { (prefixedVal, valName) ->
            w.append("""
                JNIEXPORT jint JNICALL ${nativeFunName(sourcePackage, name, "get$valName")}(JNIEnv*, jclass) {
                    return $prefixedVal;
                }
            """.trimIndent()).append('\n')
        }
    }

    private fun IdlInterface.generateCallbackInterface(w: Writer) {
        w.write("\n// $name\n")
        // callback interfaces are only mapped with their default constructor and destructor
        val natType = getNativeType(model)
        w.append("""
            JNIEXPORT jlong JNICALL ${nativeFunName(sourcePackage, name, name)}(JNIEnv* env, jobject obj) {
                return (jlong) new $name(env, obj);
            }
            JNIEXPORT void JNICALL ${nativeFunName(sourcePackage, name, "delete_native_instance")}(JNIEnv*, jclass, jlong address) {
                delete ${natType.castJniToNative("address")};
            }
        """.trimIndent()).append("\n")
    }

    private fun IdlInterface.generate(w: Writer) {
        w.write("\n// $name\n")
        if (name in externallyAllocatableClasses) {
            generateSizeOf(w)
            val ctors = functions.filter { it.name == name }
            val isOverloaded = ctors.size > 1
            ctors.forEach { ctor ->
                generatePlacedCtor(ctor, isOverloaded, w)
            }
        }
        functions.forEach { func ->
            val isOverloaded = functions.filter { it.name == func.name }.count() > 1
            if (func.name == name) {
                generateCtor(func, isOverloaded, w)
            } else {
                generateFunction(func, isOverloaded, w)
            }
        }
        if (!hasDecorator("NoDelete")) {
            generateDtor(w)
        }
        attributes.forEach {
            generateGet(it, w)
            if (!it.isReadonly) {
                generateSet(it, w)
            }
        }
    }

    private fun IdlInterface.generateFunction(func: IdlFunction, isOverloaded: Boolean, w: Writer) {
        w.nativeFunc {
            val ifPrefix = getDecoratorValue("Prefix", "")
            val natReturnType = func.getNativeType(model)
            val suffix = func.getFunctionSuffix(isOverloaded)
            val funcArgs = func.parameters.joinToString(", ") { it.getNativeType(model).castJniToNative(it.name) }

            returnType = natReturnType.jniType()
            functionName = nativeFunName(sourcePackage, name, func.name, suffix)
            isReceivingAddress = !func.isStatic
            isUsingEnv = func.parameters.any { it.type.isString } || func.returnType.isString
            extraArgs = generateFuncArgs(func)

            val callTarget = if (!func.isStatic) {
                body += "$ifPrefix$name* self = ($ifPrefix$name*) ${NativeFuncRenderer.ADDRESS};\n"
                "self->"
            } else {
                "$ifPrefix$name::"
            }

            if (natReturnType.idlType.isVoid) {
                body += "$callTarget${func.name}($funcArgs);\n"
            } else {
                var returnVal = "$callTarget${func.name}($funcArgs)"
                if (natReturnType.isValue) {
                    // We can't pass value objects via JNI, use a static variable to cache the value and return a
                    // pointer to that one. Initialize the cache variable with returnVal to avoid errors in case the
                    // type has no default constructor.
                    body += "static thread_local ${natReturnType.jniTypeName} _cache = $returnVal;\n"
                    body += "_cache = $returnVal;\n"
                    returnVal = "_cache"
                }
                body += "return ${natReturnType.castNativeToJni(returnVal)};\n"
            }
        }
    }

    private fun IdlInterface.generatePlacedCtor(func: IdlFunction, isOverloaded: Boolean, w: Writer) {
        w.nativeFunc {
            val natType = getNativeType(model)
            val suffix = func.getFunctionSuffix(isOverloaded, true).replace("__", "__J")
            val ctorArgs = func.parameters.joinToString(", ") { it.getNativeType(model).castJniToNative(it.name) }

            returnType = "jlong"
            functionName = nativeFunName(sourcePackage, name, "_placement_new_${func.name}", suffix)
            isUsingEnv = func.parameters.any { it.type.isString }
            isReceivingAddress = false
            extraArgs = ", jlong _placement_address" + generateFuncArgs(func)
            body = "return (jlong) new((void*)_placement_address) ${natType.jniTypeName}($ctorArgs);"
        }
    }

    private fun IdlInterface.generateCtor(func: IdlFunction, isOverloaded: Boolean, w: Writer) {
        w.nativeFunc {
            val natType = getNativeType(model)
            val suffix = func.getFunctionSuffix(isOverloaded, true)
            val ctorArgs = func.parameters.joinToString(", ") { it.getNativeType(model).castJniToNative(it.name) }

            returnType = "jlong"
            functionName = nativeFunName(sourcePackage, name, func.name, suffix)
            isUsingEnv = func.parameters.any { it.type.isString }
            isReceivingAddress = false
            extraArgs = generateFuncArgs(func)
            body = "return (jlong) new ${natType.jniTypeName}($ctorArgs);"
        }
    }

    private fun generateFuncArgs(func: IdlFunction): String {
        var args = func.parameters.joinToString(", ") {
            it.getNativeType(model).jniType() + " ${it.name}"
        }
        if (args.isNotEmpty()) {
            args = ", $args"
        }
        return args
    }

    private fun IdlInterface.generateDtor(w: Writer) {
        val natType = getNativeType(model)
        w.nativeFunc {
            returnType = "void"
            functionName = nativeFunName(sourcePackage, name, "delete_native_instance")
            body = "delete ${natType.castJniToNative(NativeFuncRenderer.ADDRESS)};"
        }
    }

    private fun IdlInterface.generateGet(attrib: IdlAttribute, w: Writer) {
        w.nativeFunc {
            val ifPrefix = getDecoratorValue("Prefix", "")
            val natType = attrib.getNativeType(model)
            val returnValue = if (attrib.isStatic) "$ifPrefix$name::${attrib.name}" else "_self->${attrib.name}"
            val arrayValueMod = if (attrib.type.isArray) "[${NativeFuncRenderer.ARRAY_INDEX}]" else ""

            returnType = natType.jniType()
            functionName = nativeFunName(this@generateGet, attrib, "get")
            isUsingEnv = attrib.type.isString
            isReceivingAddress = !attrib.isStatic
            isReceivingArrayIndex = attrib.type.isArray

            body = if (attrib.isStatic) "" else "$ifPrefix$name* _self = ($ifPrefix$name*) ${NativeFuncRenderer.ADDRESS};\n"
            body += "return ${natType.castNativeToJni("$returnValue$arrayValueMod")};\n"
        }
    }

    private fun IdlInterface.generateSet(attrib: IdlAttribute, w: Writer) {
        w.nativeFunc {
            val ifPrefix = getDecoratorValue("Prefix", "")
            val natType = attrib.getNativeType(model)
            val valueReceiver = if (attrib.isStatic) "$ifPrefix$name::${attrib.name}" else "_self->${attrib.name}"
            val arrayValueMod = if (attrib.type.isArray) "[${NativeFuncRenderer.ARRAY_INDEX}]" else ""

            returnType = "void"
            functionName = nativeFunName(this@generateSet, attrib, "set")
            isUsingEnv = attrib.type.isString
            isReceivingAddress = !attrib.isStatic
            isReceivingArrayIndex = attrib.type.isArray
            extraArgs = ", ${natType.jniType()} value"

            body = if (attrib.isStatic) "" else "$ifPrefix$name* _self = ($ifPrefix$name*) ${NativeFuncRenderer.ADDRESS};\n"
            body += "$valueReceiver$arrayValueMod = ${natType.castJniToNative("value")};\n"
        }
    }

    private fun IdlInterface.generateSizeOf(w: Writer) {
        w.nativeFunc {
            returnType = "jint"
            functionName = nativeFunName(sourcePackage, name, "_sizeOf")
            isReceivingAddress = false
            body = "return sizeof(${getDecoratorValue("Prefix", "")}$name);"
        }
    }

    private fun nativeFunName(idlIf: IdlInterface, attrib: IdlAttribute, funPrefix: String) =
        nativeFunName(idlIf.sourcePackage, idlIf.name, "$funPrefix${firstCharToUpper(attrib.name)}")

    private fun nativeFunName(srcPkg: String, className: String, functionName: String, nameSuffix: String = ""): String {
        var name = packagePrefix
        if (name.isNotEmpty()) { name += "." }
        name += srcPkg
        if (name.isNotEmpty() && !name.endsWith(".")) { name += "." }
        name += "$className._$functionName"
        name = name.replace("_", "_1")
        name = name.replace(".", "_")
        return "Java_$name$nameSuffix"
    }

    private fun IdlFunction.getFunctionSuffix(isOverloaded: Boolean, isCtor: Boolean = false): String {
        return if (!isOverloaded) {
            ""
        } else {
            "__" + JavaTypeSignature.getFunctionTypeSuffix(this, !isStatic && !isCtor, model)
        }
    }

    private inline fun Writer.nativeFunc(block: NativeFuncRenderer.() -> Unit) {
        val renderer = NativeFuncRenderer()
        renderer.block()
        renderer.renderTo(this)
    }

    internal class NativeFuncRenderer {
        var returnType = "void"
        var functionName = ""
        var isUsingEnv = false
        var isStaticJava = true
        var isUsingJavaRef = false
        var isReceivingAddress = true
        var isReceivingArrayIndex = false

        var extraArgs = ""
        var body = ""

        fun renderTo(w: Writer) {
            val envArg = if (isUsingEnv) "JNIEnv* $ENV" else "JNIEnv*"
            val javaRef = if (isStaticJava) "jclass" else "jobject"
            val javaRefArg = if (isUsingJavaRef) "$javaRef $JAVA_REF" else javaRef
            val addrArg = if (isReceivingAddress) ", jlong $ADDRESS" else ""
            val arrayIndexArg = if (isReceivingArrayIndex) ", jint $ARRAY_INDEX" else ""
            w.append("JNIEXPORT $returnType JNICALL $functionName($envArg, $javaRefArg$addrArg$arrayIndexArg$extraArgs) {\n")
            w.append(body.trimIndent().prependIndent(4))
            w.append("\n}\n")
        }

        companion object {
            const val ENV = "_env"
            const val JAVA_REF = "_javaRef"
            const val ADDRESS = "_address"
            const val ARRAY_INDEX = "_index"
        }
    }
}