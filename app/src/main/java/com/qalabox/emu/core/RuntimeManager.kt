package com.qalabox.emu.core

import android.content.Context
import android.net.Uri
import com.qalabox.emu.R
import com.qalabox.emu.util.Fs
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * مدير وقت التشغيل — يدير المكونات الثنائية (التي لا تُشحن مع التطبيق لأسباب
 * قانونية وحجمية): جذر النظام (Wine+Box86/64+Mesa)، proot، وخادم X.
 * التثبيت يتم من حزمة .qbxruntime عبر SAF — راجع docs/RUNTIME_BINARIES.md
 */
object RuntimeManager {

    const val RUNTIME_EXT = ".qbxruntime"

    fun rootfsDir(context: Context): File = File(context.filesDir, "imagefs")
    fun runtimeDir(context: Context): File = File(context.filesDir, "runtime").apply { mkdirs() }
    fun sockDir(context: Context): File = File(context.filesDir, "xsock").apply { mkdirs() }
    fun dxWrapperDir(context: Context, name: String): File =
        File(runtimeDir(context), "dxwrapper/$name").apply { mkdirs() }

    fun prootBin(context: Context): File = File(runtimeDir(context), "proot")
    fun xserverBin(context: Context): File = File(File(runtimeDir(context), "xserver"), "qalax11")

    fun isInstalled(context: Context): Boolean {
        val p = prootBin(context)
        if (!p.exists()) return false
        // إصلاح ذاتي: استعادة بت التنفيذ إن فُقدت (بعض عمليات النسخ/الاستعادة تسقطها)
        if (!p.canExecute() && !p.setExecutable(true, false)) return false
        return hasRootfs(context) && version(context) != null
    }

    /** هل نظام الجذر مستكمل البنية؟ (bin أو usr/bin — حزمتنا تضع /bin حقيقياً) */
    fun hasRootfs(context: Context): Boolean {
        val rf = rootfsDir(context)
        return File(rf, "bin").exists() || File(rf, "usr/bin").exists()
    }

    fun version(context: Context): String? = try {
        val f = File(runtimeDir(context), "version.json")
        if (f.exists()) JSONObject(f.readText()).optString("version", "1.0") else null
    } catch (e: Exception) { null }

    /**
     * تشخيص سبب عدم الاعتراف بالتثبيت — null يعني أن كل شيء سليم.
     * يُعرض السبب الدقيق في الإعدادات بدل رسالة «لم يُثبّت» الغامضة.
     */
    fun diagnose(context: Context): String? {
        val p = prootBin(context)
        if (!p.exists()) return context.getString(R.string.runtime_missing_proot)
        if (!p.canExecute() && !p.setExecutable(true, false))
            return context.getString(R.string.runtime_exec_blocked)
        if (!hasRootfs(context)) return context.getString(R.string.runtime_missing_rootfs)
        if (version(context) == null) return context.getString(R.string.runtime_missing_version)
        return null
    }

    /**
     * اختبار تنفيذ فعلي لـ proot (proot --version).
     * يكشف قيود أندرويد W^X: التطبيقات ذات targetSdk ≥ 29 ممنوعة من تنفيذ
     * أي ملف من مجلد بياناتها على أندرويد 10+ — يظهر ذلك كـ IOException هنا.
     * بهذا لا يُعلن نجاح التثبيت أبداً على جهاز لا يستطيع تشغيل المحرك فيه.
     */
    fun probeProotExec(context: Context): Boolean {
        return try {
            val pb = ProcessBuilder(prootBin(context).absolutePath, "--version")
            pb.redirectErrorStream(true)
            val p = pb.start()
            val out = p.inputStream.bufferedReader().use { it.readText().take(300) }
            val finished = p.waitFor(8, TimeUnit.SECONDS)
            val ok = finished && (p.exitValue() == 0 || out.contains("proot", ignoreCase = true))
            if (!finished) p.destroyForcibly()
            LogStore.append("Runtime", "اختبار تنفيذ proot: ${if (ok) "نجح" else "فشل"} (${out.trim()})")
            ok
        } catch (e: Exception) {
            LogStore.append("Runtime", "اختبار تنفيذ proot فشل: ${e.message}")
            false
        }
    }

