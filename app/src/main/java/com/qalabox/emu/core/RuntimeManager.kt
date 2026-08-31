package com.qalabox.emu.core

import android.content.Context
import android.net.Uri
import com.qalabox.emu.R
import com.qalabox.emu.util.Fs
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * مدير وقت التشغيل — يدير المكونات الثنائية (التي لا تُشحن مع التطبيق لأسباب
 * قانونية وحجمية): جذر النظام (Wine+Box86/64+Mesa)، proot، وخادم X.
 * التثبيت يتم من حزمة .qbxruntime عبر SAF — راجع docs/RUNTIME_BINARIES.md
 *
 * v1.2 — مستخرج حزم مرن: يقبل ZIP أو TAR أو TAR.GZ بأي بنية عملية
 * (غلاف مجلد، rootfs.tar بدل imagefs.tar، صيغ .tgz، لاحقات إعادة التنزيل
 * « (1)»، جذر متداخل داخل الـ tar) مع كشف الصيغة من البايتات الأولى،
 * تفريغ مرحلي، إعادة تثبيت نظيفة، وتحقق مفصّل (راجع FIXES.md 39–42).
 *
 * v1.3 — دورة تثبيت مضادة للارتداد (راجع FIXES.md 43–47):
 *   1) version.json يُتحقق من صلاحيته JSON فعلياً عند التثبيت، وقراءته
 *      تتحمل BOM — لم يعد ممكناً «نجاح» ثم فشل كشف لاحق لاختلاف المعيار.
 *   2) فك الجذر يتم في مجلد مرحلي ثم *تبديل ذرّي* (rename) — أي انقطاع
 *      في منتصف الفك يترك الجذر القديم سليماً بدل حالة ناقصة.
 *   3) بعد كل تثبيت يُطوى اختبار isInstalled الحقيقي نفسه — لا يُعلن
 *      النجاح أبداً إلا إذا كانت نفس الدالة التي ستُقرأ لاحقاً في
 *      الإعدادات ترى تثبيتاً كاملاً (ضمان اتساق الكتابة مع القراءة).
 *   4) مسح أذونات التنفيذ على مجلدات bin بعد التثبيت — حزم ZIP المصدرية
 *      لا تحمل بت التنفيذ فتكان يُثبَّت الجذر ثم يفشل الإطلاق.
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

    /** قراءة version.json — متحملة BOM وفضاءات، وتعيد null فقط للملف الغائب/التالف */
    fun version(context: Context): String? = try {
        val f = File(runtimeDir(context), "version.json")
        if (f.exists()) {
            val text = f.readText().trim().trimStart('\uFEFF')
            JSONObject(text).optString("version", "1.0").ifBlank { "1.0" }
        } else null
    } catch (e: Exception) { null }

    /** تحقق أن محتوى version.json قابل للتحليل JSON (يُستخدم أثناء التثبيت) */
    private fun isValidVersionJson(content: String): Boolean = try {
        JSONObject(content.trim().trimStart('\uFEFF'))
        true
    } catch (e: Exception) { false }

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
    private fun repairRootfsLayoutDir(rf: File) {
        if (!rf.isDirectory) return
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
     * منح بت التنفيذ لثنائيات الجذر في مواضع bin المعروفة.
     * لماذا: ملفات ZIP لا تحمل بت التنفيذ أصلاً — بدون هذا المسح يفشل
     * إطلاق wine/box64 بعد «تثبيت ناجح» إذا جاء الجذر من ZIP مباشرة.
     */
    private fun ensureRootfsExecBits(rootfs: File) {
        val binDirs = listOf(
            "bin", "sbin", "usr/bin", "usr/sbin", "usr/local/bin",
            "usr/local/sbin", "usr/libexec", "usr/games"
        )
        var count = 0
        for (rel in binDirs) {
            val d = File(rootfs, rel)
            if (!d.isDirectory) continue
            d.listFiles()?.forEach { f ->
                if (f.isFile && !f.setExecutable(true, false)) {
                    LogStore.append("Runtime", "تعذر منح تنفيذ: ${f.path}")
                } else count++
            }
        }
        LogStore.append("Runtime", "أذونات التنفيذ: $count ملفاً في مواضع bin")
    }

    /* ═══════════ المستخرج المرن (v1.2) ═══════════ */

    private enum class PkgKind { ZIP, GZIP, TAR, UNKNOWN }

    /** كشف الصيغة من البايتات الأولى: PK\x03\x04 (zip) / \x1f\x8b (gzip) / ustar (tar) */
    private fun detectKind(input: InputStream): Pair<PkgKind, InputStream> {
        val ins = BufferedInputStream(input, 1024 * 256)
        ins.mark(320)
        val head = ByteArray(300)
        val n = ins.read(head)
        ins.reset()
        val ok = n >= 262
        val kind = when {
            ok && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() &&
                    head[2] == 3.toByte() && head[3] == 4.toByte() -> PkgKind.ZIP
            ok && head[0] == 0x1f.toByte() && head[1] == 0x8b.toByte() -> PkgKind.GZIP
            ok && String(head, 257, 5, Charsets.US_ASCII) == "ustar" -> PkgKind.TAR
            else -> PkgKind.UNKNOWN
        }
        return kind to ins
    }

    /** تطبيع مقطع مسار: حذف لاحقات إعادة التنزيل « (1)» قبل الامتداد وتوحيد الحالة */
    private fun normSegment(s: String): String =
        s.replace(Regex("\\s*\\(\\d+\\)(\\.[^.]+)?$"), "$1").trim().lowercase()

    /**
     * تثبيت حزمة وقت التشغيل من URI (SAF).
     * الصيغة المرجعية (ZIP): imagefs.tar + proot + version.json
     * (+ xserver/ و dxwrapper/ اختيارياً). ويُقبل إضافياً: TAR أو TAR.GZ مباشرة،
     * غلاف مجلد واحد حول المحتويات، rootfs.tar / imagefs.tar.gz / .tgz،
     * لاحقات إعادة التنزيل، والجذر المتداخل داخل الـ tar.
     * المراحل: كشف الصيغة ← تفريغ مرحلي ← توجيه المكونات ← تحقق مبدئي ←
     * تنظيف القديم ← فك الجذر ← تسطيح ← تحقق بنيوي ← اختبار تنفيذ صادق.
     */
    fun installFromPackage(
        context: Context,
        uri: Uri,
        onProgress: (String) -> Unit
    ): Result<String> {
        var staging: File? = null
        try {
            onProgress("فتح الحزمة…")
            val resolver = context.contentResolver
            val input = resolver.openInputStream(uri)
                ?: return Result.failure(IllegalStateException("تعذر فتح الملف"))

            input.use { base ->
                val (kind, stream) = detectKind(base)
                LogStore.append("Runtime", "صيغة الحزمة المكتشفة: $kind")
                if (kind == PkgKind.UNKNOWN) {
                    return Result.failure(IllegalStateException(
                        "صيغة غير معروفة — المطلوب ZIP أو TAR أو TAR.GZ"))
                }

                // 1) تفريغ الحزمة كاملة إلى مجلد مرحلي (مسار موحد لكل الصيغ)
                val st = File(context.filesDir, "staging_pkg")
                Fs.deleteRecursively(st)
                st.mkdirs()
                staging = st
                onProgress("فك الحزمة…")
                val extracted = when (kind) {
                    PkgKind.ZIP -> Fs.unzipTo(stream, st)
                    PkgKind.GZIP -> GZIPInputStream(stream).use { Fs.extractTar(it, st) }
                    PkgKind.TAR -> Fs.extractTar(stream, st)
                    PkgKind.UNKNOWN -> false
                }
                if (!extracted) {
                    return Result.failure(IllegalStateException("تعذر فك الحزمة — قد تكون تالفة"))
                }

                // 2) توجيه المكونات الصغيرة أولاً — لا نمسّ الجذر القديم قبل اليقين
                onProgress("قراءة مكونات الحزمة…")
                var gotProot = false
                var gotVersion = false
                var versionValid = true
                var pendingImageFs: File? = null
                var pendingImageFsGz = false

                val files = st.walkTopDown().filter { it.isFile }.toList()
                for (f in files) {
                    val segs = try {
                        f.relativeTo(st).path.split(File.separatorChar).map { normSegment(it) }
                    } catch (e: Exception) { continue }
                    val bn = segs.last()
                    when {
                        bn == "imagefs.tar" || bn == "rootfs.tar" -> {
                            pendingImageFs = f; pendingImageFsGz = false
                        }
                        bn == "imagefs.tar.gz" || bn == "imagefs.tgz" ||
                                bn == "rootfs.tar.gz" || bn == "rootfs.tgz" -> {
                            pendingImageFs = f; pendingImageFsGz = true
                        }
                        bn == "proot" -> {
                            val out = prootBin(context)
                            out.parentFile?.mkdirs()
                            f.copyTo(out, overwrite = true)
                            out.setExecutable(true, false)
                            out.setReadable(true, false)
                            gotProot = true
                        }
                        bn == "version.json" -> {
                            // v1.3: تحقق محتوى JSON هنا — حزمة بversion.json تالفة
                            // تُرفض الآن برسالة واضحة بدل «نجاح» يرتد لاحقاً إلى غير مثبت
                            val content = try { f.readText() } catch (e: Exception) { "" }
                            if (isValidVersionJson(content)) {
                                val out = File(runtimeDir(context), "version.json")
                                out.parentFile?.mkdirs()
                                f.copyTo(out, overwrite = true)
                                gotVersion = true
                            } else {
                                versionValid = false
                                LogStore.append("Runtime", "version.json داخل الحزمة غير صالح JSON")
                            }
                        }
                        else -> {
                            // xserver/… و dxwrapper/… — بالتعرف عبر أي سلف في المسار
                            val xs = segs.indexOf("xserver")
                            val dw = segs.indexOf("dxwrapper")
                            val idx = if (xs >= 0) xs else dw
                            if (idx >= 0 && idx < segs.lastIndex) {
                                val rel = segs.subList(idx, segs.size).joinToString("/")
                                val out = File(runtimeDir(context), rel)
                                out.parentFile?.mkdirs()
                                f.copyTo(out, overwrite = true)
                                out.setExecutable(true, false)
                            }
                        }
                    }
                }

                // 3) تحديد مصدر نظام الجذر: ملف tar، أو مجلد imagefs/rootfs، أو الحزمة نفسها
                var rootfsSource: File? = pendingImageFs
                var sourceIsGz = pendingImageFsGz
                if (rootfsSource == null) {
                    val dirs = st.walkTopDown().filter { it.isDirectory }.take(400).toList()
                    val dirRoot = dirs.firstOrNull { d ->
                        val n = normSegment(d.name)
                        // أقواس صريحة — كانت الأسبقية تُشمل imagefs بلا بنية جذر (v1.3)
                        ((n == "imagefs" || n == "rootfs") &&
                                (File(d, "bin").exists() || File(d, "usr/bin").exists() ||
                                        File(d, "usr").exists() || File(d, "etc").exists()))
                    }
                    val bareRoot = if (File(st, "bin").exists() || File(st, "usr/bin").exists() ||
                        (File(st, "usr").exists() && File(st, "xserver").exists() == false &&
                                File(st, "dxwrapper").exists() == false) ||
                        File(st, "etc").exists()) st else null
                    rootfsSource = dirRoot ?: bareRoot
                    sourceIsGz = false
                }

                // 4) تحقق مبدئي — قبل العبث بالتثبيت القديم (فشل مبكر لا يدمّر شيئاً)
                val missing = mutableListOf<String>()
                if (rootfsSource == null) missing.add("imagefs (نظام الجذر)")
                if (!gotProot) missing.add("proot")
                if (!gotVersion) missing.add("version.json")
                if (!versionValid) missing.add("version.json (محتوى JSON غير صالح)")
                if (missing.isNotEmpty()) {
                    LogStore.append("Runtime", "حزمة ناقصة: ${missing.joinToString("، ")}")
                    return Result.failure(IllegalStateException(
                        "ناقص: ${missing.joinToString("، ")}"))
                }

                // 5) فك نظام الجذر — في مجلد *مرحلي* ثم تبديل ذرّي (v1.3):
                //    أي فشل/انقطاع أثناء الفك يترك الجذر القديم سليماً —
                //    لا توجد نافذة يظهر فيها «تثبيت ناقص» للمستخدم أبداً
                onProgress("فك نظام الجذر (قد يستغرق دقائق)…")
                val rd = rootfsDir(context)
                val stageRd = File(context.filesDir, "imagefs.staging")
                Fs.deleteRecursively(stageRd)
                stageRd.mkdirs()
                val src = rootfsSource!!
                if (src == st) {
                    st.listFiles()?.forEach { c ->
                        val dst = File(stageRd, c.name)
                        if (!c.renameTo(dst)) Fs.recursiveCopy(c, dst)
                    }
                } else if (!sourceIsGz) {
                    src.inputStream().buffered(1024 * 256).use { Fs.extractTar(it, stageRd) }
                } else {
                    GZIPInputStream(src.inputStream().buffered(1024 * 256)).use { Fs.extractTar(it, stageRd) }
                }

                // 6) تسطيح الجذر المتداخل (imagefs/imagefs/bin ← imagefs/bin) — داخل المرحلي
                repairRootfsLayoutDir(stageRd)

                // 7) تحقق بنيوي من الجذر *قبل* المساس بالتثبيت القديم
                val stageOk = File(stageRd, "bin").exists() || File(stageRd, "usr/bin").exists()
                if (!stageOk) {
                    Fs.deleteRecursively(stageRd)
                    LogStore.append("Runtime", "نظام الجذر غير مكتمل بعد الفك — أُبقي القديم سليماً")
                    return Result.failure(IllegalStateException(
                        context.getString(R.string.runtime_bad_rootfs)))
                }

                // 8) التبديل الذرّي: القديم → سلة ثم المرحلي → مكانه (rename على نفس نظام الملفات)
                val trash = File(context.filesDir, "imagefs.old")
                Fs.deleteRecursively(trash)
                if (rd.exists() && !rd.renameTo(trash)) {
                    // نادر: فشل rename — نحذف القديم ونكمل (المرحلي مكتمل بنيوياً)
                    Fs.deleteRecursively(rd)
                }
                if (!stageRd.renameTo(rd)) {
                    Fs.recursiveCopy(stageRd, rd)
                    Fs.deleteRecursively(stageRd)
                }
                Fs.deleteRecursively(trash)

                // 9) مسح أذونات التنفيذ — حزم ZIP المصدرية تسقط بت التنفيذ،
                //    بدونه يُثبَّت الجذر «بنجاح» ثم يفشل إطلاق wine/box64 فوراً
                onProgress("ضبط أذونات التنفيذ…")
                ensureRootfsExecBits(rd)

                // 10) اختبار تنفيذ صادق — يمنع «نجاحاً» وهمياً على أجهزة تمنع exec
                if (!probeProotExec(context)) {
                    return Result.failure(IllegalStateException(
                        context.getString(R.string.runtime_exec_blocked)))
                }

                // 11) تحقق ذاتي نهائي بنفس معيار القراءة لاحقاً في الإعدادات —
                //     يضمن أن ما سنكتبه الآن هو تماماً ما ستراه isInstalled غداً
                val selfDiag = diagnose(context)
                if (selfDiag != null || !isInstalled(context)) {
                    LogStore.append("Runtime", "تحقق ذاتي فاشل بعد التثبيت: $selfDiag")
                    return Result.failure(IllegalStateException(
                        selfDiag ?: context.getString(R.string.runtime_bad_rootfs)))
                }

                LogStore.append("Runtime",
                    "تم تثبيت وقت التشغيل: ${version(context)} — الجذر ${Fs.humanSize(Fs.dirSize(rd))}")
                onProgress("اكتمل التثبيت")
                return Result.success(version(context) ?: "1.0")
            }
        } catch (e: Exception) {
            LogStore.append("Runtime", "خطأ تثبيت: ${e.message}")
            return Result.failure(e)
        } finally {
            staging?.let { Fs.deleteRecursively(it) }
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
