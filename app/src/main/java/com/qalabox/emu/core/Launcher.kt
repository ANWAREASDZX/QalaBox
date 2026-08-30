package com.qalabox.emu.core

import android.content.Context
import com.qalabox.emu.core.model.Container
import java.io.File

/**
 * مُشغّل برامج الضيف — يبني سلسلة الإقلاع:
 *   التطبيق → proot → bash → wine (داخل imagefs) → اللعبة
 * مع ضبط بيئة Box64/Box86، توجيه الأنوية الكبيرة، وربط مجلدات الحاوية.
 */
object Launcher {

    // الجلسة الحالية (عملية proot الأم) — للإنهاء النظيف
    @Volatile
    var currentProcess: Process? = null
        private set

    /** نسخ سكربتات الإقلاع من assets إلى داخل نظام الجذر */
    fun ensureScripts(context: Context) {
        val target = File(RuntimeManager.rootfsDir(context), "qalabox")
        target.mkdirs()
        val assets = try {
            context.assets.list("scripts") ?: emptyArray()
        } catch (e: Exception) { emptyArray() }
        for (name in assets) {
            val out = File(target, name)
            context.assets.open("scripts/$name").use { ins ->
                out.outputStream().use { ins.copyTo(it, 1024 * 64) }
            }
            out.setExecutable(true, false)
        }
    }

    /** بناء أمر proot الأساسي مع كل الربط اللازم */
    fun buildProotCommand(
        context: Context,
        container: Container?,
        extraBinds: List<String> = emptyList()
    ): List<String> {
        val proot = RuntimeManager.prootBin(context)
        val rootfs = RuntimeManager.rootfsDir(context)
        val sock = RuntimeManager.sockDir(context)
        val cmd = mutableListOf(
            proot.absolutePath,
            "--kill-on-exit",
            "-0",                                  // محاكاة root (لازم لـ wine)
            "-w", "/root",
            "--link2symlink",
            "-R", rootfs.absolutePath,
            "-b", "/dev",                          // /dev/kgsl و/dev/dri لتسريع الرسوميات
            "-b", "/proc",
            "-b", "/sys",
            "-b", "${sock.absolutePath}:/tmp/.X11-unix"   // مقبسا خادم X والجسر
        )
        if (container != null) {
            val cdir = ContainerManager.containerDir(context, container)
            cmd.add("-b"); cmd.add("${cdir.absolutePath}:/container")
        }
        extraBinds.chunked(2).forEach { pair ->
            if (pair.size == 2) { cmd.add("-b"); cmd.add("${pair[0]}:${pair[1]}") }
        }
        return cmd
    }

    /** بناء بيئة العمل المشتركة (تُمرر عبر proot إلى scripts/wine) */
    fun buildBaseEnv(context: Context, container: Container?): MutableMap<String, String> {
        val env = mutableMapOf(
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "HOME" to "/root",
            "TMPDIR" to "/tmp",
            "LANG" to "en_US.UTF-8",
            "PROOT_TMP_DIR" to context.cacheDir.absolutePath,
            "PROOT_NO_SECCOMP" to "1",
            "DISPLAY" to ":0",
            "WINEDEBUG" to "-all",
            "QB_DRIVER" to (container?.gpuDriver ?: "turnip"),
            "TERM" to "xterm"
        )
        if (container != null) {
            env["WINEPREFIX"] = "/container/prefix"
            env["WINEARCH"] = if (container.arch == "win64") "win64" else "win32"
        }
        return env
    }

    /**
     * خريطة إعدادات مترجم الأوامر حسب النمط المختار.
     * هذه الإعدادات هي جوهر تفوق الأداء على ExaGear القديم.
     */
    fun dynarecEnv(preset: String): Map<String, String> {
        return when (preset) {
            "fast" -> mapOf(
                "BOX64_DYNAREC_BIGBLOCK" to "1", "BOX86_DYNAREC_BIGBLOCK" to "1",
                "BOX64_DYNAREC_STRONGMEM" to "0", "BOX86_DYNAREC_STRONGMEM" to "0",
                "BOX64_DYNAREC_SAFEFLAGS" to "0", "BOX86_DYNAREC_SAFEFLAGS" to "0",
                "BOX64_DYNAREC_FASTNAN" to "1", "BOX86_DYNAREC_FASTNAN" to "1",
                "BOX64_DYNAREC_X87DOUBLE" to "0", "BOX86_DYNAREC_X87DOUBLE" to "0"
            )
            "compat" -> mapOf(
                "BOX64_DYNAREC_BIGBLOCK" to "0", "BOX86_DYNAREC_BIGBLOCK" to "0",
                "BOX64_DYNAREC_STRONGMEM" to "2", "BOX86_DYNAREC_STRONGMEM" to "2",
                "BOX64_DYNAREC_SAFEFLAGS" to "2", "BOX86_DYNAREC_SAFEFLAGS" to "2",
                "BOX64_DYNAREC_FASTNAN" to "0", "BOX86_DYNAREC_FASTNAN" to "0",
                "BOX64_DYNAREC_X87DOUBLE" to "1", "BOX86_DYNAREC_X87DOUBLE" to "1"
            )
            else -> mapOf( // balanced
                "BOX64_DYNAREC_BIGBLOCK" to "1", "BOX86_DYNAREC_BIGBLOCK" to "1",
                "BOX64_DYNAREC_STRONGMEM" to "1", "BOX86_DYNAREC_STRONGMEM" to "1",
                "BOX64_DYNAREC_SAFEFLAGS" to "1", "BOX86_DYNAREC_SAFEFLAGS" to "1",
                "BOX64_DYNAREC_FASTNAN" to "1", "BOX86_DYNAREC_FASTNAN" to "1",
                "BOX64_DYNAREC_X87DOUBLE" to "0", "BOX86_DYNAREC_X87DOUBLE" to "0"
            )
        }
    }

