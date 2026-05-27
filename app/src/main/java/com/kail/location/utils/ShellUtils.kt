package com.kail.location.utils

import java.io.BufferedReader
import java.io.InputStreamReader

object ShellUtils {
    fun hasRoot(): Boolean {
        var process: Process? = null
        try {
            process = Runtime.getRuntime().exec("su")
            process.outputStream.write("id\n".toByteArray())
            process.outputStream.write("exit\n".toByteArray())
            process.outputStream.flush()
            process.outputStream.close()

            val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            process.waitFor()
            return output.contains("uid=0") || process.exitValue() == 0
        } catch (e: Exception) {
            return false
        } finally {
            process?.destroy()
        }
    }

    fun executeCommand(command: String): String {
        var process: Process? = null
        try {
            process = Runtime.getRuntime().exec("su")
            process.outputStream.write("$command\n".toByteArray())
            process.outputStream.write("exit\n".toByteArray())
            process.outputStream.flush()
            process.outputStream.close()

            val stdout = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            val stderr = BufferedReader(InputStreamReader(process.errorStream)).use { it.readText() }
            process.waitFor()
            return if (stdout.isNotEmpty()) stdout else stderr
        } catch (e: Exception) {
            e.printStackTrace()
            return ""
        } finally {
            process?.destroy()
        }
    }

    fun executeCommandToBytes(command: String): ByteArray {
        var process: Process? = null
        try {
            process = Runtime.getRuntime().exec("su")
            process.outputStream.write("$command\n".toByteArray())
            process.outputStream.write("exit\n".toByteArray())
            process.outputStream.flush()
            process.outputStream.close()

            val stdout = process.inputStream.use { it.readBytes() }
            val stderr = process.errorStream.use { it.readBytes() }
            process.waitFor()
            return if (stdout.isNotEmpty()) stdout else stderr
        } catch (e: Exception) {
            e.printStackTrace()
            return ByteArray(0)
        } finally {
            process?.destroy()
        }
    }
}
