package com.qalabox.emu

import android.app.Application
import com.qalabox.emu.core.LogStore

class QalaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        LogStore.init(this)
        LogStore.append("SYS", "قلعة بوكس v${BuildConfig.VERSION_NAME}")
    }
}