    /** اكتشاف الأنوية الكبيرة من ترددات النظام — علاج بطء الماوس القاتل في ExaGear */
    fun findBigCores(): List<Int> {
        val freqs = HashMap<Int, Long>()
        var i = 0
        while (File("/sys/devices/system/cpu/cpu$i").exists()) {
            val f = File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")
            if (f.exists()) {
                freqs[i] = f.readText().trim().toLongOrNull() ?: 0L
            }
            i++
        }
        return freqs.entries.sortedByDescending { it.value }.take((freqs.size + 1) / 2).map { it.key }
    }

    /** أمر taskset لتقييد العملية على أنوية معينة — يُدرج داخل proot
     *  (ثنائيات الجذر glibc لا يمكن تنفيذها مباشرة على bionic) */
    private fun affinitySuffix(context: Context, settings: SettingsStore): List<String> {
        val taskset = File(RuntimeManager.rootfsDir(context), "usr/bin/taskset")
        if (!taskset.exists()) return emptyList()
        if (settings.bigCores) {
            val big = findBigCores()
            if (big.isNotEmpty()) {
                val list = big.map { it.toString() }
                LogStore.append("Perf", "توجيه إلى الأنوية الكبيرة: $list")
                return listOf("/usr/bin/taskset", "-c", list.joinToString(","))
            }
        } else if (settings.cpuCores > 0) {
            val range = "0-${settings.cpuCores - 1}"
            return listOf("/usr/bin/taskset", "-c", range)
        }
        return emptyList()
    }

    /** تنفيذ أمر داخل نظام الجذر (متزامن) — للتهيئة وعمليات الصيانة */
    fun runInRootfs(
        context: Context,
        command: List<String>,
        container: Container? = null,
        extraEnv: Map<String, String> = emptyMap(),
        timeoutSec: Int = 120
    ): Boolean {
        ensureScripts(context)
        val cmd = buildProotCommand(context, container) +
                affinitySuffix(context, SettingsStore(context)) + command
        val env = buildBaseEnv(context, container)
        env.putAll(extraEnv)
        return try {
            val pb = ProcessBuilder(cmd)
            pb.environment().clear()
            pb.environment().putAll(env)
            pb.redirectErrorStream(true)
            LogStore.append("Exec", cmd.joinToString(" ").take(500))
            val p = pb.start()
            Thread {
                p.inputStream.bufferedReader().forEachLine { LogStore.append("rootfs", it) }
            }.start()
            val finished = p.waitFor(timeoutSec.toLong(), java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) p.destroyForcibly()
            finished && p.exitValue() == 0
        } catch (e: Exception) {
            LogStore.append("Exec", "فشل: ${e.message}")
            false
        }
    }

    /**
     * إطلاق اللعبة — غير متزامن، والعملية تبقى حية حتى إنهاء الجلسة.
     * يكتب سجل إخراج مباشر إلى LogStore (ميزة التشخيص الغائبة في ExaGear).
     */
    fun launchGame(
        context: Context,
        container: Container,
        onOutput: (String) -> Unit
    ): Result<Process> {
        ensureScripts(context)
        val settings = SettingsStore(context)
        val cmd = buildProotCommand(context, container) +
                affinitySuffix(context, settings) +
                listOf("/bin/bash", "/qalabox/startup.sh")
        val env = buildBaseEnv(context, container)

        // متغيرات الجلسة التي يقرؤها startup.sh
        env.putAll(dynarecEnv(settings.dynarecPreset))
        SessionStore.consume()?.let { session ->
            env.putAll(session.env)
        } ?: return Result.failure(IllegalStateException("لا توجد جلسة تشغيل مُعدّة"))

        return try {
            val pb = ProcessBuilder(cmd)
            pb.environment().clear()
            pb.environment().putAll(env)
            pb.redirectErrorStream(true)
            LogStore.append("Launch", cmd.joinToString(" ").take(800))
            val p = pb.start()
            currentProcess = p
            Thread {
                p.inputStream.bufferedReader().forEachLine { line ->
                    LogStore.append("wine", line)
                    onOutput(line)
                }
            }.start()
            Result.success(p)
        } catch (e: Exception) {
            LogStore.append("Launch", "فشل الإطلاق: ${e.message}")
            Result.failure(e)
        }
    }

    /** إنهاء جلسة اللعبة بترتيب لطيف ثم قسري
     *  destroy() على لينكس يرسل SIGTERM لعملية proot — و--kill-on-exit
     *  يكفل قتل شجرة wine والأبناء داخل الجذر */
    fun killSession() {
        val p = currentProcess ?: return
        currentProcess = null
        LogStore.append("Launch", "تم إنهاء الجلسة")
        Thread {
            try {
                p.destroy()               // SIGTERM — إتاحة حفظ حالة wineserver
                Thread.sleep(2500)
                if (p.isAlive) {
                    p.destroyForcibly()   // SIGKILL
                }
            } catch (_: Exception) {}
        }.start()
    }
}

/** ناقل جلسة واحدة بين شاشة الإعدادات (البروفايل) وشاشة المحاكاة */
data class LaunchSession(
    val env: Map<String, String>,
    val exeName: String
)

object SessionStore {
    @Volatile
    private var pending: LaunchSession? = null

    fun put(s: LaunchSession) { pending = s }
    fun consume(): LaunchSession? {
        val s = pending
        pending = null
        return s
    }

    @Suppress("unused")
    fun peek(): LaunchSession? = pending
}
