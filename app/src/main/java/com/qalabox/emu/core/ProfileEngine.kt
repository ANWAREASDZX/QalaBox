package com.qalabox.emu.core

import android.content.Context
import com.qalabox.emu.core.model.Container
import com.qalabox.emu.core.model.GameProfile
import com.qalabox.emu.util.Fs
import com.qalabox.emu.util.PeParser
import java.io.File

/**
 * محرك البروفايلات — يستعد لكل لعبة قبل الإقلاع:
 * 1) يجد ملف exe تلقائياً داخل drive_c
 * 2) يحدد معماريته (32/64 بت) — ميزة ألغت الحاجة لنسخ ExaGear المختلفة
 * 3) ينشر غلاف DirectDraw المناسب مع تخصيصات اللعبة
 * 4) يجهز مفاتيح التسجيل
 * 5) يبني بيئة الإقلاع الكاملة
 */
object ProfileEngine {

    /** تحميل كل بروفايلات الألعاب المدمجة */
    fun loadProfiles(context: Context): List<GameProfile> {
        val out = ArrayList<GameProfile>()
        try {
            val names = context.assets.list("profiles") ?: emptyArray()
            for (n in names.sorted()) {
                if (!n.endsWith(".json")) continue
                try {
                    val text = context.assets.open("profiles/$n").bufferedReader().use { it.readText() }
                    val prof = GameProfile.fromJson(org.json.JSONObject(text))
                    out.add(prof)
                } catch (e: Exception) {
                    LogStore.append("Profiles", "خطأ تحميل بروفايل $n: ${e.message}")
                }
            }
        } catch (e: Exception) {
            LogStore.append("Profiles", "خطأ قراءة مجلد البروفايلات: ${e.message}")
        }
        return out
    }

    data class PreparedSession(
        val exeFile: File?,
        val exeWindowsPath: String?,
        val arch: String,
        val fixes: List<String>,
        val warnings: List<String>,
        val ready: Boolean
    )

    /** التحضير الكامل للجلسة ثم وضعها في SessionStore استعداداً لـ Launcher.launchGame */
    fun prepareSession(
        context: Context,
        container: Container,
        profile: GameProfile,
        customExeRelPath: String? = null
    ): PreparedSession {
        val driveC = ContainerManager.driveC(context, container)
        val warnings = mutableListOf<String>()

        // حماية: prefix محذوف/تالف — لا تكمل (كان ExaGear يفشل هنا بصمت)
        if (!driveC.exists()) {
            warnings.add("مجلد drive_c غير موجود في الحاوية — أعد تهيئة الـ prefix من تبويب الحاويات")
            return PreparedSession(null, null, PeParser.ARCH_UNKNOWN, emptyList(), warnings, false)
        }

        // ── 1) إيجاد الملف التنفيذي
        val exeFile: File? = customExeRelPath?.let {
            val f = File(driveC, it)
            if (f.exists()) f else null
        } ?: profile.exeCandidates.firstNotNullOfOrNull { Fs.findFileByName(driveC, it) }

        if (exeFile == null) {
            warnings.add("لم يُعثر على أي من: ${profile.exeCandidates.joinToString(", ")}")
            return PreparedSession(null, null, PeParser.ARCH_UNKNOWN, emptyList(), warnings, false)
        }

        // ── 2) المعمارية تلقائياً
        val arch = if (profile.arch == "auto") PeParser.detectArch(exeFile) else profile.arch
        LogStore.append("Profiles", "اللعبة: ${exeFile.name} | المعمارية: $arch")

        // ── 3) غلاف DirectX في مجلد اللعبة
        val gameDir = exeFile.parentFile ?: driveC
        val (dxEnv, dxWarnings) = DxWrapperManager.deploy(context, profile, gameDir)
        warnings.addAll(dxWarnings)

        // ── 4) مفاتيح التسجيل → سكربت يُنفذ قبل الإقلاع
        val regScript = File(ContainerManager.containerDir(context, container), "apply_registry.sh")
        if (profile.registry.isNotEmpty()) {
            val sb = StringBuilder("#!/bin/bash\nexport WINEPREFIX=/container/prefix\n")
            profile.registry.forEach { r ->
                // اقتباس آمن: استبدل كل ' بصيغة '\'' لتجاوز أي بيانات بها علامات اقتباس
                val key = r.key.replace("'", "'\\''")
                val data = r.data.replace("'", "'\\''")
                sb.append("wine reg add '").append(key)
                    .append("' /v ").append(r.value)
                    .append(" /t ").append(r.type)
                    .append(" /d '").append(data)
                    .append("' /f\n")
            }
            regScript.writeText(sb.toString())
            regScript.setExecutable(true, false)
            LogStore.append("Profiles", "سُجلّ ${profile.registry.size} مفتاح تسجيل للتنفيذ قبل الإقلاع")
        } else {
            // منع تنفيذ سكربت جلسة سابقة بقيم بروفايل آخر
            regScript.delete()
        }

        // ── 5) بناء بيئة الجلسة
        val relDir = exeFile.parentFile?.toRelativeString(driveC)?.replace('\\', '/') ?: ""
        val winPath = "C:\\" + exeFile.toRelativeString(driveC).replace('/', '\\')

        val env = HashMap<String, String>()
        env.putAll(dxEnv)
        env.putAll(profile.env)
        env["QB_EXE_WIN"] = winPath
        env["QB_EXE_REL_DIR"] = relDir
        env["QB_WINEPREFIX"] = "/container/prefix"
        // المعمارية المكتشفة توجّه اختيار wine/wine64 داخل startup.sh
        env["QB_ARCH"] = arch
        // v1.4: أبعاد سطح مكتب Wine الافتراضي (startup.sh يبنيه تلقائياً) —
        // يمنع فشل تغيير الدقة على Xvfb (سبب شائع لعدم ظهور اللعبة).
        // البروفايل يمكنه تعطيله بـ "QB_DESKTOP": "0" في env
        if (!profile.env.containsKey("QB_DESKTOP")) env["QB_DESKTOP"] = "1"
        env["QB_SCREEN_W"] = container.screenWidth.coerceAtLeast(320).toString()
        env["QB_SCREEN_H"] = container.screenHeight.coerceAtLeast(240).toString()
        // نمط الأداء الخاص باللعبة (من الضغط المطوّل) يتجاوز الإعداد العام —
        // بيئة الجلسة تُطبّق في launchGame بعد بيئة الإعدادات العامة
        if (profile.preset != "balanced") {
            env.putAll(Launcher.dynarecEnv(profile.preset))
        }
        val prefs = context.getSharedPreferences("profile_prefs", 0)
        prefs.getString("profile_preset_${profile.id}", null)?.let { preset ->
            env.putAll(Launcher.dynarecEnv(preset))
            LogStore.append("Profiles", "نمط أداء خاص باللعبة: $preset")
        }
        if (profile.launchArgs.isNotBlank()) env["QB_ARGS"] = profile.launchArgs

        SessionStore.put(LaunchSession(env, exeFile.name))
        LogStore.append("Profiles", "جلسة جاهزة: $winPath | إصلاحات: ${profile.fixes.size}")

        return PreparedSession(
            exeFile = exeFile,
            exeWindowsPath = winPath,
            arch = arch,
            fixes = profile.fixes,
            warnings = warnings,
            ready = true
        )
    }
}
