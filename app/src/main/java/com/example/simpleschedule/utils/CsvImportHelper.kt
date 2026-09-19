package com.example.simpleschedule.utils

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import kotlin.math.abs

private val courseColors = listOf("blue", "pink", "purple", "slate", "indigo", "rose")

suspend fun downloadCsvTemplate(context: Context, urlString: String): File? = withContext(Dispatchers.IO) {
    val urlsToTry = listOf(
        urlString,
        "https://www.lingflame.cn/schedule_template.csv"
    )
    for (currentUrl in urlsToTry) {
        var connection: HttpURLConnection? = null
        try {
            val url = URL(currentUrl)
            connection = url.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) SimpleScheduleApp")
            if (connection.responseCode == 200) {
                val bytes = connection.inputStream.use { it.readBytes() }
                if (bytes.isNotEmpty() && bytes.size < 1000000) {
                    val decoded = decodeCsvBytes(bytes)
                    if (decoded.contains("课程") || decoded.contains(",")) {
                        return@withContext saveCsvToDownloads(context, bytes, "课表模板.csv")
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            connection?.disconnect()
        }
    }
    null
}

fun saveCsvToDownloads(context: Context, bytes: ByteArray, fileName: String): File {
    // 1. 先在应用私有外部存储目录写入一份保证永远存在且可读的物理文件（兼容所有安卓版本且免权限）
    val appPrivateDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
    if (appPrivateDir != null && !appPrivateDir.exists()) {
        appPrivateDir.mkdirs()
    }
    val fallbackFile = File(appPrivateDir ?: context.filesDir, fileName)
    try {
        fallbackFile.writeBytes(bytes)
    } catch (e: Exception) {
        e.printStackTrace()
    }

    // 2. Android 10+ (API 29+): 通过 MediaStore.Downloads 写入公共下载目录（系统级支持，无需申请危险存储权限）
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        try {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { os ->
                    os.write(bytes)
                    os.flush()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    } else {
        // 3. Android 9 及以下 (API < 29): 尝试向公共存储目录写入文件并刷新媒体库
        try {
            val publicDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!publicDownloads.exists()) {
                publicDownloads.mkdirs()
            }
            val publicFile = File(publicDownloads, fileName)
            publicFile.writeBytes(bytes)
            MediaScannerConnection.scanFile(context, arrayOf(publicFile.absolutePath), arrayOf("text/csv"), null)
            if (publicFile.exists() && publicFile.canRead()) {
                return publicFile
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // 4. 检查公共下载目录是否已经存在可读文件（包括 MediaStore 写入或者已有文件）
    val publicCandidate = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName)
    if (publicCandidate.exists() && publicCandidate.canRead()) {
        return publicCandidate
    }

    return fallbackFile
}

fun openFileManagerForFile(context: Context, file: File?) {
    // 1. 首选方案：尝试唤起系统下载管理的 Download 视图（适配大部分品牌手机与原生系统）
    try {
        val downloadIntent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (downloadIntent.resolveActivity(context.packageManager) != null) {
            context.startActivity(downloadIntent)
            return
        }
    } catch (_: Exception) {}

    // 2. 次选方案：通过 DocumentsUI/SAF 协议打开 Download 目录
    try {
        val downloadFolder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val uri = Uri.parse(downloadFolder.absolutePath)
        val folderIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "resource/folder")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (folderIntent.resolveActivity(context.packageManager) != null) {
            context.startActivity(folderIntent)
            return
        }
    } catch (_: Exception) {}

    // 3. 备选方案：通过 FileProvider 唤起系统应用选择器
    try {
        if (file != null && file.exists()) {
            val authority = "${context.packageName}.fileprovider"
            val contentUri = FileProvider.getUriForFile(context, authority, file)
            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(viewIntent, "选择文件管理器查看"))
            return
        }
    } catch (_: Exception) {}

    // 4. 兜底提示
    Toast.makeText(context, "课表模板已保存至「下载」目录，请打开手机自带「文件管理」查看喵！", Toast.LENGTH_LONG).show()
}

fun parseCourseCsv(inputStream: InputStream): String? {
    return parseCourseCsv(decodeCsvBytes(inputStream.readBytes()))
}

fun parseCourseCsv(csvText: String): String? {
    val rows = parseCsvRows(csvText)
    if (rows.isEmpty()) return null

    val header = rows.first().map { it.trim().removePrefix("\uFEFF") }
    val columnIndexes = mapOf(
        "name" to findColumn(header, "课程名称", "课程名", "课程"),
        "day" to findColumn(header, "星期", "周几"),
        "start" to findColumn(header, "开始节数", "开始节次", "开始节", "起始节次"),
        "end" to findColumn(header, "结束节数", "结束节次", "结束节", "终止节次"),
        "teacher" to findColumn(header, "老师", "教师", "授课教师"),
        "location" to findColumn(header, "地点", "上课地点"),
        "weeks" to findColumn(header, "周数", "周次", "上课周次")
    )

    if (columnIndexes.values.any { it < 0 }) return null

    val courses = JSONArray()
    rows.drop(1).forEach { row ->
        val name = row.valueAt(columnIndexes.getValue("name")).trim()
        val day = row.valueAt(columnIndexes.getValue("day")).toIntOrNull()
        val start = row.valueAt(columnIndexes.getValue("start")).toIntOrNull()
        val end = row.valueAt(columnIndexes.getValue("end")).toIntOrNull()
        val weeks = parseWeeks(row.valueAt(columnIndexes.getValue("weeks")))

        if (name.isBlank() || day == null || start == null || end == null || day !in 1..7 || start !in 1..17 || end !in start..17 || weeks.isEmpty()) return@forEach

        val nameHash = name.sumOf { it.code }
        courses.put(JSONObject().apply {
            put("name", name)
            put("location", row.valueAt(columnIndexes.getValue("location")).ifBlank { "未排地点" })
            put("teacher", row.valueAt(columnIndexes.getValue("teacher")))
            put("dayOfWeek", day)
            put("startNode", start)
            put("endNode", end)
            put("weeks", JSONArray(weeks).toString())
            put("colorTheme", courseColors[abs(nameHash) % courseColors.size])
        })
    }

    return courses.takeIf { it.length() > 0 }?.toString()
}

private fun findColumn(header: List<String>, vararg names: String): Int {
    return header.indexOfFirst { column -> names.any { it == column } }
}

private fun List<String>.valueAt(index: Int): String = getOrNull(index).orEmpty()

private fun parseWeeks(value: String): List<Int> {
    val result = mutableSetOf<Int>()
    val pattern = Regex("(\\d+)\\s*(?:-\\s*(\\d+))?\\s*(单周|双周|单|双|周)?")
    pattern.findAll(value).forEach { match ->
        val first = match.groupValues[1].toIntOrNull() ?: return@forEach
        val last = match.groupValues[2].toIntOrNull() ?: first
        val type = match.groupValues[3]
        if (first > last) return@forEach
        (first..last).filter { week ->
            when (type) {
                "单周", "单" -> week % 2 == 1
                "双周", "双" -> week % 2 == 0
                else -> true
            }
        }.filter { it in 1..50 }.forEach(result::add)
    }
    return result.sorted()
}

private fun parseCsvRows(text: String): List<List<String>> {
    val rows = mutableListOf<List<String>>()
    val row = mutableListOf<String>()
    val field = StringBuilder()
    var quoted = false
    var index = 0

    fun finishField() {
        row += field.toString()
        field.setLength(0)
    }

    while (index < text.length) {
        val character = text[index]
        when {
            character == '"' && quoted && index + 1 < text.length && text[index + 1] == '"' -> {
                field.append('"')
                index++
            }
            character == '"' -> quoted = !quoted
            character == ',' && !quoted -> finishField()
            (character == '\n' || character == '\r') && !quoted -> {
                finishField()
                if (row.any { it.isNotBlank() }) rows += row.toList()
                row.clear()
                if (character == '\r' && index + 1 < text.length && text[index + 1] == '\n') index++
            }
            else -> field.append(character)
        }
        index++
    }

    if (field.isNotEmpty() || row.isNotEmpty()) {
        finishField()
        if (row.any { it.isNotBlank() }) rows += row.toList()
    }
    return rows
}

private fun decodeCsvBytes(bytes: ByteArray): String {
    if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
        return bytes.copyOfRange(3, bytes.size).toString(Charsets.UTF_8)
    }
    return try {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        decoder.decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        Charset.forName("GB18030").decode(ByteBuffer.wrap(bytes)).toString()
    }
}
