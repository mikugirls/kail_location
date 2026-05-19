package com.kail.location.utils

import java.io.BufferedReader
import java.io.InputStreamReader

object ShellUtils {
    fun hasRoot(): Boolean {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            process.outputStream.close()
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readLine()
            reader.close()
            process.waitFor()
            return output?.contains("uid=0") == true || process.exitValue() == 0
        } catch (e: Exception) {
            return false
        }
    }

    fun executeCommand(command: String): String {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val stdout = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            val stderr = BufferedReader(InputStreamReader(process.errorStream)).use { it.readText() }
            process.waitFor()
            return if (stdout.isNotEmpty()) stdout else stderr
        } catch (e: Exception) {
            e.printStackTrace()
            return ""
        }
    }

    fun executeCommandToBytes(command: String): ByteArray {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val stdout = process.inputStream.use { it.readBytes() }
            val stderr = process.errorStream.use { it.readBytes() }
            process.waitFor()
            return if (stdout.isNotEmpty()) stdout else stderr
        } catch (e: Exception) {
            e.printStackTrace()
            return ByteArray(0)
        }
    }
}