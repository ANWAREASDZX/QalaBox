package com.qalabox.emu.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.qalabox.emu.core.LogStore
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * عميل الصوت — يستقبل PCM خام من PulseAudio داخل نظام الجذر عبر
 * module-simple-protocol-tcp (منفذ 4712) ويشغله عبر AudioTrack.
 *
 * علاج تقطيع الصوت الشهير في ExaGear: تخزين مؤقت أكبر قابل للضبط
 * + إعادة اتصال تلقائية + تشغيل على خيط منفصل منخفض التأخير.
 *
 * v1.3: AudioTrack يُحتفظ به في مستوى الصف — stop() يوقف التشغيل فوراً
 * فيُفكّ أي حجب write قائم (كان الخيط قد يظل عالقاً ماسكاً المسار)؛
 * وكتابة كل مقطع تُفحص ضد النتيجة لتجاهل المقاطع المقطوعة.
 */
class AudioStreamClient(
    private val host: String = "127.0.0.1",
    private val port: Int = 4712,
    private val sampleRate: Int = 48000,
    private val bufferSizeBytes: Int = 4096
) {

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    @Volatile
    private var currentSocket: Socket? = null
    @Volatile
    private var track: AudioTrack? = null

    fun start() {
        if (running.get()) return
        running.set(true)
        thread = Thread({ loop() }, "QalaAudio").apply {
            priority = Thread.MAX_PRIORITY - 1
            start()
        }
    }

    fun stop() {
        running.set(false)
        // إيقاف المسار أولاً — يفكّ حجب write() حتى لو كان الخيط داخل الكتابة
        try { track?.pause() } catch (_: Exception) {}
        try { track?.flush() } catch (_: Exception) {}
        // إغلاق المقبس يفك حجب read() — Thread.interrupt وحده لا يكفي
        try { currentSocket?.close() } catch (_: Exception) {}
        currentSocket = null
        thread?.interrupt()
        thread = null
    }

    private fun loop() {
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        val tr = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minBuf, bufferSizeBytes * 2))
            .build()
        track = tr

        val chunk = ByteArray(bufferSizeBytes)
        while (running.get()) {
            try {
                Socket(host, port).use { sock ->
                    currentSocket = sock
                    sock.tcpNoDelay = true
                    val ins = sock.getInputStream()
                    LogStore.append("Audio", "متصل بخادم الصوت $host:$port")
                    if (tr.playState != AudioTrack.PLAYSTATE_PLAYING) tr.play()
                    while (running.get()) {
                        var read = 0
                        while (read < chunk.size) {
                            val n = ins.read(chunk, read, chunk.size - read)
                            if (n < 0) throw java.io.IOException("نهاية التدفق")
                            read += n
                        }
                        // النتيجة مهمة: المقطع المقطوع/المرفوض لا يُعاد عدّه نجاحاً
                        val written = tr.write(chunk, 0, read)
                        if (written < 0) throw java.io.IOException("فشل كتابة الصوت: $written")
                    }
                }
            } catch (e: Exception) {
                if (running.get()) {
                    LogStore.append("Audio", "انقطع الاتصال — إعادة المحاولة… (${e.message})")
                    try { tr.pause(); tr.flush() } catch (_: Exception) {}
                    try { Thread.sleep(1000) } catch (_: InterruptedException) {}
                }
            }
        }
        try {
            tr.stop()
            tr.release()
        } catch (_: Exception) {}
        track = null
    }
}
