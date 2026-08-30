package com.qalabox.emu.core

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.qalabox.emu.core.model.Container
import com.qalabox.emu.util.Fs
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * مدير الحاويات — إصلاح مباشر لمشاكل ExaGear:
 * - حاويات مستقلة قابلة للنسخ الاحتياطي/الاستعادة (كانت مستحيلة في ExaGear)
 * - إعادة تهيئة prefix عند التلف بضغطة واحدة
 * - drive_c ثابت لا "يتناسي" ملفات الحفظ بين الجلسات
 */
object ContainerManager {

    fun containersRoot(context: Context): File = File(context.filesDir, "containers")

    fun list(context: Context): MutableList<Container> {
        val out = ArrayList<Container>()
        val root = containersRoot(context)
        root.listFiles()?.sortedBy { it.name }?.forEach { dir ->
            val meta = File(dir, "meta.json")
            if (meta.exists()) {
                try {
                    out.add(Container.fromJson(JSONObject(meta.readText())))
                } catch (e: Exception) { /* تجاهل حاويات تالفة الميتا */ }
            }
        }
        return out
    }

    fun containerDir(context: Context, c: Container): File =
        File(containersRoot(context), c.id)

    fun prefixDir(context: Context, c: Container): File =
        File(containerDir(context, c), "prefix")

    fun driveC(context: Context, c: Container): File =
        File(prefixDir(context, c), "drive_c")

    fun saveMeta(context: Context, c: Container) {
        val dir = containerDir(context, c)
        dir.mkdirs()
        File(dir, "meta.json").writeText(c.toJson().toString())
    }

    /** إنشاء الحاوية ثم تهيئة Wine prefix (قد يستغرق دقيقة) */
    fun create(
        context: Context,
        name: String,
        screenWidth: Int,
        screenHeight: Int,
        gpuDriver: String,
        dxWrapper: String,
        arch: String,
        onProgress: (String) -> Unit
    ): Result<Container> {
        if (!RuntimeManager.isInstalled(context)) {
            return Result.failure(IllegalStateException("وقت التشغيل غير مثبت"))
        }
        val c = Container(
            id = UUID.randomUUID().toString().substring(0, 8),
            name = name.ifBlank { "حاوية" },
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            gpuDriver = gpuDriver,
            dxWrapper = dxWrapper,
            arch = arch,
            createdAt = System.currentTimeMillis()
        )
        containerDir(context, c).mkdirs()
        prefixDir(context, c).mkdirs()
        saveMeta(context, c)
        LogStore.append("Containers", "إنشاء حاوية: ${c.name} (${c.id}) ${c.arch} ${c.screenWidth}x${c.screenHeight}")

        onProgress("تهيئة Windows الافتراضي (Wine)…")
        val ok = Launcher.runInRootfs(
            context,
            listOf("/bin/bash", "/qalabox/container_init.sh", if (arch == "win64") "win64" else "win32"),
            container = c,
            timeoutSec = 300
        )
        return if (ok) {
            // مجلد ألعاب جاهز
            File(driveC(context, c), "Games").mkdirs()
            Result.success(c)
        } else {
            Result.failure(IllegalStateException("فشل تهيئة prefix — راجع السجل"))
        }
    }

    fun delete(context: Context, c: Container): Boolean {
        LogStore.append("Containers", "حذف الحاوية: ${c.name}")
        return Fs.deleteRecursively(containerDir(context, c))
    }

    fun resetPrefix(context: Context, c: Container): Boolean {
        val p = prefixDir(context, c)
        Fs.deleteRecursively(p)
        p.mkdirs()
        LogStore.append("Containers", "إعادة تهيئة prefix للحاوية ${c.name}")
        return Launcher.runInRootfs(
            context,
            listOf("/bin/bash", "/qalabox/container_init.sh", if (c.arch == "win64") "win64" else "win32"),
            container = c,
            timeoutSec = 300
        )
    }

