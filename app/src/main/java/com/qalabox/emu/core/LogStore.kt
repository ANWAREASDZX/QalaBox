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
 *
 * v1.3 أداء: مُنسّق الوقت يُخزَّن في ThreadLocal (كان يُبنى لكل سطر)،
 * والملف يبقى مفتوحاً بكاتب مخزّن (كان يُفتح/يُغلق لكل سطر — عنق زجاجة
 * حقيقي مع مئات أسطر wine في الثانية أثناء الإقلاع).
 */
object LogStore {

    private const val MAX_BUFFER_LINES = 1500

    private lateinit var logDir: File
    private var sessionFile: File? = null
    private val buffer = ArrayDeque<String>()
    private var writer: java.io.BufferedWriter? = null

    // SimpleDateFormat غير آمن خيطياً — نسخة لكل خيط (خيوط wine/xserver/audio تكتب معاً)
    private val tsFormat = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }

    fun init(context: Context) {
        logDir = File(context.filesDir, "logs").apply { mkdirs() }
        newSession()
    }

    fun newSession() {
        synchronized(buffer) {
            closeWriterLocked()
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            sessionFile = File(logDir, "session_$ts.log")
            append("SYS", "— جلسة جديدة —")
            // احتفظ بآخر 5 جلسات فقط — لا تتراكم ملفات لانهائية
            try {
                logDir.listFiles()?.sortedByDescending { it.name }?.drop(5)?.forEach { it.delete() }
            } catch (_: Exception) {}
        }
    }

    fun sessionLogFile(): File? = sessionFile

    private fun closeWriterLocked() {
        try { writer?.flush() } catch (_: Exception) {}
        try { writer?.close() } catch (_: Exception) {}
        writer = null
    }

    private fun writerLocked(): java.io.BufferedWriter? {
        val f = sessionFile ?: return null
        writer?.let { return it }
        return try {
            java.io.BufferedWriter(java.io.FileWriter(f, true), 16 * 1024).also { writer = it }
        } catch (_: Exception) { null }
    }

    fun append(tag: String, message: String) {
        val ts = tsFormat.get()?.format(Date()) ?: ""
        val line = "[$ts][$tag] $message"
        synchronized(buffer) {
            buffer.addLast(line)
            while (buffer.size > MAX_BUFFER_LINES) buffer.removeFirst()
            try {
                val w = writerLocked()
                w?.write(line)
                w?.newLine()
                w?.flush() // تفريغ لكل سطر — السجل حي حتى لو قُتل التطبيق
            } catch (_: Exception) {
                // ملف تالف/ممتلئ — أعد الفتح في السطر القادم
                closeWriterLocked()
            }
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
        synchronized(buffer) {
            closeWriterLocked()
            logDir.listFiles()?.forEach { it.delete() }
            buffer.clear()
            sessionFile = null
        }
        newSession()
    }
}
