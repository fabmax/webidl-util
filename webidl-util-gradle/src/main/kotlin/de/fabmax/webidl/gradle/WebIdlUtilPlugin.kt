package de.fabmax.webidl.gradle

import de.fabmax.webidl.generator.jni.java.JniJavaGenerator
import de.fabmax.webidl.generator.jni.nat.JniNativeGenerator
import de.fabmax.webidl.generator.ktjs.EmscriptenIdlGenerator
import de.fabmax.webidl.generator.ktjs.KtJsInterfaceGenerator
import de.fabmax.webidl.parser.WebIdlParser
import org.gradle.api.Action
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Nested
import org.gradle.internal.os.OperatingSystem

class WebIdlUtilPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        val extension = target.extensions.create("webidl", WebIdlUtilExtension::class.java)

        val os = OperatingSystem.current()
        extension.getGenerateJniBindings().nativePlatform.convention(when {
            os.isWindows -> "windows"
            os.isLinux -> "linux"
            os.isMacOsX -> "macos"
            else -> throw IllegalStateException("Unsupported platform: $os")
        })

        target.createGenerateJniBindingsTask(extension)
        target.createGenerateKotlinJsBindingsTask(extension)
        target.createGenerateCompactWebIdlTask(extension)
    }

    private fun Project.createGenerateJniBindingsTask(extension: WebIdlUtilExtension) = task("generateJniBindings").apply {
        group = "webidl"
        doLast {
            val genJni = extension.getGenerateJniBindings()
            val outputFileNative = genJni.nativeGlueCodeOutputFile.asFile.orNull
            val outputDirJava = genJni.javaClassesOutputDirectory.asFile.orNull
            val modelPath = extension.modelPath.asFile.get()
            val model = WebIdlParser.parse(modelPath.path, extension.modelName.orNull)

            outputDirJava?.let {
                JniJavaGenerator().apply {
                    outputDirectory = outputDirJava.path
                    genJni.packagePrefix.orNull?.let { packagePrefix = it }
                    genJni.onClassLoadStatement.orNull?.let { onClassLoad = it }
                    genJni.nativeIncludeDir.asFile.orNull?.let { parseCommentsFromDirectories += it.path }
                }.generate(model)
            }

            outputFileNative?.let {
                JniNativeGenerator().apply {
                    outputDirectory = outputFileNative.parent
                    glueFileName = outputFileNative.name
                    genJni.packagePrefix.orNull?.let { packagePrefix = it }
                    genJni.nativePlatform.get().let { platform = it }
                }.generate(model)
            }
        }
    }

    private fun Project.createGenerateKotlinJsBindingsTask(extension: WebIdlUtilExtension) = task("generateKotlinJsBindings").apply {
        group = "webidl"
        doLast {
            val genInterfaces = extension.getGenerateKotlinJsBindings()
            val outputDir = genInterfaces.outputDirectory.asFile.get()
            val modelPath = extension.modelPath.asFile.get()
            val model = WebIdlParser.parse(modelPath.path, extension.modelName.orNull)

            KtJsInterfaceGenerator().apply {
                outputDirectory = outputDir.path
                genInterfaces.moduleName.orNull?.let { moduleName = it }
                genInterfaces.packagePrefix.orNull?.let { packagePrefix = it }
                genInterfaces.modulePromiseName.orNull?.let { modulePromiseName = it }
            }.generate(model)
        }
    }

    private fun Project.createGenerateCompactWebIdlTask(extension: WebIdlUtilExtension) = task("generateCompactWebIdl").apply {
        group = "webidl"
        doLast {
            val genWebIdl = extension.getGenerateCompactWebIdl()
            val outputPath = genWebIdl.outputFile.asFile.get()
            val modelPath = extension.modelPath.asFile.get()
            val model = WebIdlParser.parse(modelPath.path, extension.modelName.orNull)

            EmscriptenIdlGenerator().apply {
                outputDirectory = outputPath.path
                outputIdlFileName = outputPath.name
            }.generate(model)
        }
    }
}

abstract class WebIdlUtilExtension {
    abstract val modelPath: RegularFileProperty
    abstract val modelName: Property<String>

    @Nested
    abstract fun getGenerateKotlinJsBindings(): GenerateKotlinJsBindings

    @Nested
    abstract fun getGenerateJniBindings(): GenerateJniBindings

    @Nested
    abstract fun getGenerateCompactWebIdl(): GenerateCompactWebIdl

    fun generateKotlinJsInterfaces(action: Action<GenerateKotlinJsBindings>) {
        action.execute(getGenerateKotlinJsBindings())
    }

    fun generateJni(action: Action<GenerateJniBindings>) {
        action.execute(getGenerateJniBindings())
    }

    fun generateCompactWebIdl(action: Action<GenerateCompactWebIdl>) {
        action.execute(getGenerateCompactWebIdl())
    }
}

interface GenerateKotlinJsBindings {
    val outputDirectory: RegularFileProperty
    val packagePrefix: Property<String>
    val moduleName: Property<String>
    val modulePromiseName: Property<String>
}

interface GenerateJniBindings {
    val javaClassesOutputDirectory: RegularFileProperty
    val nativeGlueCodeOutputFile: RegularFileProperty
    val packagePrefix: Property<String>
    val nativePlatform: Property<String>
    val nativeIncludeDir: RegularFileProperty
    val onClassLoadStatement: Property<String>
}

interface GenerateCompactWebIdl {
    val outputFile: RegularFileProperty
}
