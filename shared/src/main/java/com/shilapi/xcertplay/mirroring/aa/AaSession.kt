package com.shilapi.xcertplay.mirroring.aa

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Two frames of slack: enough to absorb a scheduling hiccup, few enough that latency stays low. */
private const val VIDEO_BACKLOG = 2

/**
 * A bidirectional byte pipe. TCP today; a USB bulk endpoint can implement the same two calls once
 * the accessory protocol is available, and the session below does not change.
 */
interface AaDuplex {
    /** Blocking read. Returns the byte count, or -1 when the peer closed the pipe. */
    fun read(buffer: ByteArray): Int

    /** Writes the whole array. Throws [java.io.IOException] when the pipe is gone. */
    fun write(bytes: ByteArray)

    fun close()
}

enum class AaSessionState { READY, HANDSHAKING, STREAMING, CLOSED }

/** Default listener for callers that only care about a few callbacks. */
internal object AaNoopListener : AaSession.Listener

/**
 * The transport-independent half of a mirroring session: framing, the HELLO/HELLO_ACK exchange,
 * and dispatch. Both ends use this same class with a different [Role], which is what lets the unit
 * tests drive a full conversation over an in-memory pipe without Android or a socket.
 */
class AaSession(
    private val duplex: AaDuplex,
    private val role: Role,
    private val listener: Listener = AaNoopListener,
    private val hello: AaMessages.Hello? = null,
    private val receiverName: String = "",
    /** Receiver-side policy. Returns null to accept, or a reason to refuse. */
    private val acceptHello: (AaMessages.Hello) -> String? = { null },
    private val clockUs: () -> Long = { System.nanoTime() / 1000 },
) {
    enum class Role { SENDER, RECEIVER }

    interface Listener {
        fun onStateChanged(state: AaSessionState) {}
        fun onEstablished(hello: AaMessages.Hello, ack: AaMessages.HelloAck?) {}

        /** Codec setup bytes (`csd-0`/`csd-1`) that must reach the decoder before the first frame. */
        fun onConfig(codec: Int, payload: ByteArray) {}
        fun onVideo(codec: Int, ptsUs: Long, payload: ByteArray) {}
        fun onTouch(touch: AaMessages.Touch) {}
        fun onKey(key: AaMessages.Key) {}
        fun onStats(stats: AaMessages.Stats) {}
        fun onRoundTripUs(micros: Long) {}
        fun onClosed(reason: String) {}
    }

    @Volatile
    var state: AaSessionState = AaSessionState.READY
        private set

    /** Frames received since the session opened; the receiver shows this so a silent link is visible. */
    @Volatile
    var videoFramesReceived: Long = 0L
        private set

    @Volatile
    var bytesReceived: Long = 0L
        private set

    val peer: AaMessages.Hello? get() = peerHello
    @Volatile
    private var peerHello: AaMessages.Hello? = null

    private val reader = AaFrameReader()
    private val writeLock = Any()
    private val readBuffer = ByteArray(64 * 1024)

    /**
     * Writes never happen on the caller's thread. Touches come from the UI thread and frames from the
     * encoder loop, and a socket write from the UI thread is a `NetworkOnMainThreadException` on
     * Android, while a blocking write from the encoder loop would stall capture. A single writer
     * thread also keeps frames in order without holding a lock the reader needs.
     *
     * Control messages get their own queue so a full video backlog can never delay a touch or an
     * acknowledgement, and so enqueuing from the UI thread is always non-blocking.
     */
    private val control = LinkedBlockingQueue<ByteArray>()
    private val video = java.util.concurrent.ArrayBlockingQueue<ByteArray>(VIDEO_BACKLOG)

    /** Messages accepted but not yet written; [awaitDrained] waits on it before closing. */
    private val outstanding = java.util.concurrent.atomic.AtomicInteger(0)

    @Volatile
    private var writing = true

    private val writerThread = Thread({ writeLoop() }, "aa-writer").apply {
        isDaemon = true
        start()
    }

    /**
     * Sends HELLO on the sender side. The receiver stays silent until the phone introduces itself,
     * so a link that never produces HELLO shows up as a handshake stall rather than a blank screen.
     */
    fun begin() {
        if (state != AaSessionState.READY) return
        transition(AaSessionState.HANDSHAKING)
        if (role == Role.SENDER) {
            val hello = hello ?: error("sender needs a HELLO")
            send(AaWire.Type.HELLO, payload = hello.encode())
        }
    }

    /** One blocking read iteration. Returns false once the session is closed. */
    fun pump(): Boolean {
        if (state == AaSessionState.CLOSED) return false
        val read = try {
            duplex.read(readBuffer)
        } catch (error: Exception) {
            close("read failed: ${error.message}")
            return false
        }
        if (read < 0) {
            close("peer closed")
            return false
        }
        if (read == 0) return true
        bytesReceived += read
        for (frame in reader.feed(readBuffer, 0, read)) {
            dispatch(frame)
            if (state == AaSessionState.CLOSED) return false
        }
        return true
    }

    /** Runs [pump] until the session closes. Callers on Android put this on a worker thread. */
    fun run() {
        begin()
        while (pump()) Unit
    }

    private fun dispatch(frame: AaFrame) {
        when (frame.type) {
            AaWire.Type.HELLO -> onHello(frame)
            AaWire.Type.HELLO_ACK -> onHelloAck(frame)
            AaWire.Type.CONFIG -> {
                if (state == AaSessionState.STREAMING) listener.onConfig(frame.codec, frame.payload)
            }
            AaWire.Type.VIDEO -> {
                if (state == AaSessionState.STREAMING) {
                    videoFramesReceived++
                    listener.onVideo(frame.codec, frame.ptsUs, frame.payload)
                }
            }
            AaWire.Type.TOUCH -> AaMessages.Touch.decode(frame.payload)?.let(listener::onTouch)
            AaWire.Type.KEY -> AaMessages.Key.decode(frame.payload)?.let(listener::onKey)
            AaWire.Type.PING -> send(AaWire.Type.PONG, ptsUs = frame.ptsUs, payload = frame.payload)
            AaWire.Type.PONG -> AaMessages.nonceOf(frame.payload)?.let {
                listener.onRoundTripUs((clockUs() - it).coerceAtLeast(0L))
            }
            AaWire.Type.STATS -> AaMessages.Stats.decode(frame.payload)?.let(listener::onStats)
            AaWire.Type.BYE -> close("peer said bye: ${AaMessages.reasonOf(frame.payload)}")
            else -> Unit
        }
    }

    private fun onHello(frame: AaFrame) {
        if (role != Role.RECEIVER) return
        val hello = AaMessages.Hello.decode(frame.payload)
        if (hello == null) {
            refuse("unsupported hello")
            return
        }
        peerHello = hello
        val refusal = acceptHello(hello)
        if (refusal != null) {
            refuse(refusal)
            return
        }
        send(AaWire.Type.HELLO_ACK, payload = AaMessages.HelloAck(true, "", receiverName).encode())
        transition(AaSessionState.STREAMING)
        listener.onEstablished(hello, null)
    }

    private fun onHelloAck(frame: AaFrame) {
        if (role != Role.SENDER) return
        val ack = AaMessages.HelloAck.decode(frame.payload)
        if (!ack.accepted) {
            close("receiver refused: ${ack.reason}")
            return
        }
        transition(AaSessionState.STREAMING)
        listener.onEstablished(hello ?: AaMessages.Hello(width = 0, height = 0, densityDpi = 0,
            codec = AaWire.Codec.H264, deviceName = ""), ack)
    }

    private fun refuse(reason: String) {
        send(AaWire.Type.HELLO_ACK, payload = AaMessages.HelloAck(false, reason, receiverName).encode())
        // The refusal is the only thing the phone gets to read, so let it reach the socket first.
        awaitDrained(500)
        close("refused: $reason")
    }

    fun sendConfig(codec: Int, payload: ByteArray) = send(AaWire.Type.CONFIG, codec = codec, payload = payload)

    fun sendVideo(codec: Int, ptsUs: Long, payload: ByteArray) =
        send(AaWire.Type.VIDEO, codec = codec, ptsUs = ptsUs, payload = payload)

    fun sendTouch(touch: AaMessages.Touch) = send(AaWire.Type.TOUCH, payload = touch.encode())

    fun sendKey(key: AaMessages.Key) = send(AaWire.Type.KEY, payload = key.encode())

    fun sendStats(stats: AaMessages.Stats) = send(AaWire.Type.STATS, payload = stats.encode())

    /** Receiver-side liveness probe; the phone echoes it so a dead direction is measurable. */
    fun ping(): Long {
        val nonce = clockUs()
        send(AaWire.Type.PING, ptsUs = nonce, payload = AaMessages.nonce(nonce))
        return nonce
    }

    fun bye(reason: String = "closing") {
        send(AaWire.Type.BYE, payload = AaMessages.reason(reason))
        awaitDrained(500)
        close(reason)
    }

    private fun send(
        type: Int,
        codec: Int = AaWire.Codec.NONE,
        ptsUs: Long = 0L,
        payload: ByteArray = ByteArray(0),
    ) {
        if (state == AaSessionState.CLOSED || !writing) return
        val bytes = AaWire.encode(type, codec, ptsUs, payload)
        if (type == AaWire.Type.VIDEO) {
            // Latest frame wins: a backlog of stale video is worse than a gap, and the encoder sends a
            // key frame every second so the receiver recovers on its own.
            if (video.offer(bytes)) {
                outstanding.incrementAndGet()
            } else {
                if (video.poll() != null) outstanding.decrementAndGet()
                if (video.offer(bytes)) outstanding.incrementAndGet()
            }
            return
        }
        if (control.offer(bytes)) outstanding.incrementAndGet()
    }

    private fun writeLoop() {
        while (writing) {
            val next = control.poll() ?: video.poll()
            if (next == null) {
                try {
                    Thread.sleep(5)
                } catch (_: InterruptedException) {
                    return
                }
                continue
            }
            try {
                synchronized(writeLock) { duplex.write(next) }
            } catch (error: Exception) {
                outstanding.decrementAndGet()
                close("write failed: ${error.javaClass.simpleName} ${error.message}")
                return
            }
            outstanding.decrementAndGet()
        }
    }

    /** Waits until everything queued has actually reached the socket, or [timeoutMs] passes. */
    private fun awaitDrained(timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (outstanding.get() > 0 && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(2)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    fun close(reason: String) {
        if (state == AaSessionState.CLOSED) return
        transition(AaSessionState.CLOSED)
        writing = false
        writerThread.interrupt()
        try {
            duplex.close()
        } catch (_: Exception) {
        }
        listener.onClosed(reason)
    }

    private fun transition(next: AaSessionState) {
        if (state == next) return
        state = next
        listener.onStateChanged(next)
    }
}