    /** تسطيح مجلد التفاف واحد — حزم أنشئت بـ tar من خارج الجذر (rootfs/bin/…) */
    private fun repairRootfsLayout(context: Context) {
        val rf = rootfsDir(context)
        if (!rf.isDirectory || hasRootfs(context)) return
        val kids = rf.listFiles() ?: return
        if (kids.size != 1 || !kids[0].isDirectory) return
        val wrapper = kids[0]
        val looksLikeRoot = File(wrapper, "bin").isDirectory ||
                File(wrapper, "usr/bin").isDirectory ||
                File(wrapper, "etc").isDirectory
        if (!looksLikeRoot) return
        LogStore.append("Runtime", "تسطيح مجلد التفاف داخل الجذر: ${wrapper.name}")
        var ok = true
        wrapper.listFiles()?.forEach { c ->
            ok = ok && c.renameTo(File(rf, c.name))
        }
        if (ok) wrapper.delete()
        else LogStore.append("Runtime", "تعذر التسطيح الكامل — قد تحتاج إعادة تثبيت الحزمة")
    }

    /**
     * تثبيت حزمة وقت التشغيل من URI (SAF).
     * بنية الحزمة (ZIP):
     *   imagefs.tar      — نظام جذر glibc مع Wine + Box86/64 + Mesa + PulseAudio
     *   proot            — ثنائي proot ثابت (arm64)
     *   xserver/qalax11  — خادم X مبني لأندرويد (واجهة: -socketdir -screen)
     *   version.json     — {"version":"...","notes":"..."}
     *   dxwrapper/cnc-ddraw/ddraw.dll (اختياري) — غلاف DirectDraw
     */
    fun installFromPackage(
        context: Context,
        uri: Uri,
        onProgress: (String) -> Unit
    ): Result<String> {
        return try {
            onProgress("فتح الحزمة…")
            val resolver = context.contentResolver
            resolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input.buffered(1024 * 256)).use { zis ->
                    var entry = zis.nextEntry
                    var gotImageFs = false; var gotProot = false; var gotVersion = false
                    while (entry != null) {
                        val name = entry.name.trimStart('/')
                        // حماية من مسارات التطفل (Zip Slip)
                        if (name.contains("..") || name.startsWith("/")) {
                            LogStore.append("Runtime", "إدخال مشبوه مرفوض: $name")
                            entry = zis.nextEntry
                            continue
                        }
                        when {
                            name == "imagefs.tar" && !entry.isDirectory -> {
                                onProgress("فك نظام الجذر (قد يستغرق دقائق)…")
                                Fs.extractTar(zis, rootfsDir(context))
                                gotImageFs = true
                            }
                            name == "proot" && !entry.isDirectory -> {
                                val out = prootBin(context)
                                out.parentFile?.mkdirs()
                                out.outputStream().use { zis.copyTo(it) }
                                out.setExecutable(true, false)
                                gotProot = true
                            }
                            name.startsWith("xserver/") && !entry.isDirectory -> {
                                val out = File(runtimeDir(context), name)
                                out.parentFile?.mkdirs()
                                out.outputStream().use { zis.copyTo(it) }
                                out.setExecutable(true, false)
                            }
                            name.startsWith("dxwrapper/") && !entry.isDirectory -> {
                                val out = File(runtimeDir(context), name)
                                out.parentFile?.mkdirs()
                                out.outputStream().use { zis.copyTo(it) }
                                out.setExecutable(true, false)
                            }
                            name == "version.json" && !entry.isDirectory -> {
                                File(runtimeDir(context), "version.json")
                                    .outputStream().use { zis.copyTo(it) }
                                gotVersion = true
                            }
                        }
                        entry = zis.nextEntry
                    }
                    if (!(gotImageFs && gotProot && gotVersion)) {
                        return Result.failure(IllegalStateException(
                            context.getString(R.string.runtime_invalid)))
                    }
                    // تحقق بنيوي من الجذر + إصلاح تلقائي لحزم ذات مجلد التفاف
                    repairRootfsLayout(context)
                    if (!hasRootfs(context)) {
                        LogStore.append("Runtime", "نظام الجذر غير مكتمل بعد الفك")
                        return Result.failure(IllegalStateException(
                            context.getString(R.string.runtime_bad_rootfs)))
                    }
                    // اختبار تنفيذ صادق — يمنع «نجاحاً» وهمياً على أجهزة تمنع exec
                    if (!probeProotExec(context)) {
                        return Result.failure(IllegalStateException(
                            context.getString(R.string.runtime_exec_blocked)))
                    }
                    LogStore.append("Runtime", "تم تثبيت وقت التشغيل: ${version(context)}")
                    return Result.success(version(context) ?: "1.0")
                }
            }
            Result.failure(IllegalStateException("تعذر فتح الملف"))
        } catch (e: Exception) {
            LogStore.append("Runtime", "خطأ تثبيت: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * تشغيل خادم العرض — بمسارين بالترتيب:
     * 1) خادم X ثنائي مخصص لأندرويد ضمن الحزمة (xserver/qalax11) — الأسرع
     *    العقد: `qalax11 :0 -socketdir <dir> -screen WxHx32 -noreset`
     *    ثم qalarender داخل الجذر عبر proot لالتقاط الإطارات (يتخطى Xvfb تلقائياً)
     * 2) الحزمة الافتراضية داخل الجذر: Xvfb + qalarender عبر proot
     *
     * يعيد كل العمليات المُشغّلة (لإيقافها كلها عند التنظيف) — فارغة عند الفشل.
     * لا يُعاد الاستدعاء حتى يظهر مقبس X0 فعلاً (أو انتهت المهلة).
     */
    fun startXServer(context: Context, width: Int, height: Int): List<Process> {
        val sd = sockDir(context)
        val xs = xserverBin(context)
        val procs = mutableListOf<Process>()
        try {
            if (xs.exists() && xs.canExecute()) {
                val pb = ProcessBuilder(
                    xs.absolutePath, ":0",
                    "-socketdir", sd.absolutePath,
                    "-screen", "${width}x${height}x32",
                    "-noreset"
                )
                pb.environment()["HOME"] = context.filesDir.absolutePath
                val p = pb.start()
                procs.add(p)
                Thread {
                    p.inputStream.bufferedReader().forEachLine { LogStore.append("XServer", it) }
                }.start()
                Thread {
                    p.errorStream.bufferedReader().forEachLine { LogStore.append("XServer-err", it) }
                }.start()
                LogStore.append("XServer", "خادم X المخصص بدأ (${width}x${height})")
                waitForXSocket(sd)
                // الجسر: qalarender داخل الجذر يلتقط من :0 — سكربت العرض
                // يتخطى Xvfb تلقائياً لأن المقبس جاهز
                Launcher.ensureScripts(context)
                val cmd = Launcher.buildProotCommand(context, null) +
                        listOf("/bin/bash", "/qalabox/start_render_stack.sh")
                val env = mutableMapOf(
                    "QB_FPS" to "60"
                )
                val rpb = ProcessBuilder(cmd)
                rpb.environment().clear()
                rpb.environment().putAll(Launcher.buildBaseEnv(context, null))
                rpb.environment().putAll(env)
                rpb.redirectErrorStream(true)
                val rp = rpb.start()
                procs.add(rp)
                Thread {
                    rp.inputStream.bufferedReader().forEachLine { LogStore.append("render", it) }
                }.start()
                LogStore.append("XServer", "جسر qalarender متصل بخادم X المخصص")
                return procs
            }

            // ── المسار الافتراضي: Xvfb + qalarender داخل الجذر ──
            Launcher.ensureScripts(context)
            val env = mutableMapOf(
                "QB_SCREEN_W" to width.toString(),
                "QB_SCREEN_H" to height.toString(),
                "QB_FPS" to "60"
            )
            val cmd = Launcher.buildProotCommand(context, null) +
                    listOf("/bin/bash", "/qalabox/start_render_stack.sh")
            val pb = ProcessBuilder(cmd)
            pb.environment().clear()
            pb.environment().putAll(Launcher.buildBaseEnv(context, null))
            pb.environment().putAll(env)
            pb.redirectErrorStream(true)
            LogStore.append("XServer", "تشغيل حزمة العرض داخل الجذر: Xvfb+qalarender (${width}x${height})")
            val p = pb.start()
            procs.add(p)
            Thread {
                p.inputStream.bufferedReader().forEachLine { LogStore.append("render", it) }
            }.start()
            waitForXSocket(sd)
            return procs
        } catch (e: Exception) {
            LogStore.append("XServer", "فشل تشغيل خادم العرض: ${e.message}")
            procs.forEach { try { it.destroy() } catch (_: Exception) {} }
            return emptyList()
        }
    }

    /**
     * انتظار ظهور مقبس العرض :0 (ملف X0 في مجلد المآخذ) حتى 15 ثانية.
     * يضمن أن wineboot/wine لن يفشلا لغياب العرض عند الإقلاع.
     */
    fun waitForXSocket(sd: File, timeoutMs: Long = 15000): Boolean {
        val xsock = File(sd, "X0")
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (xsock.exists()) {
                LogStore.append("XServer", "مقبس العرض جاهز")
                return true
            }
            try { Thread.sleep(200) } catch (_: InterruptedException) { return false }
        }
        LogStore.append("XServer", "انتهت مهلة انتظار مقبس العرض — نُكمل على أي حال")
        return false
    }
}
