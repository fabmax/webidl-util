# webidl-util

[![License](https://img.shields.io/badge/License-Apache%202.0-green.svg)](http://www.apache.org/licenses/LICENSE-2.0.txt)
[![Maven Central](https://img.shields.io/maven-central/v/de.fabmax/webidl-util.svg?label=Maven%20Central)](https://search.maven.org/search?q=g:%22de.fabmax%22%20AND%20a:%22webidl-util%22)

A parser and code-generator for WebIDL files.

The goals of this project are:
1. Generate external interface definitions for easy access on
   [emscripten/WebIDL](https://emscripten.org/docs/porting/connecting_cpp_and_javascript/WebIDL-Binder.html)
   modules in Kotlin/js
2. Generate JNI (Java Native Interface) code (Java side as well as native glue code) from WebIDL to provide Java
   bindings for native libraries.

The two generator targets are especially useful in the context of Kotlin multi-platform projects, which can then
rely on the same library APIs for JVM and javascript platforms. However, the generated code can of course also
be used in non-multiplatform projects.

I use this to generate JNI bindings for Nvidia PhysX:
[physx-jni](https://github.com/fabmax/physx-jni) with ~370 generated java classes and no manual tweaking.
So it's arguably in a state where you can actually use it :smile:

## How to use
This library comes in two flavors: A gradle plugin for easy buildscript integration as well as 
a plane library, which can be used for advanced use-cases.

### Gradle plugin
Apply the gradle plugin to your project:
```kotlin
plugins {
    id("de.fabmax.webidl-util") version "0.10.2"
}
```
Then configure it to generate JNI bindings:
```kotlin
webidl {
    modelPath = "path/to/webidlmodel.idl"   // required
    modelName = "MyWebIdl"                  // optional model name

    generateJni {
        // required: where to generate the Java classes
        javaClassesOutputDirectory = file("$projectDir/src/main/generated/")
        // required: where to generate the JNI C header file
        nativeGlueCodeOutputFile = file("path/to/native/glue_code.h")

        // optional package prefix for the generated Java classes
        packagePrefix = "com.example"
        // optional directory with C++ header files to parse documentation strings from
        nativeIncludeDir = file("${projectDir}/src/jsMain/kotlin/physx")
        // optional statement to execute in each generated class' static block
        //  this is particularly useful to call loader code, which loads the corresponding native lib
        onClassLoadStatement = "System.out.println(\"class loaded\")"
    }
}
```

Alternatively, you can generate Kotlin/JS bindings for an emscripten/WASM library compiled with the given
WebIDL definition:
```kotlin
webidl {
    modelPath = "path/to/webidlmodel.idl"   // required
    modelName = "MyWebIdl"                  // optional model name

    generateKotlinJsInterfaces {
        // required: where to generate the Kotlin interfaces
        outputDirectory = file("${projectDir}/src/jsMain/kotlin")
        // required: name of the JS module
        moduleName = "physx-js-webidl"
        // required: name of the JS Promise providing the loaded module
        modulePromiseName = "PhysX"
        // optional package prefix for the generated Kotlin interfaces
        packagePrefix = "com.example"
    }
}
```

### Plain library usage
The library is published on maven central:

```
dependencies {
    implementation("de.fabmax:webidl-util:0.10.2")
}
```

Here's a small `main()` method which configures and runs the generator:

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
This is a work-in-progress project, and I implement features as I need them, so there are a few limitations:

### WebIDL Parser
- The parser is quite robust and provides somewhat useful messages on syntax errors, however there might be edge
  cases which are not correctly parsed.
- Consistency of the parsed model is not checked (e.g. missing referenced interfaces don't produce an error).

### Kotlin/js Interface Generator
- No known issues.

### JNI Generator
- Array values are only supported for attributes (not for function arguments / return values).
- Passing java strings into a native API leaks memory: On the native side the string is copied into a char-array,
  which is never released.
