package com.qalabox.emu.core.model

import org.json.JSONObject

/**
 * حاوية Wine مستقلة — مكافئة لمفهوم "الحاوية" في ExaGear:
 * كل حاوية = WINEPREFIX كامل + إعداداتها الخاصة + ملفات ألعابها.
 */
data class Container(
    val id: String,
    var name: String,
    var screenWidth: Int,
    var screenHeight: Int,
    var gpuDriver: String,   // turnip | virgl | llvm
    var dxWrapper: String,   // wined3d | dxvk | cncddraw
    var arch: String,        // win32 | win64
    val createdAt: Long
) {

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("screenWidth", screenWidth)
        put("screenHeight", screenHeight)
        put("gpuDriver", gpuDriver)
        put("dxWrapper", dxWrapper)
        put("arch", arch)
        put("createdAt", createdAt)
    }

    companion object {
        fun fromJson(o: JSONObject): Container = Container(
            id = o.getString("id"),
            name = o.getString("name"),
            screenWidth = o.getInt("screenWidth"),
            screenHeight = o.getInt("screenHeight"),
            gpuDriver = o.optString("gpuDriver", "turnip"),
            dxWrapper = o.optString("dxWrapper", "cncddraw"),
            arch = o.optString("arch", "win32"),
            createdAt = o.optLong("createdAt", System.currentTimeMillis())
        )
    }
}
