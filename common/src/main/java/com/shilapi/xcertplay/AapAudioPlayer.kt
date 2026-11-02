package com.shilapi.xcertplay

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Plays the phone's PCM audio.
 *
 * The phone sends raw PCM on a media sink channel: `PCM`, 48 kHz, 16-bit, stereo unless it accepted
 * something else from the service list. There is no container and no decoder — the bytes go to an
 * [AudioTrack] in streaming mode.
 *
 * Writes are queued rather than performed on the caller's thread. The caller is the session's read
 * thread, which also has to acknowledge video frames; blocking it on a full audio buffer would
 * stall the video stream every time the car's mixer burped. The queue is bounded and **drops
 * instead of blocking** — audio that cannot be played on time is worth less than the video that
 * would be held up behind it.
 */
class AapAudioPlayer(private val log: (String) -> Unit = {}) {

    private var track: AudioTrack? = null
    private var thread: Thread? = null
    private var queue: ArrayBlockingQueue<Chunk>? = null

    @Volatile
    private var running = false

    /** Chunks dropped because the queue was full or the track was gone. */
    @Volatile
    var droppedChunks = 0L
        private set

    /** Chunks written to the track, for the diagnostics line. */
    @Volatile
    var playedChunks = 0L
        private set

    private class Chunk(val bytes: ByteArray)

    val isRunning: Boolean get() = running

    /** Starts (or restarts) playback at the phone's format. */
    @Synchronized
    fun start(sampleRate: Int, channels: Int, bitDepth: Int) {
        if (running) return
        val channelMask = if (channels >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val encoding = if (bitDepth == 8) AudioFormat.ENCODING_PCM_8BIT else AudioFormat.ENCODING_PCM_16BIT
        // A quarter second of buffer: enough to ride out a scheduling hiccup, short enough that a
        // pause on the phone does not leave sound playing for a noticeable moment afterwards.
        val bytesPerFrame = (bitDepth / 8).coerceAtLeast(1) * channels.coerceAtLeast(1)
        val bufferSize = (sampleRate / 4 * bytesPerFrame).coerceAtLeast(4096)
        val created = try {
            createTrack(sampleRate, channelMask, encoding, bufferSize)
        } catch (e: Exception) {
            log("audio track unavailable: ${e.message}")
            return
        }
        created.play()
        val chunks = ArrayBlockingQueue<Chunk>(AUDIO_QUEUE)
        queue = chunks
        running = true
        thread = Thread({
            while (running) {
                val chunk = try {
                    chunks.poll(200, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    null
                } ?: continue
                try {
                    created.write(chunk.bytes, 0, chunk.bytes.size)
                    playedChunks++
                } catch (e: Exception) {
                    log("audio write failed: ${e.message}")
                    break
                }
            }
        }, "aap-audio").apply { isDaemon = true; start() }
        log("audio started ${sampleRate}Hz ${channels}ch ${bitDepth}bit")
    }

    /** Queues one PCM chunk. Returns false when it was dropped to stay in real time. */
    fun write(data: ByteArray, offset: Int, length: Int) {
        if (!running) return
        val chunks = queue ?: return
        if (!chunks.offer(Chunk(data.copyOfRange(offset, offset + length)))) {
            droppedChunks++
        }
    }

    @Synchronized
    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
        queue?.clear()
        queue = null
        val current = track
        track = null
        if (current != null) {
            runCatching { current.stop() }
            runCatching { current.release() }
        }
    }

    private fun createTrack(sampleRate: Int, channelMask: Int, encoding: Int, bufferSize: Int): AudioTrack =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        // The car mixes this with its own sources; media is the right category for
                        // music and navigation both, since guidance arrives on a separate channel
                        // but the same stream type on this side.
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(encoding)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelMask)
                        .build(),
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } else {
            @Suppress("DEPRECATION")
            AudioTrack(
                AudioManager.STREAM_MUSIC,
                sampleRate,
                channelMask,
                encoding,
                bufferSize,
                AudioTrack.MODE_STREAM,
            )
        }

    private companion object {
        /** About half a second at the sizes involved; deeper only adds latency after a pause. */
        const val AUDIO_QUEUE = 32
    }
}
