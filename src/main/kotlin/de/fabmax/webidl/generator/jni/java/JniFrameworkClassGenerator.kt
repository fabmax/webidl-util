package de.fabmax.webidl.generator.jni.java

import de.fabmax.webidl.generator.prependIndent
import de.fabmax.webidl.model.IdlInterface
import de.fabmax.webidl.model.IdlModel

internal fun JniJavaGenerator.generatePlatformChecks(model: IdlModel): JavaClass {
    val fwPlatformFlags = IdlInterface.Builder(JniJavaGenerator.PLATFORM_CHECKS_NAME).build()
    fwPlatformFlags.finishModel(model)

    return JavaClass(fwPlatformFlags, "", packagePrefix).apply {
        protectedDefaultContructor = true
        generatePointerWrapMethods = false
    }.apply {
        generateSource(createOutFileWriter(fileName)) {
            append("""
                public static final int PLATFORM_WINDOWS = ${JniJavaGenerator.PLATFORM_BIT_WINDOWS};
                public static final int PLATFORM_LINUX = ${JniJavaGenerator.PLATFORM_BIT_LINUX};
                public static final int PLATFORM_MACOS = ${JniJavaGenerator.PLATFORM_BIT_MACOS};
                public static final int PLATFORM_ANDROID = ${JniJavaGenerator.PLATFORM_BIT_ANDROID};
                public static final int PLATFORM_OTHER = ${JniJavaGenerator.PLATFORM_BIT_OTHER};
                
                private static int platformBit = PLATFORM_OTHER;
                
                public static void setPlatformBit(int platformBit) {
                    ${JniJavaGenerator.PLATFORM_CHECKS_NAME}.platformBit = platformBit;
                }
                
                public static void requirePlatform(int supportedPlatforms, String name) {
                    if ((supportedPlatforms & platformBit) == 0) {
                        throw new RuntimeException(name + " is not supported on this platform. If you think this is a mistake, make sure the correct platform is set by calling PlatformChecks.setPlatformBit().");
                    }
                }
            """.trimIndent().prependIndent(4)).append('\n')
        }
    }
}

internal fun JniJavaGenerator.generateNativeObject(model: IdlModel): JavaClass {
    val fwNativeObj = IdlInterface.Builder(JniJavaGenerator.NATIVE_OBJECT_NAME).build()
    fwNativeObj.finishModel(model)

    return JavaClass(fwNativeObj, "", packagePrefix).apply {
        protectedDefaultContructor = true
        generatePointerWrapMethods = false
        staticCode = onClassLoad
    }.apply {
        generateSource(createOutFileWriter(fileName)) {
            append("""
                private static native int __sizeOfPointer();
                public static final int SIZEOF_POINTER = __sizeOfPointer();
                public static final int SIZEOF_BYTE = 1;
                public static final int SIZEOF_SHORT = 2;
                public static final int SIZEOF_INT = 4;
                public static final int SIZEOF_LONG = 8;
                public static final int SIZEOF_FLOAT = 4;
                public static final int SIZEOF_DOUBLE = 8;
                
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
    }
}

internal fun JniJavaGenerator.generateJavaNativeRef(model: IdlModel, nativeObject: JavaClass): JavaClass {
    val fwJavaNativeRef = IdlInterface.Builder("JavaNativeRef").build()
    fwJavaNativeRef.finishModel(model)

    return JavaClass(fwJavaNativeRef, "", packagePrefix).apply {
        protectedDefaultContructor = false
        generatePointerWrapMethods = false
        staticCode = onClassLoad
        superClass = nativeObject
    }.apply {
        createOutFileWriter(fileName).use { w ->
            generatePackage(w)
            generateImports(w)

            w.append("""
                public class JavaNativeRef<T> extends NativeObject {
                    static {
                        $staticCode
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
}