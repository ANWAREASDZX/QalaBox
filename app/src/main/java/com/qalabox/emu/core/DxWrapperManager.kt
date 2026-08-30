package com.qalabox.emu.core

import android.content.Context
import com.qalabox.emu.core.model.GameProfile
import com.qalabox.emu.util.Fs
import java.io.File

/**
 * مدير أغلفة DirectX — علاج جذري لأشهر مشاكل ExaGear:
 * «الشاشة السوداء / شاشة مقلوبة / رسوم مختفية» في ألعاب DirectDraw الكلاسيكية.
 * يعتمد cnc-ddraw (مفتوح المصدر) الذي يترجم DirectDraw إلى OpenGL حديثة.
 */
object DxWrapperManager {

    /**
     * نشر الغلاف داخل مجلد اللعبة وإرجاع (متغيرات البيئة، تحذيرات)
     */
    fun deploy(
        context: Context,
        profile: GameProfile,
        gameDir: File
    ): Pair<MutableMap<String, String>, MutableList<String>> {
        val env = mutableMapOf<String, String>()
        val warnings = mutableListOf<String>()
        val wrapper = profile.dxWrapper

        when (wrapper) {
            "cncddraw" -> {
                // 1) ملف الإعدادات الأساسي من assets (نص — مشمول مع التطبيق)
                try {
                    context.assets.open("dxwrapper/cnc-ddraw/ddraw.ini").use { ins ->
                        val outFile = File(gameDir, "ddraw.ini")
                        outFile.outputStream().use { ins.copyTo(it) }
                    }
                } catch (e: Exception) {
                    warnings.add("ملف ddraw.ini الافتراضي مفقود من assets")
                }
                // 2) مكتبة ddraw.dll — تأتي من حزمة وقت التشغيل
                val dll = File(RuntimeManager.dxWrapperDir(context, "cnc-ddraw"), "ddraw.dll")
                if (dll.exists()) {
                    Fs.recursiveCopy(dll, File(gameDir, "ddraw.dll"))
                } else {
                    warnings.add("ddraw.dll غير موجود في حزمة وقت التشغيل (dxwrapper/cnc-ddraw) — انسخه يدوياً إلى مجلد اللعبة")
                }
                // 3) إجبار Wine على تحميل نسختنا المحلية أولاً
                //    (الصيغة الصحيحة: dll=ترتيب التحميل — n=native، b=builtin)
                env["WINEDLLOVERRIDES"] = "ddraw=n,b"
                // 4) تخصيصات البروفايل (دقة/مُصيّر/حد إطارات…)
                val ini = File(gameDir, "ddraw.ini")
                profile.cncDdrawOverrides.forEach { (k, v) ->
                    Fs.setIniKey(ini, "ddraw", k, v)
                }
            }

            "dxvk" -> {
                val dxvkDir = RuntimeManager.dxWrapperDir(context, "dxvk")
                val dlls = dxvkDir.listFiles()
                if (dlls.isNullOrEmpty()) {
                    warnings.add("مكتبات DXVK غير موجودة في حزمة وقت التشغيل — سيُستخدم WineD3D بدلاً منها")
                } else {
                    dlls.filter { it.extension == "dll" }.forEach {
                        Fs.recursiveCopy(it, File(gameDir, it.name))
                    }
                    // كل DLL يُعلَن بترتيبه الخاص، والفواصل بين الإعلانات «;»
                    env["WINEDLLOVERRIDES"] = "d3d9=n,b;d3d10core=n,b;d3d11=n,b;dxgi=n,b"
                }
            }

            else -> {
                // WineD3D المدمج مع Wine — لا يحتاج أي تجاوز
                // (لا تضبط المفتاح قيمةً فارغة حتى لا يتذمر Wine)
            }
        }
        LogStore.append("DxWrapper", "الغلاف: $wrapper، تجاوزات: ${env["WINEDLLOVERRIDES"]}")
        return Pair(env, warnings)
    }
}
