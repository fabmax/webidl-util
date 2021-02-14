package de.fabmax.webidl.generator

import de.fabmax.webidl.model.IdlModel
import java.io.*

abstract class CodeGenerator {

    var outputDirectory = "./generated"

    abstract fun generate(model: IdlModel)

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
        if (!dir.exists()) {
            if (!dir.mkdirs()) {
                throw IOException("Failed creating output directory")
            }
        }
        return FileOutputStream(outPath)
    }

    fun firstCharToUpper(str: String): String {
        return str[0].toUpperCase() + str.substring(1)
    }

    fun firstCharToLower(str: String): String {
        return str[0].toLowerCase() + str.substring(1)
    }
}

internal fun indent(indent: Int) = String.format("%${indent}s", "")

internal fun String.prependIndent(indent: Int) = prependIndent(indent(indent))
