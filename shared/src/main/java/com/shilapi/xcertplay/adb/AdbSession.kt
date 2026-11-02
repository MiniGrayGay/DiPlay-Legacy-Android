package com.shilapi.xcertplay.adb

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyPair
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * An ADB client that speaks to a phone's `adbd`, with several streams open at once.
 *
 * [LocalAdb] is the older, one-command-at-a-time client for the head unit's own adbd. Mirroring needs
 * more than that: a 60-frame-per-second H.264 pipe and a touch pipe must not take turns, or a swipe
 * stalls while the encoder is mid-frame. So this class runs one reader thread that demultiplexes every
 * stream by local id, and lets each stream block on its own buffer.
 *
 * Flow control follows `adb/protocol.txt`: every `WRTE` is acknowledged with an `OKAY`, and a writer
 * waits for that `OKAY` before sending the next chunk. Without the wait, adbd's own receive buffer
 * overruns and the phone drops frames on a fast cable.
 */
class AdbSession(
    private val transport: AdbTransport,
    private val key: KeyPair,
    private val log: (String) -> Unit = {},
) : Closeable {

    enum class State {
        /** The handshake is running. */
        CONNECTING,

        /** adbd asked for a key the phone has never seen. The driver has to approve it on the phone. */
        AWAITING_APPROVAL,

        READY,

        /** adbd on this device only accepts TLS (`STLS`), as wireless debugging does on Android 11+. */
        UNSUPPORTED,

        CLOSED,
    }

    @Volatile
    var state: State = State.CONNECTING
        private set

    /** `device::ro.product.model=…`, once adbd has said hello. */
    @Volatile
    var deviceBanner: String = ""
        private set

    /** Largest payload the phone accepts in one packet; adbd's own announcement wins over the default. */
    @Volatile
    private var maxPayload: Int = ADB_DEFAULT_MAX_PAYLOAD

    private val input: InputStream = transport.input
    private val output: OutputStream = transport.output

    private val streams = ConcurrentHashMap<Int, AdbStream>()
    private val nextLocalId = AtomicInteger(1)
    private val writeLock = Any()
    private val closed = AtomicBoolean(false)

    private var reader: Thread? = null

    /** Set once the signature was refused, so the second challenge offers the key instead. */
    private var offeredSignature = false

    /**
     * Runs the handshake and starts the reader. Returns the state it reached; only [State.READY] can
     * carry streams.
     */
    fun connect(timeoutMillis: Int = CONNECT_TIMEOUT_MS, approvalTimeoutMillis: Int = APPROVAL_TIMEOUT_MS): State {
        if (state == State.READY) return state
        return try {
            send(AdbPacket(AdbPacket.CNXN, AdbPacket.VERSION, ADB_DEFAULT_MAX_PAYLOAD, HOST_BANNER))
            var deadline = System.currentTimeMillis() + timeoutMillis
            while (System.currentTimeMillis() < deadline) {
                val packet = AdbPacket.read(input)
                when {
                    packet.command == AdbPacket.STLS -> {
                        state = State.UNSUPPORTED
                        return state
                    }
                    packet.command == AdbPacket.CNXN -> {
                        deviceBanner = String(packet.payload, Charsets.UTF_8).trim('\u0000')
                        // adbd's announcement is the value it is willing to receive, not to send.
                        maxPayload = packet.arg1.takeIf { it in MIN_MAX_PAYLOAD..MAX_MAX_PAYLOAD }
                            ?: ADB_DEFAULT_MAX_PAYLOAD
                        state = State.READY
                        startReader()
                        log("adb ready: $deviceBanner")
                        return state
                    }
                    packet.command == AdbPacket.AUTH && packet.arg0 == AdbPacket.AUTH_TOKEN -> {
                        if (!offeredSignature) {
                            offeredSignature = true
                            send(AdbPacket(AdbPacket.AUTH, AdbPacket.AUTH_SIGNATURE, 0, AdbKeys.sign(packet.payload, key.private)))
                        } else {
                            // Second challenge: this phone has never seen DiPlay's key. Offering the
                            // public key makes adbd raise its "Allow USB debugging?" dialog, which the
                            // driver has to answer on the phone — so the wait gets much longer than a
                            // silent phone deserves.
                            state = State.AWAITING_APPROVAL
                            deadline = System.currentTimeMillis() + approvalTimeoutMillis
                            send(AdbPacket(AdbPacket.AUTH, AdbPacket.AUTH_PUBLIC_KEY, 0, AdbKeys.publicKeyMessage(key.public)))
                        }
                    }
                    else -> throw IOException("unexpected ADB packet 0x${Integer.toHexString(packet.command)}")
                }
            }
            // A pending approval is not a failure: the driver may still be looking at the phone.
            if (state == State.AWAITING_APPROVAL) state else State.CLOSED
        } catch (error: Throwable) {
            log("adb handshake failed: ${error.message}")
            state = State.CLOSED
            closeQuietly()
            state
        }
    }

    /** Opens a service such as `exec:screenrecord …` or `shell:id`. Null if adbd refuses. */
    fun open(service: String, timeoutMillis: Int = OPEN_TIMEOUT_MS): AdbStream? {
        if (state != State.READY) return null
        val localId = nextLocalId.getAndIncrement()
        val stream = AdbStream(localId)
        streams[localId] = stream
        return try {
            send(AdbPacket(AdbPacket.OPEN, localId, 0, "$service\u0000".toByteArray(Charsets.UTF_8)))
            if (!stream.awaitOpen(timeoutMillis)) {
                streams.remove(localId)
                stream.abandon()
                log("adb open refused: $service")
                null
            } else {
                stream
            }
        } catch (error: IOException) {
            streams.remove(localId)
            stream.abandon()
            log("adb open failed: ${error.message}")
            null
        }
    }

    /** Runs a short command and returns its stdout, or null if the link is gone. */
    fun exec(command: String, timeoutMillis: Int = EXEC_TIMEOUT_MS): String? {
        val stream = open("exec:$command", timeoutMillis) ?: return null
        return try {
            val bytes = stream.readFully(timeoutMillis)
            String(bytes, Charsets.UTF_8).trim()
        } catch (_: IOException) {
            null
        } finally {
            stream.close()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        state = State.CLOSED
        streams.values.forEach { it.abandon() }
        streams.clear()
        runCatching { transport.close() }
        reader?.interrupt()
        reader = null
    }

    private fun startReader() {
        val thread = Thread({ readLoop() }, "adb-session")
        thread.isDaemon = true
        // A blocked bulk read cannot be interrupted, so the thread is left to die with the transport.
        reader = thread
        thread.start()
    }

    private fun readLoop() {
        try {
            while (!closed.get()) {
                val packet = AdbPacket.read(input)
                dispatch(packet)
            }
        } catch (error: Throwable) {
            if (!closed.get()) {
                val reason = if (error is IOException) error.message else error.toString()
                log("adb link lost: $reason")
            }
        } finally {
            state = State.CLOSED
            streams.values.forEach { it.abandon() }
            streams.clear()
        }
    }

    private fun dispatch(packet: AdbPacket) {
        when (packet.command) {
            AdbPacket.OKAY -> streams[packet.arg1]?.onAcknowledged(packet.arg0)
            AdbPacket.WRTE -> {
                val stream = streams[packet.arg1]
                if (stream == null) {
                    // A stream this side already closed: drain the phone's copy instead of stalling it.
                    send(AdbPacket(AdbPacket.CLSE, packet.arg1, packet.arg0, NO_PAYLOAD))
                } else {
                    stream.offer(packet.payload)
                    send(AdbPacket(AdbPacket.OKAY, packet.arg1, packet.arg0, NO_PAYLOAD))
                }
            }
            AdbPacket.CLSE -> {
                val stream = streams.remove(packet.arg1)
                stream?.onRemoteClosed(packet.arg0)
            }
            AdbPacket.AUTH -> log("adb: adbd re-authenticated; restarting the session is required")
            else -> log("adb: ignoring packet 0x${Integer.toHexString(packet.command)}")
        }
    }

    /** Serialised because two streams writing at once would interleave two headers on the wire. */
    private fun send(packet: AdbPacket) {
        synchronized(writeLock) {
            output.write(packet.encode())
            output.flush()
        }
    }

    private fun closeQuietly() {
        closed.set(true)
        streams.values.forEach { it.abandon() }
        streams.clear()
        runCatching { transport.close() }
    }

    /** One open ADB service, e.g. a shell command's stdout. */
    inner class AdbStream internal constructor(val localId: Int) {

        @Volatile
        var remoteId: Int = 0
            private set

        private val opened = Object()

        /** Set once `OPEN` is answered, either way; [openGranted] says which. */
        private var openSettled = false
        private var openGranted = false

        private val dataLock = Object()
        private val chunks = ArrayDeque<ByteArray>()
        private var headOffset = 0
        private var buffered = 0
        private var finished = false

        /** One `WRTE` may be in flight at a time; adbd answers each with an `OKAY`. */
        private val writePermit = Semaphore(1, true)

        private val closedByUs = AtomicBoolean(false)

        /** Blocks until adbd answers `OPEN`. */
        internal fun awaitOpen(timeoutMillis: Int): Boolean = synchronized(opened) {
            val deadline = System.currentTimeMillis() + timeoutMillis
            while (!openSettled) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) return false
                opened.wait(left)
            }
            openGranted
        }

        internal fun onAcknowledged(remote: Int) {
            synchronized(opened) {
                if (!openSettled) {
                    openSettled = true
                    openGranted = true
                    remoteId = remote
                    opened.notifyAll()
                    return
                }
                if (remoteId == 0 && remote > 0) remoteId = remote
            }
            // Only a write acknowledgement frees the next chunk; the one for `OPEN` must not.
            writePermit.release()
        }

        internal fun offer(payload: ByteArray) {
            if (payload.isEmpty()) return
            synchronized(dataLock) {
                // A slow decoder must not let a 40 Mbit/s stream grow without bound: the oldest
                // chunks go first, and the decoder resynchronises on the next IDR of screenrecord.
                while (buffered + payload.size > MAX_BUFFERED_BYTES && chunks.isNotEmpty()) {
                    val dropped = chunks.removeFirst()
                    buffered -= dropped.size - headOffset
                    headOffset = 0
                    log("adb: dropping buffered video, consumer is behind")
                }
                chunks.addLast(payload)
                buffered += payload.size
                dataLock.notifyAll()
            }
        }

        internal fun onRemoteClosed(remote: Int) {
            synchronized(opened) {
                if (!openSettled) {
                    // adbd closes a service it will not start, e.g. `exec:` being refused.
                    openSettled = true
                    openGranted = false
                }
                if (remote > 0) remoteId = remote
                opened.notifyAll()
            }
            synchronized(dataLock) {
                finished = true
                dataLock.notifyAll()
            }
            // Unblock a writer waiting for an acknowledgement that will never come.
            writePermit.release()
        }

        internal fun abandon() {
            synchronized(opened) {
                openSettled = true
                opened.notifyAll()
            }
            synchronized(dataLock) {
                finished = true
                chunks.clear()
                buffered = 0
                dataLock.notifyAll()
            }
            writePermit.release()
        }

        /** Reads what adbd wrote, blocking until something arrives. -1 at end of stream. */
        fun read(destination: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            synchronized(dataLock) {
                while (true) {
                    val chunk = chunks.firstOrNull()
                    if (chunk != null && headOffset < chunk.size) {
                        val count = minOf(length, chunk.size - headOffset)
                        System.arraycopy(chunk, headOffset, destination, offset, count)
                        headOffset += count
                        buffered -= count
                        if (headOffset >= chunk.size) {
                            chunks.removeFirst()
                            headOffset = 0
                        }
                        return count
                    }
                    if (finished) return -1
                    runCatching { dataLock.wait(READ_WAIT_SLICE_MS) }
                    if (finished && chunks.isEmpty()) return -1
                }
            }
        }

        fun write(source: ByteArray, offset: Int, length: Int) {
            if (remoteId == 0) throw IOException("ADB stream is not open")
            var sent = 0
            val chunkSize = minOf(maxPayload / 2, MAX_WRITE_CHUNK).coerceAtLeast(4096)
            while (sent < length) {
                val count = minOf(chunkSize, length - sent)
                if (!writePermit.tryAcquire(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    throw IOException("adbd stopped acknowledging writes")
                }
                send(AdbPacket(AdbPacket.WRTE, localId, remoteId, source.copyOfRange(offset + sent, offset + sent + count)))
                sent += count
            }
        }

        fun write(bytes: ByteArray) = write(bytes, 0, bytes.size)

        /** Reads to the end of the stream, bounding the total so a runaway command cannot exhaust the heap. */
        fun readFully(timeoutMillis: Int): ByteArray {
            val sink = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            val deadline = System.currentTimeMillis() + timeoutMillis
            while (System.currentTimeMillis() < deadline) {
                val read = read(buffer, 0, buffer.size)
                if (read < 0) break
                sink.write(buffer, 0, read)
                if (sink.size() > MAX_COMMAND_OUTPUT) break
            }
            return sink.toByteArray()
        }

        /** An [InputStream] view, for callers that want to hand the stream to a parser. */
        fun inputStream(): InputStream = object : InputStream() {
            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int = this@AdbStream.read(b, off, len)

            override fun close() = this@AdbStream.close()
        }

        fun close() {
            if (!closedByUs.compareAndSet(false, true)) return
            val remote = remoteId
            streams.remove(localId)
            if (remote > 0) {
                runCatching { send(AdbPacket(AdbPacket.CLSE, localId, remote, NO_PAYLOAD)) }
            }
            abandon()
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val APPROVAL_TIMEOUT_MS = 90_000
        private const val OPEN_TIMEOUT_MS = 5_000
        private const val EXEC_TIMEOUT_MS = 5_000
        private const val WRITE_TIMEOUT_MS = 5_000L
        private const val READ_WAIT_SLICE_MS = 200L

        private const val ADB_DEFAULT_MAX_PAYLOAD = 256 * 1024
        private const val MIN_MAX_PAYLOAD = 4 * 1024
        private const val MAX_MAX_PAYLOAD = 1024 * 1024
        private const val MAX_WRITE_CHUNK = 64 * 1024
        private const val MAX_BUFFERED_BYTES = 8 * 1024 * 1024
        private const val MAX_COMMAND_OUTPUT = 1024 * 1024

        /** `host::` plus the features DiPlay uses; adbd ignores any it does not know. */
        private val HOST_BANNER = "host::features=shell_v2,cmd,stat_v2,ls_v2,fixed_push_mkdir\u0000".toByteArray(Charsets.UTF_8)
        private val NO_PAYLOAD = ByteArray(0)
    }
}
