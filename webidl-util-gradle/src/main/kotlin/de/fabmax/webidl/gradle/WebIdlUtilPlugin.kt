package de.fabmax.webidl.gradle

import de.fabmax.webidl.generator.jni.java.JniJavaGenerator
import de.fabmax.webidl.generator.jni.nat.JniNativeGenerator
import de.fabmax.webidl.generator.ktjs.EmscriptenIdlGenerator
import de.fabmax.webidl.generator.ktjs.KtJsInterfaceGenerator
import de.fabmax.webidl.parser.WebIdlParser
import org.gradle.api.Action
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.FileCollection
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

        target.createGenerateJniJavaBindingsTask(extension)
        target.createGenerateJniNativeBindingsTask(extension)
        target.createGenerateKotlinJsBindingsTask(extension)
        target.createGenerateCompactWebIdlTask(extension)
    }

    private fun Project.createGenerateJniJavaBindingsTask(extension: WebIdlUtilExtension) = tasks.register("generateJniJavaBindings") {
        it.group = "webidl"
        it.doLast {
            val genJni = extension.getGenerateJniBindings()
            val outputDirJava = genJni.javaClassesOutputDirectory.asFile.get()
            val modelPath = extension.modelPath.asFile.get()
            val model = WebIdlParser.parse(modelPath.path, extension.modelName.orNull)

            JniJavaGenerator().apply {
                outputDirectory = outputDirJava.path
                genJni.packagePrefix.orNull?.let { packagePrefix = it }
                genJni.onClassLoadStatement.orNull?.let { onClassLoad = it }
                genJni.nativeIncludeDirs.get().mapNotNull { it.path }.forEach { parseCommentsFromDirectories += it }
                @Suppress("DEPRECATION")
                genJni.nativeIncludeDir.asFile.orNull?.let { parseCommentsFromDirectories += it.path }
            }.generate(model)
        }
    }

    private fun Project.createGenerateJniNativeBindingsTask(extension: WebIdlUtilExtension) = tasks.register("generateJniNativeBindings") {
        it.group = "webidl"
        it.doLast {
            val genJni = extension.getGenerateJniBindings()
            val outputFileNative = genJni.nativeGlueCodeOutputFile.asFile.get()
            val modelPath = extension.modelPath.asFile.get()
            val model = WebIdlParser.parse(modelPath.path, extension.modelName.orNull)

            JniNativeGenerator().apply {
                outputDirectory = outputFileNative.parent
                glueFileName = outputFileNative.name
                genJni.packagePrefix.orNull?.let { packagePrefix = it }
                genJni.nativePlatform.get().let { platform = it }
            }.generate(model)
        }
    }

    private fun Project.createGenerateKotlinJsBindingsTask(extension: WebIdlUtilExtension) = tasks.register("generateKotlinJsBindings") {
        it.group = "webidl"
        it.doLast {
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

    private fun Project.createGenerateCompactWebIdlTask(extension: WebIdlUtilExtension) = tasks.register("generateCompactWebIdl") {
        it.group = "webidl"
        it.doLast {
            val genWebIdl = extension.getGenerateCompactWebIdl()
            val outputPath = genWebIdl.outputFile.asFile.get()
            val modelPath = extension.modelPath.asFile.get()
            val model = WebIdlParser.parse(modelPath.path, extension.modelName.orNull, explodeOptionalFunctionParams = false)

            EmscriptenIdlGenerator().apply {
                outputDirectory = outputPath.parent
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
    @Deprecated("Use nativeIncludeDirs instead")
    val nativeIncludeDir: RegularFileProperty
    val nativeIncludeDirs: Property<FileCollection>
    val onClassLoadStatement: Property<String>
}

interface GenerateCompactWebIdl {
    val outputFile: RegularFileProperty
}