    /** نسخة احتياطية ZIP إلى مجلد Downloads عبر MediaStore (API 29+) */
    fun backup(context: Context, c: Container, onProgress: (String) -> Unit): Result<Uri> {
        return try {
            val src = containerDir(context, c)
            onProgress("ضغط الحاوية…")
            val tmpZip = File(context.filesDir, "exports/${c.name}.qbcontainer")
            tmpZip.parentFile?.mkdirs()
            ZipOutputStream(tmpZip.outputStream().buffered(1024 * 256)).use { zos ->
                src.walkBottomUp().filter { it.isFile }.forEach { f ->
                    val rel = src.toPath().relativize(f.toPath()).toString()
                    zos.putNextEntry(ZipEntry(rel))
                    f.inputStream().use { it.copyTo(zos, 1024 * 128) }
                    zos.closeEntry()
                }
            }
            // كتابة إلى MediaStore
            val values = android.content.ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "${c.name}_${c.id}.qbcontainer")
                put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return Result.failure(IllegalStateException("تعذر إنشاء ملف الوجهة"))
            resolver.openOutputStream(uri)?.use { os ->
                tmpZip.inputStream().use { it.copyTo(os, 1024 * 128) }
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            tmpZip.delete()
            LogStore.append("Containers", "نسخة احتياطية للحاوية ${c.name} → Downloads")
            Result.success(uri)
        } catch (e: Exception) {
            LogStore.append("Containers", "خطأ النسخ الاحتياطي: ${e.message}")
            Result.failure(e)
        }
    }

    /** استعادة نسخة احتياطية من SAF */
    fun restore(context: Context, uri: Uri, onProgress: (String) -> Unit): Result<Container> {
        return try {
            onProgress("قراءة النسخة…")
            val newId = UUID.randomUUID().toString().substring(0, 8)
            val dest = File(containersRoot(context), newId)
            context.contentResolver.openInputStream(uri)?.use { input ->
                // النسخة QBcontainer هي ZIP بمسارات نسبية داخل مجلد الحاوية
                val metaExtracted = arrayListOf<File>()
                java.util.zip.ZipInputStream(input.buffered(1024 * 256)).use { zis ->
                    var e = zis.nextEntry
                    while (e != null) {
                        val rel = e.name
                        // حماية Zip Slip — تشمل فاصل المسار الصريح
                        if (rel.contains("..") ) { e = zis.nextEntry; continue }
                        val f = File(dest, rel)
                        if (!f.canonicalPath.startsWith(dest.canonicalPath + File.separator) &&
                            f.canonicalPath != dest.canonicalPath) { e = zis.nextEntry; continue }
                        if (e.isDirectory) f.mkdirs()
                        else {
                            f.parentFile?.mkdirs()
                            f.outputStream().use { zis.copyTo(it, 1024 * 128) }
                            if (e.name == "meta.json") metaExtracted.add(f)
                        }
                        e = zis.nextEntry
                    }
                }
                // تحديث المعرّف في meta
                if (metaExtracted.isNotEmpty()) {
                    val meta = metaExtracted[0]
                    val obj = JSONObject(meta.readText())
                    obj.put("id", newId)
                    obj.put("name", obj.optString("name") + " (مستعادة)")
                    meta.writeText(obj.toString())
                    val c = Container.fromJson(obj)
                    LogStore.append("Containers", "استعادة الحاوية ${c.name}")
                    return Result.success(c)
                }
            }
            Result.failure(IllegalStateException("ملف النسخة لا يحتوي meta.json"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun defaultContainer(context: Context): Container? = list(context).firstOrNull()

    /** وصف مختصر يظهر في بطاقة الحاوية */
    fun describe(c: Container): String {
        val driver = when (c.gpuDriver) {
            "turnip" -> "Turnip"
            "virgl" -> "VirGL"
            else -> "LLVMpipe"
        }
        val dx = when (c.dxWrapper) {
            "dxvk" -> "DXVK"
            "cncddraw" -> "cnc-ddraw"
            else -> "WineD3D"
        }
        return "${c.arch.uppercase()} • ${c.screenWidth}×${c.screenHeight} • $driver • $dx"
    }
}
