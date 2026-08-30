package com.qalabox.emu.core.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * بروفايل لعبة — قلب نظام "الإصلاحات التلقائية":
 * كل بروفايل يضم إعدادات البيئة، غلاف DirectDraw، مفاتيح التسجيل،
 * وقائمة الإصلاحات المعروفة التي تُطبَّق قبل الإقلاع.
 */
data class RegistryEntry(val key: String, val value: String, val type: String, val data: String)

data class GameProfile(
    val id: String,
    val name: String,
    val nameAr: String,
    val icon: String,
    val exeCandidates: List<String>,
    val arch: String,                       // x86 | x64 | auto
    val env: Map<String, String>,           // متغيرات بيئة إضافية (Box64/Box86 وغيرها)
    val dxWrapper: String,                  // wined3d | dxvk | cncddraw
    val cncDdrawOverrides: Map<String, String>,
    val registry: List<RegistryEntry>,
    val launchArgs: String,
    val fixes: List<String>,                // وصف الإصلاحات المطبقة (لعرضها في الواجهة)
    val preset: String,                     // fast | balanced | compat
    val notesAr: String
) {
    companion object {
        fun fromJson(o: JSONObject): GameProfile {
            fun mapOf(j: JSONObject): Map<String, String> {
                val m = HashMap<String, String>()
                j.keys().forEach { k -> m[k] = j.optString(k) }
                return m
            }
            fun strList(j: JSONArray): List<String> = (0 until j.length()).map { j.getString(it) }

            val reg = ArrayList<RegistryEntry>()
            val regArr = o.optJSONArray("registry") ?: JSONArray()
            for (i in 0 until regArr.length()) {
                val r = regArr.getJSONObject(i)
                reg.add(RegistryEntry(r.getString("key"), r.getString("value"), r.optString("type", "REG_SZ"), r.getString("data")))
            }
            return GameProfile(
                id = o.getString("id"),
                name = o.getString("name"),
                nameAr = o.optString("nameAr", o.getString("name")),
                icon = o.optString("icon", "🏰"),
                exeCandidates = strList(o.getJSONArray("exeCandidates")),
                arch = o.optString("arch", "auto"),
                env = mapOf(o.optJSONObject("env") ?: JSONObject()),
                dxWrapper = o.optString("dxWrapper", "cncddraw"),
                cncDdrawOverrides = mapOf(o.optJSONObject("cnc_ddraw_overrides") ?: JSONObject()),
                registry = reg,
                launchArgs = o.optString("launchArgs", ""),
                fixes = strList(o.optJSONArray("fixes") ?: JSONArray()),
                preset = o.optString("preset", "balanced"),
                notesAr = o.optString("notesAr", "")
            )
        }
    }
}
