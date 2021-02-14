# webidl-util

A parser and code-generator for WebIDL files.

The goals of this project are:
1. Generate external interface definitions for easy access on
   [emscripten/WebIDL](https://emscripten.org/docs/porting/connecting_cpp_and_javascript/WebIDL-Binder.html)
   modules in Kotlin/js
2. Generate JNI (Java Native Interface) code (Java side as well as native glue code) from WebIDL to provide Java
   bindings for native libraries.

The two generator targets are especially useful in the context of Kotlin multi-platform projects, which can then
rely on the same library APIs for JVM and javascript platforms. However, the generated code can of course also
be used in non-multiplatform projects. The JNI code can even be used in plain Java without any Kotlin.

Although this is still work in progress, I use this to generate JNI bindings for Nvidia PhysX:
[physx-jni](https://github.com/fabmax/physx-jni) with ~170 generated java classes and no manual tweaking.
So it's arguably in a state where you could actually use it.

## How to use
This library is published to maven central, so you can easily add ít to your (gradle-)dependencies:
```
dependencies {
    implementation("de.fabmax:webidl-util:0.7.0")
}
```

As this is a code generator, it makes sense to integrate this library into a buildscript. Although there is no
dedicated gradle plugin, it is easy enough to integrate this into a gradle task. You can check out my
[physx-jni](https://github.com/fabmax/physx-jni) project to see this in action (take a look at the buildSrc folder).

Alternatively you can write a small `main()` method which configures and runs the generator:

```kotlin
fun main() {
    val model = WebIdlParser().parse("SomeWebIdlFile.idl")
    
    // Generate JNI native glue code
    JniNativeGenerator().apply {
        outputDirectory = "path/to/output/jni_native"
        // other configuration stuff...
    }.generate(model)

    // Generate JNI Java classes
    JniJavaGenerator().apply {
        outputDirectory = "path/to/output/jni_java"
        // other configuration stuff...
    }.generate(model)
    
    
    // Or, alternatively, if you are using kotlin/js and
    // want to use a WebIDL bound emscripten module
    JsInterfaceGenerator().apply {
        outputDirectory = "path/to/output/kotlinjs"
        // other configuration stuff...
    }.generate(model)
}
```

## Limitations
This is a work-in-progress project, and I implement features as I need them, so there are a limitations:

- Code is generated with minimal documentation (WebIDL files typically don't contain docs, but it would be super cool
  to crawl and include the documentation of the corresponding native lib)

### WebIDL Parser
- The parser is quite robust but doesn't provide very useful messages on syntax errors.
- Consistency of the parsed model is not checked (e.g. missing referenced interfaces don't produce an error).

### Kotlin/js Interface Generator
- No known issues.

### JNI Generator
- Array values are only supported for attributes (not for function arguments / return values).
- Passing java strings into a native API leaks memory: On the native side the string is copied into a char-array,
  which is never released.
