package com.shilapi.xcertplay.aap

import com.shilapi.xcertplay.proto.ProtoWire
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Stands in for the phone's end of the WPP bootstrap, following the order AARE uses.
 *
 * Kept in the test sources: it is not a product feature, it exists so the head unit's half of the
 * exchange can be exercised without a paired phone, including the orders that are easy to get wrong
 * (a phone that never asks for credentials, one that cannot join, one that refuses the endpoint).
 */
class AawTestPhone(
    private val transport: AapTransport,
    /** Send WifiInfoRequest after the start request, as AARE does. False models a phone that never asks. */
    private val asksForCredentials: Boolean = true,
    /** What to report in WifiConnectionStatus; anything but 0 means the phone could not join. */
    private val joinStatus: Int = AawWpp.Status.SUCCESS,
    /** What to report in WifiStartResponse. */
    private val startStatus: Int = AawWpp.Status.SUCCESS,
    /** Ping the head unit once, mid-exchange, to check it answers while it waits. */
    private val pingOnce: Boolean = false,
    /** Hang up instead of answering the first frame, the way a refusing phone would. */
    private val hangUpImmediately: Boolean = false,
) {

    private val input = transport.inputStream()
    private val output = transport.outputStream()

    /** What the head unit announced as its AAP endpoint. */
    @Volatile var startRequestIp: String? = null
    @Volatile var startRequestPort: Int = 0

    /** What the head unit said to join. */
    @Volatile var ssid: String? = null
    @Volatile var password: String? = null
    @Volatile var bssid: String? = null
    @Volatile var securityMode: Int = -1
    @Volatile var accessPointType: Int = -1
    @Volatile var infoResponseCount = 0

    /** The version request the head unit sent, decoded. */
    @Volatile var versionMajor: Int = -1
    @Volatile var versionMinor: Int = -1
    @Volatile var versionChannelType: Int = -1

    @Volatile var pingsAnswered = 0

    /** Runs the phone side to completion. Throws on anything unexpected so the test reports it. */
    fun run() {
        if (hangUpImmediately) {
            transport.close()
            return
        }
        while (true) {
            val frame = AawWpp.readFrame(input) ?: return
            when (frame.id) {
                AawWpp.MessageId.VERSION_REQUEST -> {
                    readVersionRequest(frame.payload)
                    AawWpp.writeFrame(
                        output,
                        AawWpp.MessageId.VERSION_RESPONSE,
                        versionResponse(major = versionMajor, minor = versionMinor),
                    )
                }

                AawWpp.MessageId.START_REQUEST -> {
                    readStartRequest(frame.payload)
                    if (asksForCredentials) {
                        AawWpp.writeFrame(output, AawWpp.MessageId.INFO_REQUEST, ByteArray(0))
                    }
                    if (pingOnce) {
                        AawWpp.writeFrame(output, AawWpp.MessageId.PING_REQUEST, byteArrayOf(1, 2, 3, 4))
                    }
                }

                AawWpp.MessageId.INFO_RESPONSE -> {
                    readInfoResponse(frame.payload)
                    // The real phone joins the network here. It then reports both statuses with the
                    // start response (7) first, which is the order aa-proxy-rs and open-headunit
                    // expect and the order AARE sends.
                    AawWpp.writeFrame(output, AawWpp.MessageId.START_RESPONSE, startResponse(startStatus))
                    AawWpp.writeFrame(output, AawWpp.MessageId.CONNECTION_STATUS, connectionStatus(joinStatus))
                    return
                }

                AawWpp.MessageId.PING_RESPONSE -> pingsAnswered++

                else -> error("head unit sent unexpected ${AawWpp.MessageId.name(frame.id)}")
            }
        }
    }

    fun close() = transport.close()

    private fun readVersionRequest(payload: ByteArray) {
        val reader = ProtoWire.Reader(payload)
        while (true) {
            val field = reader.next() ?: break
            when (field.number) {
                1 -> versionMajor = field.asInt()
                2 -> versionMinor = field.asInt()
                3 -> versionChannelType = field.asInt()
            }
        }
    }

    private fun readStartRequest(payload: ByteArray) {
        val reader = ProtoWire.Reader(payload)
        while (true) {
            val field = reader.next() ?: break
            when (field.number) {
                1 -> startRequestIp = field.stringValue()
                2 -> startRequestPort = field.asInt()
            }
        }
    }

    private fun readInfoResponse(payload: ByteArray) {
        infoResponseCount++
        val reader = ProtoWire.Reader(payload)
        while (true) {
            val field = reader.next() ?: break
            when (field.number) {
                1 -> ssid = field.stringValue()
                2 -> password = field.stringValue()
                3 -> bssid = field.stringValue()
                4 -> securityMode = field.asInt()
                5 -> accessPointType = field.asInt()
            }
        }
    }

    private fun versionResponse(major: Int, minor: Int): ByteArray = ProtoWire.Writer()
        .int32(1, major.coerceAtLeast(0))
        .int32(2, minor.coerceAtLeast(0))
        .int32(4, AawWpp.Status.SUCCESS)
        .build()

    private fun connectionStatus(status: Int): ByteArray {
        val writer = ProtoWire.Writer().int32(1, status)
        if (status != AawWpp.Status.SUCCESS) writer.string(2, "simulated failure")
        return writer.build()
    }

    private fun startResponse(status: Int): ByteArray = ProtoWire.Writer()
        .string(1, "192.168.43.23")
        .uint32(2, 5277)
        .int32(3, status)
        .build()
}

/** Wires a head unit bootstrap to an [AawTestPhone] over an in-process pipe. */
class AawRig(
    phone: (AapTransport) -> AawTestPhone,
    accessPoint: () -> AawAccessPoint = { defaultAccessPoint() },
    server: AawServerEndpoint = AawServerEndpoint("192.168.43.1", 5277),
) {
    private val pipe: Pair<LoopbackTransport, LoopbackTransport> = LoopbackTransport.pair()
    private val headUnitSide: LoopbackTransport = pipe.first
    private val phoneSide: LoopbackTransport = pipe.second
    val phone: AawTestPhone = phone(phoneSide)
    val bootstrap = AawBootstrap(
        input = headUnitSide.inputStream(),
        output = headUnitSide.outputStream(),
        accessPoint = accessPoint,
        server = server,
    ) { }
    val phoneError = AtomicReference<Throwable?>(null)
    val phoneThread: Thread = thread(name = "aaw-test-phone") {
        try {
            this.phone.run()
        } catch (t: Throwable) {
            phoneError.set(t)
        }
    }

    fun assertPhoneHealthy() {
        val error = phoneError.get() ?: return
        throw AssertionError("the simulated phone failed: $error", error)
    }

    companion object {
        fun defaultAccessPoint() = AawAccessPoint(
            ssid = "DiPlay-AA",
            password = "carplay0001",
            bssid = "02:11:22:33:44:55",
        )
    }
}
