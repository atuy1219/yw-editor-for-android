package com.atuy.yweditor.yokai

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import java.io.InputStream
import java.io.IOException
import java.io.OutputStream
import java.lang.reflect.Method

class ShizukuFileGateway {

    private val newProcessMethod: Method by lazy {
        Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        ).apply {
            isAccessible = true
        }
    }

    fun isShizukuRunning(): Boolean = Shizuku.pingBinder()

    fun hasPermission(): Boolean {
        if (!isShizukuRunning()) return false
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: IllegalStateException) {
            false
        }
    }

    fun requestPermission(requestCode: Int) {
        if (!isShizukuRunning()) return
        try {
            Shizuku.requestPermission(requestCode)
        } catch (_: IllegalStateException) {
            // Binder 未受信直後のレースを吸収し、UI から再試行できるようにする。
        }
    }

    fun readBytes(path: String): ByteArray {
        val command = arrayOf("sh", "-c", "cat ${shellQuote(path)}")
        val process = startProcess(command)

        val data = inputStreamOf(process).use { it.readBytes() }
        val error = errorStreamOf(process).use { it.readBytes().toString(Charsets.UTF_8).trim() }
        val code = waitFor(process)
        if (code != 0) {
            throw IOException("読み取り失敗(code=$code): ${if (error.isBlank()) "unknown" else error}")
        }

        return data
    }

    fun backup(path: String): String {
        val backupPath = "$path.bak"
        exec(arrayOf("sh", "-c", "cp ${shellQuote(path)} ${shellQuote(backupPath)}"))
        return backupPath
    }

    fun writeBytes(path: String, data: ByteArray) {
        val process = startProcess(arrayOf("sh", "-c", "cat > ${shellQuote(path)}"))
        outputStreamOf(process).use {
            it.write(data)
            it.flush()
        }

        val error = errorStreamOf(process).use { it.readBytes().toString(Charsets.UTF_8).trim() }
        val code = waitFor(process)
        if (code != 0) {
            throw IOException("書き込み失敗(code=$code): ${if (error.isBlank()) "unknown" else error}")
        }
    }

    fun lastModifiedMillis(path: String): Long {
        val process = startProcess(
            arrayOf(
                "sh",
                "-c",
                "stat -c %Y ${shellQuote(path)} 2>/dev/null || toybox stat -c %Y ${shellQuote(path)}",
            ),
        )
        val output = inputStreamOf(process).use { it.readBytes().toString(Charsets.UTF_8).trim() }
        val error = errorStreamOf(process).use { it.readBytes().toString(Charsets.UTF_8).trim() }
        val code = waitFor(process)
        if (code != 0) {
            throw IOException("更新日時取得失敗(code=$code): ${if (error.isBlank()) "unknown" else error}")
        }

        val seconds = output.lineSequence().firstOrNull()?.toLongOrNull()
            ?: throw IOException("更新日時の解析に失敗: $output")
        return seconds * 1000L
    }

    private fun exec(command: Array<String>) {
        val process = startProcess(command)
        val error = errorStreamOf(process).use { it.readBytes().toString(Charsets.UTF_8).trim() }
        val code = waitFor(process)
        if (code != 0) {
            throw IOException("コマンド失敗(code=$code): ${if (error.isBlank()) "unknown" else error}")
        }
    }

    private fun startProcess(command: Array<String>): Any {
        if (!isShizukuRunning()) {
            throw IOException("Shizuku が未接続です。Shizuku を起動してから再試行してください")
        }
        if (!hasPermission()) {
            throw IOException("Shizuku の権限がありません。許可後に再試行してください")
        }

        return try {
            newProcessMethod.invoke(null, command, null, null)
                ?: throw IOException("Shizuku プロセスの生成に失敗しました")
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("Shizuku プロセスAPI呼び出し失敗: ${e.message}", e)
        }
    }

    private fun inputStreamOf(process: Any): InputStream {
        return process.javaClass.getMethod("getInputStream").invoke(process) as InputStream
    }

    private fun errorStreamOf(process: Any): InputStream {
        return process.javaClass.getMethod("getErrorStream").invoke(process) as InputStream
    }

    private fun outputStreamOf(process: Any): OutputStream {
        return process.javaClass.getMethod("getOutputStream").invoke(process) as OutputStream
    }

    private fun waitFor(process: Any): Int {
        return process.javaClass.getMethod("waitFor").invoke(process) as Int
    }

    private fun shellQuote(path: String): String {
        return "'" + path.replace("'", "'\"'\"'") + "'"
    }
}

