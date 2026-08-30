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

        val track = AudioTrack.Builder()
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

        val chunk = ByteArray(bufferSizeBytes)
        while (running.get()) {
            try {
                Socket(host, port).use { sock ->
                    currentSocket = sock
                    sock.tcpNoDelay = true
                    val ins = sock.getInputStream()
                    LogStore.append("Audio", "متصل بخادم الصوت $host:$port")
                    track.play()
                    while (running.get()) {
                        var read = 0
                        while (read < chunk.size) {
                            val n = ins.read(chunk, read, chunk.size - read)
                            if (n < 0) throw java.io.IOException("نهاية التدفق")
                            read += n
                        }
                        track.write(chunk, 0, read)
                    }
                }
            } catch (e: Exception) {
                if (running.get()) {
                    LogStore.append("Audio", "انقطع الاتصال — إعادة المحاولة… (${e.message})")
                    track.pause()
                    track.flush()
                    try { Thread.sleep(1000) } catch (_: InterruptedException) {}
                }
            }
        }
        try {
            track.stop()
            track.release()
        } catch (_: Exception) {}
    }
}
