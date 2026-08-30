package com.qalabox.emu.util

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/** أدوات نظام ملفات عامة: فك الضغط، النسخ، بحث، تحرير INI */
object Fs {

    /** فك أرشيف ZIP مع حماية من هجوم Path Traversal (Zip Slip) */
    fun unzipTo(input: InputStream, destDir: File): Boolean {
        destDir.mkdirs()
        try {
            ZipInputStream(input.buffered(1024 * 256)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val outFile = File(destDir, entry.name)
                    // حماية Zip Slip
                    if (!outFile.canonicalPath.startsWith(destDir.canonicalPath + File.separator) &&
                        outFile.canonicalPath != destDir.canonicalPath) {
                        entry = zis.nextEntry
                        continue
                    }
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { fos -> zis.copyTo(fos, 1024 * 256) }
                    }
                    entry = zis.nextEntry
                }
            }
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    /**
     * مستخرج TAR مبسّط (ustar + GNU + PAX) — يُستخدم لفك imagefs.tar لوقت التشغيل.
     * يدعم:
     *  - الملفات والمجلدات والروابط الرمزية (ضرورية لصحة نظام الجذر glibc)
     *  - الأسماء الطويلة عبر ترويسة GNU 'L' و PAX 'x' (مسارات > 100 حرف شائعة في الجذر)
     *  - الروابط الصلبة عبر نسخ الملف الهدف
     * ويمنح كل ملف أذونات تنفيذ (مطلوب لثنائيات الجذر).
     */
    fun extractTar(input: InputStream, destDir: File): Boolean {
        destDir.mkdirs()
        val buf = ByteArray(512)
        var pendingLongName: String? = null   // GNU 'L'
        var pendingPaxPath: String? = null    // PAX 'x'
        try {
            var marker = input.read(buf, 0, 512)
            while (marker == 512) {
                val name = parseTarName(buf, 0, 100)
                val sizeStr = String(buf, 124, 12, Charsets.US_ASCII).trim('\u0000', ' ')
                val size = if (sizeStr.isEmpty()) 0L else sizeStr.toLong(8)
                val typeFlag = buf[156].toInt().toChar()
                val prefix = if (buf[345].toInt() != 0) parseTarName(buf, 345, 155) else ""
                var relPath = (if (prefix.isNotEmpty()) prefix + "/" + name else name)

                // نهاية الأرشيف (كتلة صفرية)
                if (name.isEmpty() && size == 0L && typeFlag == '\u0000') break

                when (typeFlag) {
                    // GNU LongName: البيانات التالية هي الاسم الحقيقي للإدخال القادم
                    'L' -> {
                        val nb = ByteArray(size.toInt().coerceAtMost(1 shl 20))
                        readExact(input, nb, size)
                        val endIdx = nb.indexOf(0).let { if (it < 0) nb.size else it }
                        pendingLongName = String(nb, 0, endIdx, Charsets.UTF_8)
                        skipTarData(input, size, consumed = size)
                        marker = input.read(buf, 0, 512)
                        continue
                    }
                    // PAX Extended Header: استخرج مسار= من البيانات
                    'x', 'X' -> {
                        val pb = ByteArray(size.toInt().coerceAtMost(1 shl 20))
                        readExact(input, pb, size)
                        pendingPaxPath = parsePaxPath(pb)
                        skipTarData(input, size, consumed = size)
                        marker = input.read(buf, 0, 512)
                        continue
                    }
                    'g' -> { // ترويسة PAX عامة — تجاهل محتواها
                        skipTarData(input, size)
                        marker = input.read(buf, 0, 512)
                        continue
                    }
                }

                if (pendingLongName != null) { relPath = pendingLongName; pendingLongName = null }
                if (pendingPaxPath != null) { relPath = pendingPaxPath; pendingPaxPath = null }
                if (relPath.isEmpty()) {
                    skipTarData(input, size)
                    marker = input.read(buf, 0, 512)
                    continue
                }

                val outFile = File(destDir, relPath)
                if (!outFile.canonicalPath.startsWith(destDir.canonicalPath + File.separator) &&
                    outFile.canonicalPath != destDir.canonicalPath) {
                    skipTarData(input, size)
                    marker = input.read(buf, 0, 512)
                    continue
                }

                when (typeFlag) {
                    '5' -> outFile.mkdirs()
                    '0', '\u0000', '7' -> {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { fos ->
                            copyExact(input, fos, size)
                        }
                        // تنفيذ للمالك يكفي — الملفات داخل تخزين التطبيق الخاص (نفس UID)
                        outFile.setExecutable(true)
                    }
                    '2' -> { // رابط رمزي — جوهري لصحة rootfs (ld-linux، lib*.so …)
                        val linkTarget = parseTarName(buf, 157, 100)
                        try {
                            outFile.parentFile?.mkdirs()
                            outFile.delete()
                            android.system.Os.symlink(linkTarget, outFile.absolutePath)
                        } catch (e: Exception) {
                            // بعض المجلدات قد تمنع — تجاهل بأمان
                        }
                        skipTarData(input, size)
                    }
                    '1' -> { // رابط صلب: انسخ الهدف إن وجد
                        val linkTarget = parseTarName(buf, 157, 100)
                        val src = File(destDir, linkTarget)
                        skipTarData(input, size)
                        if (src.exists()) {
                            outFile.parentFile?.mkdirs()
                            src.copyTo(outFile, overwrite = true)
                        }
                    }
                    else -> skipTarData(input, size) // أجهزة/أدلة أخرى: تجاهل بأمان
                }
                marker = input.read(buf, 0, 512)
            }
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    /** استخرج قيمة path= من ترويسة PAX */
    private fun parsePaxPath(data: ByteArray): String? {
        var i = 0
        while (i < data.size) {
            var sp = i
            while (sp < data.size && data[sp] != 32.toByte()) sp++
            if (sp >= data.size) break
            val lenStr = String(data, i, sp - i, Charsets.US_ASCII).toIntOrNull() ?: break
            if (lenStr <= 0 || i + lenStr > data.size) break
            val rec = String(data, sp + 1, i + lenStr - sp - 1, Charsets.UTF_8)
            if (rec.startsWith("path=")) return rec.substring(5)
            i += lenStr
        }
        return null
    }

    private fun readExact(input: InputStream, buf: ByteArray, size: Long) {
        var remaining = size
        var got = 0
        while (remaining > 0 && got < buf.size) {
            val n = input.read(buf, got, minOf(remaining, (buf.size - got).toLong()).toInt())
            if (n <= 0) break
            got += n
            remaining -= n
        }
    }

    private fun parseTarName(buf: ByteArray, off: Int, len: Int): String {
        var end = off
        var i = off
        while (i < off + len && buf[i].toInt() != 0) {
            if (buf[i].toInt() != 0) end = i + 1
            i++
        }
        return String(buf, off, end - off, Charsets.UTF_8)
    }

    private fun skipTarData(input: InputStream, size: Long, consumed: Long = 0) {
        // تخطِ الحشو حتى حد 512 (مع مراعاة ما قُرئ مسبقاً)
        var remaining = ((size + 511) / 512) * 512 - consumed
        val skip = ByteArray(8192)
        while (remaining > 0) {
            val n = input.read(skip, 0, minOf(remaining, skip.size.toLong()).toInt())
            if (n <= 0) break
            remaining -= n
        }
    }

    private fun copyExact(input: InputStream, out: FileOutputStream, size: Long) {
        var remaining = size
        val buf = ByteArray(1024 * 64)
        while (remaining > 0) {
            val want = minOf(remaining, buf.size.toLong()).toInt()
            val n = input.read(buf, 0, want)
            if (n <= 0) break
            out.write(buf, 0, n)
            remaining -= n
        }
        // تخطي الحشو حتى حد 512
        val pad = ((size + 511) / 512) * 512 - size
        if (pad > 0) {
            val padBuf = ByteArray(pad.toInt())
            var got = 0
            while (got < pad) {
                val n = input.read(padBuf, got, (pad - got).toInt())
                if (n <= 0) break
                got += n
            }
        }
    }

    /** نسخة مجلد/ملف تكرارية */
    fun recursiveCopy(src: File, dst: File) {
        if (src.isDirectory) {
            dst.mkdirs()
            src.listFiles()?.forEach { recursiveCopy(it, File(dst, it.name)) }
        } else {
            dst.parentFile?.mkdirs()
            src.inputStream().use { ins -> dst.outputStream().use { ins.copyTo(it, 1024 * 256) } }
        }
    }

    /** حذف تكراري آمن */
    fun deleteRecursively(file: File): Boolean {
        if (!file.exists()) return true
        if (file.isDirectory) {
            file.listFiles()?.forEach { deleteRecursively(it) }
        }
        return file.delete()
    }

    /** حجم مجلد تكراري بالبايت */
    fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0
        return dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
    }

    /** بحث تكراري عن ملف بالاسم (غير حساس لحالة الأحرف) */
    fun findFileByName(root: File, name: String, maxDepth: Int = 8): File? {
        if (maxDepth <= 0) return null
        val children = root.listFiles() ?: return null
        for (c in children) {
            if (c.isFile && c.name.equals(name, ignoreCase = true)) return c
        }
        for (c in children) {
            if (c.isDirectory) {
                val found = findFileByName(c, name, maxDepth - 1)
                if (found != null) return found
            }
        }
        return null
    }

    /**
     * تعديل مفتاح في ملف INI (يُستخدم لضبط ddraw.ini الخاص بـ cnc-ddraw
     * حسب متطلبات كل لعبة دون تعديل الملف الافتراضي).
     */
    fun setIniKey(iniFile: File, section: String, key: String, value: String) {
        val lines = if (iniFile.exists()) iniFile.readLines().toMutableList() else mutableListOf()
        val target = "[$section]"
        var inSection = false
        var replaced = false
        var sectionFound = false

        for (i in lines.indices) {
            val line = lines[i].trim()
            if (line.startsWith("[")) {
                if (inSection && !replaced) {
                    // المفتاح غير موجود داخل القسم — أضفه في نهايته
                    lines.add(i, "$key=$value")
                    replaced = true
                }
                inSection = line.equals(target, ignoreCase = true)
                if (inSection) sectionFound = true
                continue
            }
            if (inSection && line.substringBefore('=').trim().equals(key, ignoreCase = true)) {
                lines[i] = "$key=$value"
                replaced = true
            }
        }
        if (inSection && !replaced) {
            lines.add("$key=$value")
        }
        if (!sectionFound) {
            lines.add(target)
            lines.add("$key=$value")
        }
        iniFile.parentFile?.mkdirs()
        iniFile.writeText(lines.joinToString("\n"))
    }

    fun humanSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(java.util.Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(java.util.Locale.US, "%.1f MB", mb)
        return String.format(java.util.Locale.US, "%.2f GB", mb / 1024.0)
    }
}
