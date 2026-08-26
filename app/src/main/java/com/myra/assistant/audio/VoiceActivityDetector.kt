package com.myra.assistant.audio

import kotlin.math.min
import kotlin.math.sqrt

/**
 * Lightweight energy-based Voice Activity Detection. Computes a normalized RMS
 * amplitude (0..1) from a PCM16 buffer, used both to drive the animated orb /
 * waveform and to decide whether the user is speaking.
 */
class VoiceActivityDetector(private val speechThreshold: Double = 0.045) {

    fun amplitude(pcm: ByteArray): Float {
        if (pcm.size < 2) return 0f
        var sum = 0.0
        var count = 0
        var i = 0
        while (i + 1 < pcm.size) {
            val sample = (pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)
            val normalized = sample / 32768.0
            sum += normalized * normalized
            count++
            i += 2
        }
        if (count == 0) return 0f
        val rms = sqrt(sum / count)
        return min(1.0, rms * 3.5).toFloat()
    }

    fun isSpeech(pcm: ByteArray): Boolean = amplitude(pcm) > speechThreshold
}
