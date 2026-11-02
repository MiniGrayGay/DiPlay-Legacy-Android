package com.shilapi.xcertplay

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build

/**
 * The car's microphone, sent up the Android Auto link.
 *
 * The phone's voice assistant records from here rather than from its own microphone, because the
 * driver is speaking at the car, not at a phone in a pocket. It is a media **source** channel: the
 * phone opens it with `MicrophoneRequest{open=true}` and the car answers with a session id, then
 * streams PCM until the phone closes it or the assistant stops listening.
 *
 * Capture runs on its own thread and hands fixed-size chunks to the session's sender. Nothing here
 * blocks the caller: a stalled reader would otherwise back up into the protocol loop that also has
 * to acknowledge video frames.
 */
class AapMicrophone(private val log: (String) -> Unit = {}) {

    private var record: AudioRecord? = null
    private var thread: Thread? = null

    @Volatile
    private var running = false

    /** Chunks handed to the session, for the diagnostics line. */
    @Volatile
    var chunks = 0L
        private set

    val isRunning: Boolean get() = running

    /**
     * Opens the microphone at the format the phone asked for and starts streaming.
     *
     * [onChunk] is called on the capture thread with a byte array of exactly [chunkBytes] bytes
     * (the last one may be shorter). A capture device that cannot be opened is logged once and
     * leaves the session running without voice: losing the assistant is much better than losing
     * the projection.
     */
    @Synchronized
    fun start(
        sampleRate: Int,
        channels: Int,
        bitDepth: Int,
        onChunk: (ByteArray, Int) -> Unit,
    ) {
        if (running) return
        val channelMask =
            if (channels >= 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val encoding = if (bitDepth == 8) AudioFormat.ENCODING_PCM_8BIT else AudioFormat.ENCODING_PCM_16BIT
        val bytesPerFrame = (bitDepth / 8).coerceAtLeast(1) * channels.coerceAtLeast(1)
        // 20 ms per chunk: the same cadence Android Auto uses, and short enough that the assistant
        // hears the first syllable rather than the second.
        val chunkBytes = (sampleRate / 50).coerceAtLeast(160) * bytesPerFrame
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelMask, encoding)
        if (minBuffer <= 0) {
            log("microphone: device supports no ${sampleRate}Hz ${channels}ch capture (code $minBuffer)")
            return
        }
        val bufferSize = maxOf(minBuffer, chunkBytes * 4)
        val created = try {
            createRecord(sampleRate, channelMask, encoding, bufferSize)
        } catch (e: Exception) {
            log("microphone unavailable: ${e.message}")
            return
        }
        if (created.state != AudioRecord.STATE_INITIALIZED) {
            log("microphone did not initialise (state ${created.state})")
            runCatching { created.release() }
            return
        }
        record = created
        running = true
        thread = Thread({
            val chunk = ByteArray(chunkBytes)
            try {
                created.startRecording()
                log("microphone started ${sampleRate}Hz ${channels}ch ${bitDepth}bit")
                while (running) {
                    val read = created.read(chunk, 0, chunk.size)
                    if (read <= 0) {
                        // A zero read on a live device is a hiccup, not an end: a negative one is.
                        if (read < 0) {
                            log("microphone read failed ($read)")
                            break
                        }
                        continue
                    }
                    chunks++
                    runCatching { onChunk(chunk, read) }
                }
            } catch (e: Exception) {
                log("microphone stopped: ${e.message}")
            }
        }, "aap-mic").apply { isDaemon = true; start() }
    }

    @Synchronized
    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
        val current = record
        record = null
        if (current != null) {
            runCatching { current.stop() }
            runCatching { current.release() }
        }
    }

    private fun createRecord(
        sampleRate: Int,
        channelMask: Int,
        encoding: Int,
        bufferSize: Int,
    ): AudioRecord =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(encoding)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelMask)
                        .build(),
                )
                .setBufferSizeInBytes(bufferSize)
                .build()
        } else {
            @Suppress("DEPRECATION")
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                channelMask,
                encoding,
                bufferSize,
            )
        }
}
