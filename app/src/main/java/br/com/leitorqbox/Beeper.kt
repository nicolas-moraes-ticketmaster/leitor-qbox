package br.com.leitorqbox

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper

/**
 * Bipe alto gerado na hora (onda quadrada em volume máximo). Em ~2,7 kHz o alto-falante
 * pequeno do TC22 rende mais que o tom senoidal do ToneGenerator.
 */
object Beeper {

    private const val RATE = 44_100
    private val main = Handler(Looper.getMainLooper())

    /** Liberado: bipe duplo agudo. */
    fun success() = play(listOf(2700 to 130, 0 to 70, 2700 to 200))

    /** segmentos = (frequência Hz, duração ms); frequência 0 = silêncio. */
    private fun play(segments: List<Pair<Int, Int>>) {
        runCatching {
            val total = segments.sumOf { RATE * it.second / 1000 }
            val buf = ShortArray(total)
            var i = 0
            val fade = RATE / 500 // 2 ms de rampa para não estalar
            for ((freq, ms) in segments) {
                val n = RATE * ms / 1000
                for (k in 0 until n) {
                    val amp = if (freq == 0) 0.0 else minOf(1.0, minOf(k, n - 1 - k) / fade.toDouble())
                    val high = ((k.toLong() * 2 * freq) / RATE) % 2 == 0L
                    buf[i++] = (amp * (if (high) Short.MAX_VALUE.toInt() else -Short.MAX_VALUE.toInt())).toInt().toShort()
                }
            }
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(total * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(buf, 0, total)
            track.setVolume(AudioTrack.getMaxVolume())
            track.play()
            main.postDelayed({ runCatching { track.release() } }, segments.sumOf { it.second } + 200L)
        }
    }
}
