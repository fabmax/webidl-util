package de.fabmax.webidl.generator

import de.fabmax.webidl.model.IdlModel
import java.io.*

abstract class CodeGenerator {

    var outputDirectory = "./generated"

    open fun generate(model: IdlModel) {
        deleteDirectory(File(outputDirectory))
    }

    private fun deleteDirectory(dir: File) {
        dir.listFiles()?.forEach {
            if (it.isDirectory) {
                deleteDirectory(it)
            }
            it.delete()
        }
    }

    fun createOutFileWriter(path: String): Writer {
        return OutputStreamWriter(createOutFile(path))
    }

    fun createOutFile(path: String): OutputStream {
        val outPath = File(outputDirectory, path)
        outPath.parentFile.mkdirs()
        return FileOutputStream(outPath)
    }

    fun firstCharToUpper(str: String): String {
        return str[0].toUpperCase() + str.substring(1)
    }

    fun firstCharToLower(str: String): String {
        return str[0].toLowerCase() + str.substring(1)
    }
}