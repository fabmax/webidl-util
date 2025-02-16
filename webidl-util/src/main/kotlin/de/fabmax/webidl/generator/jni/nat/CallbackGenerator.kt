package de.fabmax.webidl.generator.jni.nat

import de.fabmax.webidl.generator.indent
import de.fabmax.webidl.generator.prependIndent
import de.fabmax.webidl.model.IdlDecorator
import de.fabmax.webidl.model.IdlInterface
import de.fabmax.webidl.model.IdlModel
import java.io.Writer

internal class CallbackGenerator(val model: IdlModel, val platform: String) {

    fun generateJniSupportCode(w: Writer) {
        // Generate support code, which auto attaches and detaches native threads to the Java VM.
        // This is needed for callbacks from the native side to Java from other threads than the main thread.
        w.append("""
            static JavaVM* javaVm = NULL;
            
            class JniThreadEnv {
                public:
                    JniThreadEnv() : shouldDetach(false), env(NULL) { }
                    JniThreadEnv(JNIEnv *env) : shouldDetach(false), env(env) { }
                    ~JniThreadEnv() {
                        if (shouldDetach) {
                            javaVm->DetachCurrentThread();
                        }
                    }
                    JNIEnv* getEnv() {
                        if (env == NULL && javaVm != NULL) {
#ifndef __ANDROID__
                            javaVm->AttachCurrentThreadAsDaemon((void**) &env, NULL);
#else
                            javaVm->AttachCurrentThreadAsDaemon(&env, NULL);
#endif
                            shouldDetach = true;
                        }
                        return env;
                    }
                    
                private:
                    bool shouldDetach;
                    JNIEnv *env;
            };
            
            static thread_local JniThreadEnv jniThreadEnv;
            
            class JavaNativeRef {
                public:
                    JavaNativeRef(JNIEnv *env, jobject javaRef) {
                        javaGlobalRef = env->NewGlobalRef(javaRef);
                    }
                    
                    ~JavaNativeRef() {
                        jniThreadEnv.getEnv()->DeleteGlobalRef(javaGlobalRef);
                    }
                    
                    jobject javaGlobalRef;
            };
        """.trimIndent()).append("\n\n")
    }

    fun generateJniOnLoad(w: Writer) {
        w.append("\n\n")
        w.append("""
            // on load callback executed by the JVM once the lib is loaded
            JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) {
                javaVm = vm;
                
                JNIEnv* env;
                if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
                    return JNI_ERR;
                }
                jniThreadEnv = JniThreadEnv(env);
                
                return JNI_VERSION_1_6;
            }
        """.trimIndent())
    }

    fun generateCallbackClasses(w: Writer) {
        model.interfaces
            .filter { it.hasDecorator(IdlDecorator.JS_IMPLEMENTATION) }
            .filter { it.matchesPlatform(platform) }
            .forEach { it.generateCallbackClass(w) }
    }

    private fun IdlInterface.generateCallbackClass(w: Writer) {
        var superClassName = getDecoratorValue("JSImplementation", "")
        val superClass = model.interfaces.find { it.name == superClassName }
            ?: throw IllegalStateException("Callback class $name has an invalid / unknown super class \"$superClassName\"")

        superClassName = superClass.getDecoratorValue("Prefix", "") + superClassName

        w.append("""
            class $name : $superClassName {
                public:
                    $name(JNIEnv* env, jobject javaLocalRef) {
                        javaGlobalRef = env->NewGlobalRef(javaLocalRef);
                        jclass javaClass = env->GetObjectClass(javaLocalRef);
                        ${generateGetMethodIds()}
                    }
                    
                    ~$name() {
                        jniThreadEnv.getEnv()->DeleteGlobalRef(javaGlobalRef);
                    }
                    ${generateCallbackMethods()}
                private:
                    jobject javaGlobalRef;
                    ${generateMethodIdMembers()}
            };
        """.trimIndent()).append("\n\n")
    }

    private fun IdlInterface.generateCallbackMethods(): String {
        val out = StringBuilder()
        functions.filter { it.name != name }.forEach { cbFunc ->
            val env = JniNativeGenerator.NativeFuncRenderer.ENV
            val returnType = cbFunc.getNativeType(model)
            val paramsToTypes = cbFunc.parameters.zip(cbFunc.parameters.map { it.getNativeType(model) })
            val params = paramsToTypes.joinToString(", ") { (p, t) -> "${t.nativeType()} ${p.name}" }
            var callParams = paramsToTypes.joinToString(", ") { (p, t) -> t.castNativeToJni(p.name) }
            if (callParams.isNotEmpty()) {
                callParams = ", $callParams"
            }

            val callTypedMethod = when (cbFunc.returnType.typeName) {
                "boolean" -> "CallBooleanMethod"
                "float" -> "CallFloatMethod"
                "double" -> "CallDoubleMethod"
                "byte" -> "CallByteMethod"
                "octet" -> "CallByteMethod"
                "short" -> "CallShortMethod"
                "long" -> "CallIntMethod"
                "long long" -> "CallLongMethod"
                "unsigned short" -> "CallShortMethod"
                "unsigned long" -> "CallIntMethod"
                "unsigned long long" -> "CallLongMethod"
                "void" -> "CallVoidMethod"
                else -> if(cbFunc.returnType.isString) "CallObjectMethod" else "CallLongMethod"    // any, VoidPtr, NativeObject
            }

            var call = "$env->$callTypedMethod(javaGlobalRef, ${cbFunc.name}MethodId$callParams)"
            if (!cbFunc.returnType.isVoid) {
                call = "return ${returnType.castJniToNative(call)}"
            }

            out.append('\n').append("""
                virtual ${returnType.nativeType()} ${cbFunc.name}($params) {
                    JNIEnv* $env = jniThreadEnv.getEnv();
                    $call;
                }
            """.trimIndent().prependIndent(20)).append('\n')
        }
        return out.toString()
    }

    private fun IdlInterface.generateGetMethodIds(): String {
        val out = StringBuilder()
        val cbFuncs = functions.filter { it.name != name }
        cbFuncs.forEachIndexed { i, cbFunc ->
            if (i > 0) {
                out.append(indent(24))
            }
            out.append("${cbFunc.name}MethodId = env->GetMethodID(javaClass, \"_${cbFunc.name}\", \"${JavaTypeSignature.getJavaFunctionSignature(cbFunc, model)}\");")
            if (i < cbFuncs.lastIndex) { out.append('\n') }
        }
        return out.toString()
    }

    private fun IdlInterface.generateMethodIdMembers(): String {
        val out = StringBuilder()
        val cbFuncs = functions.filter { it.name != name }
        cbFuncs.forEachIndexed { i, cbFunc ->
            if (i > 0) {
                out.append(indent(20))
            }
            out.append("jmethodID ${cbFunc.name}MethodId;")
            if (i < cbFuncs.lastIndex) { out.append('\n') }
        }
        return out.toString()
    }
}