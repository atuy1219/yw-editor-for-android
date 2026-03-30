package com.atuy.yweditor.yokai

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import java.io.InputStream
import java.io.IOException
import java.io.OutputStream
import java.lang.reflect.Method
import java.net.URLDecoder
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class MainBinBackupInfo(
    val fileName: String,
    val displayName: String,
    val backupEpochMillis: Long,
    val filePath: String,
    val lastModifiedEpochMillis: Long,
)

class ShizukuFileGateway {

    private val managedBackupRegex = Regex("""^main_backup_(\d{12})_([A-Za-z0-9%._+-]{1,120})\.bin$""")
    private val backupTimestampFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmm")

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

    fun createManagedBackup(path: String, backupEpochMillis: Long, backupName: String): MainBinBackupInfo {
        val backupsDir = backupDir(path)
        val timestamp = buildBackupTimestamp(backupEpochMillis)
        val encodedName = encodeBackupName(backupName)
        val displayName = decodeBackupName(encodedName)
        val fileName = "main_backup_${timestamp}_${encodedName}.bin"
        val backupPath = "$backupsDir/$fileName"

        exec(arrayOf("sh", "-c", "mkdir -p ${shellQuote(backupsDir)}"))
        exec(arrayOf("sh", "-c", "cp ${shellQuote(path)} ${shellQuote(backupPath)}"))

        val modified = lastModifiedMillis(backupPath)
        return MainBinBackupInfo(
            fileName = fileName,
            displayName = displayName,
            backupEpochMillis = backupEpochMillis,
            filePath = backupPath,
            lastModifiedEpochMillis = modified,
        )
    }

    fun listManagedBackups(path: String): List<MainBinBackupInfo> {
        val backupsDir = backupDir(path)
        exec(arrayOf("sh", "-c", "mkdir -p ${shellQuote(backupsDir)}"))
        val files = runCommandForText(
            arrayOf("sh", "-c", "ls -1 ${shellQuote(backupsDir)}"),
            onErrorReturnEmpty = true,
        )

        return files
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { fileName ->
                val parsed = parseManagedBackup(fileName) ?: return@mapNotNull null
                val filePath = "$backupsDir/$fileName"
                val modified = runCatching { lastModifiedMillis(filePath) }.getOrDefault(parsed.first)
                MainBinBackupInfo(
                    fileName = fileName,
                    displayName = parsed.second,
                    backupEpochMillis = parsed.first,
                    filePath = filePath,
                    lastModifiedEpochMillis = modified,
                )
            }
            .sortedByDescending { it.backupEpochMillis }
            .toList()
    }

    fun restoreManagedBackup(path: String, backupFileName: String) {
        val backup = parseManagedBackup(backupFileName)
            ?: throw IOException("不正なバックアップ名です: $backupFileName")
        if (backup.second.isBlank()) {
            throw IOException("バックアップ名の解析に失敗しました: $backupFileName")
        }

        val sourcePath = "${backupDir(path)}/$backupFileName"
        exec(arrayOf("sh", "-c", "cp ${shellQuote(sourcePath)} ${shellQuote(path)}"))
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

    private fun runCommandForText(command: Array<String>, onErrorReturnEmpty: Boolean = false): String {
        val process = startProcess(command)
        val output = inputStreamOf(process).use { it.readBytes().toString(Charsets.UTF_8) }
        val error = errorStreamOf(process).use { it.readBytes().toString(Charsets.UTF_8).trim() }
        val code = waitFor(process)
        if (code != 0) {
            if (onErrorReturnEmpty) return ""
            throw IOException("コマンド失敗(code=$code): ${if (error.isBlank()) "unknown" else error}")
        }
        return output
    }

    private fun backupDir(path: String): String {
        val normalized = path.replace('\\', '/')
        val slash = normalized.lastIndexOf('/')
        if (slash <= 0) return "./backups"
        return normalized.substring(0, slash) + "/backups"
    }

    private fun buildBackupTimestamp(epochMillis: Long): String {
        val local = LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault())
        return local.format(backupTimestampFormatter)
    }

    private fun parseManagedBackup(fileName: String): Pair<Long, String>? {
        val match = managedBackupRegex.matchEntire(fileName) ?: return null
        val digits = match.groupValues[1]
        val local = runCatching { LocalDateTime.parse(digits, backupTimestampFormatter) }.getOrNull() ?: return null
        val epoch = local.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val name = decodeBackupName(match.groupValues[2])
        return epoch to name
    }

    private fun encodeBackupName(input: String): String {
        val normalized = input.trim().ifBlank { "backup" }.take(40)
        return URLEncoder.encode(normalized, Charsets.UTF_8.name())
    }

    private fun decodeBackupName(input: String): String {
        return runCatching { URLDecoder.decode(input, Charsets.UTF_8.name()) }
            .getOrDefault(input)
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

