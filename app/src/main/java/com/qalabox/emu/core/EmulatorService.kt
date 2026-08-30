package com.qalabox.emu.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.qalabox.emu.R

/**
 * خدمة أمامية — علاج مباشر لمشكلة ExaGear القاتلة:
 * «اللعبة تتجمد أو تُقتل عندما تُطفئ الشاشة أو تنتقل لتطبيق آخر».
 */
class EmulatorService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        // مجموعة تُجمع تحتها كل جلسات المحاكاة
        val group = NotificationChannelGroup(GROUP_ID, getString(R.string.emu_service_channel))
        nm.createNotificationChannelGroup(group)
        val ch = NotificationChannel(
            CHANNEL_ID, getString(R.string.notif_channel_emu),
            NotificationManager.IMPORTANCE_LOW
        )
        ch.setShowBadge(false)
        ch.setGroup(GROUP_ID)
        nm.createNotificationChannel(ch)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra("title") ?: getString(R.string.app_name)
        val notif: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.emu_running, title))
            .setContentText(getString(R.string.emu_service_notif))
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, notif)

        // إبقاء المعالج حياً أثناء اللعب
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "QalaBox::emu").apply {
            acquire(4 * 60 * 60 * 1000L)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "qala_emu"
        const val GROUP_ID = "qala_emu_group"
        const val NOTIF_ID = 1001
    }
}
