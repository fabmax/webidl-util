plugins {
    alias(libs.plugins.kotlin)
    id("com.gradle.plugin-publish") version "1.2.1"
}

dependencies {
    api(project(":webidl-util"))
}

kotlin {
    jvmToolchain(8)
}

gradlePlugin {
    website = "https://github.com/fabmax/webidl-util"
    vcsUrl = "https://github.com/fabmax/webidl-util.git"

    plugins {
        create("webidlPlugin") {
            id = "de.fabmax.webidl-util"
            displayName = "WebIDL Code Generation Gradle Plugin"
            description = "Generates JNI and Kotlin/JS bindings from an emscripten/WebIDL model"
            implementationClass = "de.fabmax.webidl.gradle.WebIdlUtilPlugin"
        }
    }
}
