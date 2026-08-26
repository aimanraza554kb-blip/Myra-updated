package com.myra.assistant.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.myra.assistant.util.Constants
import com.myra.assistant.util.Logger
import java.util.concurrent.LinkedBlockingQueue

/**
 * Streams 24kHz mono PCM16 audio returned by Gemini to the speaker through a
 * queue so chunks play back smoothly. Supports interruption (flush) and mute.
 */
class AudioPlayer {

    @Volatile
    var muted: Boolean = false
        set(value) {
            field = value
            track?.setVolume(if (value) 0f else 1f)
        }

    private var track: AudioTrack? = null
    private val queue = LinkedBlockingQueue<ByteArray>()
    private var thread: Thread? = null
    @Volatile private var playing = false

    fun start() {
        if (playing) return
        val minBuffer = AudioTrack.getMinBufferSize(
            Constants.OUTPUT_SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(Constants.OUTPUT_SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            // ~250ms of headroom: large enough to avoid underruns, small enough
            // to keep MYRA's voice responsive (lower output latency).
            .setBufferSizeInBytes(maxOf(minBuffer, Constants.OUTPUT_SAMPLE_RATE / 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        audioTrack.setVolume(if (muted) 0f else 1f)
        audioTrack.play()
        track = audioTrack
        playing = true

        thread = Thread {
            while (playing) {
                val chunk = try { queue.take() } catch (e: InterruptedException) { break }
                if (chunk.isEmpty()) continue
                if (!muted) track?.write(chunk, 0, chunk.size)
            }
        }.apply { name = "MyraSpeaker"; start() }
        Logger.i(TAG, "Playback started")
    }

    fun enqueue(pcm: ByteArray) {
        if (playing) queue.offer(pcm)
    }

    /** Interrupt: drop everything queued and reset the track immediately. */
    fun flush() {
        queue.clear()
        track?.let {
            try {
                it.pause()
                it.flush()
                it.play()
            } catch (_: Exception) {}
        }
        Logger.d(TAG, "Playback flushed (interrupt)")
    }

    fun stop() {
        playing = false
        queue.clear()
        queue.offer(ByteArray(0)) // unblock take()
        thread?.interrupt()
        thread = null
        track?.let {
            try { it.pause(); it.flush(); it.stop() } catch (_: Exception) {}
            it.release()
        }
        track = null
        Logger.i(TAG, "Playback stopped")
    }

    companion object { private const val TAG = "AudioPlayer" }
}
