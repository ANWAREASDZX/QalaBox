package com.qalabox.emu.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * مخزن السجلات — أحد أهم الإصلاحات عن ExaGear:
 * كل خطوة من خطوات التشغيل تُسجَّل في ملف قابل للتصدير،
 * بينما ExaGear كان يفشل صامتاً بدون أي تشخيص.
 */
object LogStore {

    private const val MAX_BUFFER_LINES = 1500

    private lateinit var logDir: File
    private var sessionFile: File? = null
    private val buffer = ArrayDeque<String>()

    fun init(context: Context) {
        logDir = File(context.filesDir, "logs").apply { mkdirs() }
        newSession()
    }

    fun newSession() {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        sessionFile = File(logDir, "session_$ts.log")
        append("SYS", "— جلسة جديدة —")
        // احتفظ بآخر 5 جلسات فقط — لا تتراكم ملفات لانهائية
        try {
            logDir.listFiles()?.sortedByDescending { it.name }?.drop(5)?.forEach { it.delete() }
        } catch (_: Exception) {}
    }

    fun sessionLogFile(): File? = sessionFile

    fun append(tag: String, message: String) {
        val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val line = "[$ts][$tag] $message"
        synchronized(buffer) {
            buffer.addLast(line)
            while (buffer.size > MAX_BUFFER_LINES) buffer.removeFirst()
            try {
                sessionFile?.appendText(line + "\n")
            } catch (_: Exception) {}
        }
        android.util.Log.d("QalaBox", "$tag: $message")
    }

    fun tail(lines: Int = 200): String {
        synchronized(buffer) {
            return buffer.takeLast(lines).joinToString("\n")
        }
    }

    fun exportIntent(context: Context): Intent? {
        val f = sessionFile ?: return null
        val uri: Uri = FileProvider.getUriForFile(
            context, context.packageName + ".fileprovider", f
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun clear() {
        logDir.listFiles()?.forEach { it.delete() }
        buffer.clear()
        newSession()
    }
}
