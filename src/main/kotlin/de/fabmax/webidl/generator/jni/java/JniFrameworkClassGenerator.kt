package de.fabmax.webidl.generator.jni.java

import de.fabmax.webidl.generator.prependIndent
import de.fabmax.webidl.model.IdlInterface
import de.fabmax.webidl.model.IdlModel

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

internal fun JniJavaGenerator.generateJniThreadManager(model: IdlModel): JavaClass {
    val fwThreadManager = IdlInterface.Builder("JniThreadManager").build()
    fwThreadManager.finishModel(model)

    return JavaClass(fwThreadManager, "", packagePrefix).apply {
        protectedDefaultContructor = false
        generatePointerWrapMethods = false
        staticCode = onClassLoad
        importFqns += "java.util.concurrent.atomic.AtomicBoolean"
    }.apply {
        generateSource(createOutFileWriter(fileName)) {
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