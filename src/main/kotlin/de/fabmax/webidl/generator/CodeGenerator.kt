package de.fabmax.webidl.generator

import de.fabmax.webidl.model.*
import java.io.*

abstract class CodeGenerator {

    var outputDirectory = "./generated"

    /**
     * Platform name used for filtering IDL model elements. Leave empty to include all.
     */
    var platform = ""

    abstract fun generate(model: IdlModel)

    val IdlModel.platformInterfaces: List<IdlInterface>
        get() = interfaces.filter { it.matchesPlatform(platform) }

    val IdlModel.platformEnums: List<IdlEnum>
        get() = enums.filter { it.matchesPlatform(platform) }

    val IdlInterface.platformFunctions: List<IdlFunction>
        get() = functions.filter { it.matchesPlatform(platform) }

    val IdlInterface.platformAttributes: List<IdlAttribute>
        get() = attributes.filter { it.matchesPlatform(platform) }

    fun deleteDirectory(dir: File) {
        dir.listFiles()?.forEach {
            if (it.isDirectory) {
                deleteDirectory(it)
            }
            it.delete()
        }
    }

    fun getOutFile(path: String) = File(outputDirectory, path)

    fun createOutFileWriter(path: String): Writer {
        return OutputStreamWriter(createOutFile(path))
    }

    fun createOutFile(path: String): OutputStream {
        val outPath = getOutFile(path)
        val dir = outPath.parentFile
        if (!dir.exists() && !dir.mkdirs()) {
            throw IOException("Failed creating output directory")
        }
        return FileOutputStream(outPath)
    }

    fun firstCharToUpper(str: String): String {
        return str[0].uppercaseChar() + str.substring(1)
    }

    fun firstCharToLower(str: String): String {
        return str[0].lowercaseChar() + str.substring(1)
    }
}

internal fun indent(indent: Int) = String.format("%${indent}s", "")

internal fun String.prependIndent(indent: Int) = prependIndent(indent(indent))
