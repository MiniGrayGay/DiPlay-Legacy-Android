package com.shilapi.xcertplay.wfd

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * One RTSP message, in the shape Wi-Fi Display uses it.
 *
 * Two things here are unusual enough to be worth stating, because both are easy to get backwards
 * and both fail silently:
 *
 * - **The sink dials the source.** The source listens on 7236 and the sink opens the TCP connection
 *   to it, even though the source is the one that starts talking (M1).
 * - **Both ends send requests on that one connection.** RTSP is normally client-asks-server, but WFD
 *   has the source trigger the sink (M5) and the sink answer with SETUP/PLAY (M6/M7) over the same
 *   socket. Replies are matched by `CSeq`, not by who spoke last.
 *
 * Header names are case-insensitive per RFC 2326, and the WFD parameters appear both dashed
 * (`wfd-video-formats`, as the specification writes them) and underscored (`wfd_video_formats`, as
 * Android sends them). [param] folds the two so a lookup cannot miss on punctuation alone.
 */
class WfdMessage internal constructor(
    val startLine: String,
    private val headers: List<Pair<String, String>>,
    val body: ByteArray = ByteArray(0),
) {

    /** True for `OPTIONS * RTSP/1.0`, false for `RTSP/1.0 200 OK`. */
    val isRequest: Boolean get() = !startLine.startsWith(PROTOCOL_VERSION)

    /** `OPTIONS`, `SETUP`, … on a request; null on a response. */
    val method: String?
        get() = if (isRequest) startLine.substringBefore(' ').takeIf { it.isNotEmpty() } else null

    /** The URL a request addresses (`*` for OPTIONS); null on a response. */
    val url: String?
        get() = if (isRequest) {
            startLine.split(' ').getOrNull(1)
        } else {
            null
        }

    /** `200` on `RTSP/1.0 200 OK`; null on a request. */
    val statusCode: Int?
        get() = if (isRequest) {
            null
        } else {
            startLine.split(' ').getOrNull(1)?.toIntOrNull()
        }

    val cSeq: Int? get() = header("CSeq")?.trim()?.toIntOrNull()

    /** `Session: 12345678;timeout=60` without the parameters. */
    val session: String? get() = header("Session")?.substringBefore(';')?.trim()

    fun header(name: String): String? =
        headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

    /**
     * A WFD parameter, whichever separator the peer used and wherever it put it.
     *
     * There are two places to look and the specification uses both: the M1/M2 exchange carries
     * parameters as **headers**, while the M3/M4 reply carries them as `name: value` lines in a
     * `text/parameters` **body**. A sink that only reads one of the two answers half of what the
     * source asks for, and the session then stalls at "capabilities sent" with no error anywhere.
     */
    fun param(name: String): String? {
        val wanted = normalize(name)
        val fromHeader = headers.firstOrNull { normalize(it.first) == wanted }?.second
        if (fromHeader != null) return fromHeader
        return bodyParameters()[wanted]
    }

    /** Parameters from the body, with names normalised so a lookup cannot miss on punctuation. */
    fun bodyParameters(): Map<String, String> {
        if (body.isEmpty()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        val text = String(body, StandardCharsets.UTF_8)
        for (line in text.split('\n')) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val colon = trimmed.indexOf(':')
            if (colon <= 0) continue
            result[normalize(trimmed.substring(0, colon).trim())] = trimmed.substring(colon + 1).trim()
        }
        return result
    }

    private fun normalize(name: String): String = name.replace('-', '_').lowercase()

    /** All headers, for a caller that needs to forward something this class does not know. */
    fun allHeaders(): List<Pair<String, String>> = headers

    fun encode(): ByteArray {
        val out = StringBuilder()
        out.append(startLine).append(CRLF)
        for ((name, value) in headers) {
            out.append(name).append(": ").append(value).append(CRLF)
        }
        // Always sent, even for an empty body: a peer that reads Content-Length to decide whether a
        // body follows treats an absent one as "unknown", not "zero".
        if (headers.none { it.first.equals("Content-Length", ignoreCase = true) }) {
            out.append("Content-Length: ").append(body.size).append(CRLF)
        }
        out.append(CRLF)
        val head = out.toString().toByteArray(StandardCharsets.ISO_8859_1)
        return head + body
    }

    fun writeTo(output: OutputStream) {
        output.write(encode())
        output.flush()
    }

    override fun toString(): String =
        if (isRequest) {
            "req ${method} cseq=$cSeq"
        } else {
            "res ${statusCode} cseq=$cSeq"
        }

    companion object {
        const val PROTOCOL_VERSION = "RTSP/1.0"
        private const val CRLF = "\r\n"

        /** Guards against a peer that sends a header block that never terminates. */
        private const val MAX_HEADER_BYTES = 16 * 1024

        /**
         * Reads one message, or null when the peer closed cleanly.
         *
         * Splitting on the header block first is not an optimisation: the body length is only known
         * after the headers are parsed, and reading in chunks would consume the first bytes of the
         * next message whenever the peer pipelines two of them into one TCP segment.
         */
        @Throws(IOException::class)
        fun read(input: InputStream): WfdMessage? {
            val header = readHeaderBlock(input) ?: return null
            val lines = header.split(CRLF).filter { it.isNotEmpty() }
            if (lines.isEmpty()) throw IOException("empty RTSP message")
            val startLine = lines.first()
            val headers = ArrayList<Pair<String, String>>(lines.size)
            for (line in lines.drop(1)) {
                val colon = line.indexOf(':')
                if (colon <= 0) continue
                headers += line.substring(0, colon).trim() to line.substring(colon + 1).trim()
            }
            val length = headers.firstOrNull { it.first.equals("Content-Length", ignoreCase = true) }
                ?.second?.trim()?.toIntOrNull() ?: 0
            if (length < 0 || length > MAX_HEADER_BYTES * 64) {
                throw IOException("implausible Content-Length $length")
            }
            val body = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(body, read, length - read)
                if (n < 0) throw EOFException("truncated RTSP body: $read of $length bytes")
                read += n
            }
            return WfdMessage(startLine, headers, body)
        }

        /**
         * Reads bytes up to and including the blank line, or null at a clean EOF.
         *
         * Returns the header block with its trailing CRLFCRLF removed.
         */
        private fun readHeaderBlock(input: InputStream): String? {
            val buffer = java.io.ByteArrayOutputStream(512)
            var matched = 0
            while (true) {
                val byte = input.read()
                if (byte < 0) {
                    if (buffer.size() == 0 && matched == 0) return null
                    throw EOFException("connection closed inside an RTSP header block")
                }
                buffer.write(byte)
                matched = when {
                    byte == '\r'.code && (matched == 0 || matched == 2) -> matched + 1
                    byte == '\n'.code && (matched == 1 || matched == 3) -> matched + 1
                    else -> if (byte == '\r'.code) 1 else 0
                }
                if (matched == 4) {
                    val all = buffer.toString(StandardCharsets.ISO_8859_1.name())
                    return all.substring(0, all.length - 4)
                }
                if (buffer.size() > MAX_HEADER_BYTES) {
                    throw IOException("RTSP header block exceeded $MAX_HEADER_BYTES bytes")
                }
            }
        }
    }
}

/**
 * Builders for the messages a WFD sink sends, plus the replies it owes the source.
 *
 * Only the messages this project needs are here: capability exchange (M1–M4), session setup
 * (M5–M7), teardown (M8) and the UIBC switch (M14/M15). M9–M13 are playback control that a mirroring
 * sink can start without; adding one is a few lines beside these.
 */
object WfdRtsp {

    /** The source listens here for the sink's RTSP connection. */
    const val PORT = 7236

    /** The only protocol version Wi-Fi Display defines. */
    const val REQUIRE = "org.wfa.wfd1.0"

    /** What a sink advertises in its M1/M2 reply. */
    const val PUBLIC = "org.wfa.wfd1.0, GET_PARAMETER, SET_PARAMETER"

    /**
     * `OPTIONS * RTSP/1.0`.
     *
     * Both ends send this (M1 from the source, M2 from the sink), which is why it is not named after
     * either side.
     */
    fun options(cSeq: Int, require: Boolean = true): WfdMessage {
        val headers = ArrayList<Pair<String, String>>(2)
        headers += "CSeq" to cSeq.toString()
        if (require) headers += "Require" to REQUIRE
        return WfdMessage("OPTIONS * ${WfdMessage.PROTOCOL_VERSION}", headers)
    }

    fun ok(cSeq: Int): WfdMessage =
        WfdMessage("${WfdMessage.PROTOCOL_VERSION} 200 OK", listOf("CSeq" to cSeq.toString()))

    /** M3: ask the sink for its capabilities. */
    fun getParameter(cSeq: Int, names: List<String>): WfdMessage {
        val text = names.joinToString("\n") + "\n"
        val body = text.toByteArray(StandardCharsets.UTF_8)
        return WfdMessage(
            "GET_PARAMETER * ${WfdMessage.PROTOCOL_VERSION}",
            listOf(
                "CSeq" to cSeq.toString(),
                "Content-Type" to "text/parameters",
                "Content-Length" to body.size.toString(),
            ),
            body,
        )
    }

    /** M3 reply, and also how a sink answers M16 keep-alives that arrive empty. */
    fun parameters(cSeq: Int, values: Map<String, String>): WfdMessage {
        val text = values.entries.joinToString("") { (name, value) -> "$name: $value\r\n" }
        return WfdMessage(
            "${WfdMessage.PROTOCOL_VERSION} 200 OK",
            listOf("CSeq" to cSeq.toString(), "Content-Type" to "text/parameters") +
                bodyHeader(text),
            text.toByteArray(StandardCharsets.UTF_8),
        )
    }

    /** M4 and M5 both arrive as SET_PARAMETER; the parameters inside say which one it is. */
    fun setParameter(cSeq: Int, values: Map<String, String>): WfdMessage =
        parameters(cSeq, values)

    /**
     * M6: the sink announces where it wants the RTP and RTCP streams.
     *
     * This is the message that makes "the sink dials, the sink sets up" concrete: the ports below are
     * the sink's own, and the source answers with its `server_port` pair.
     */
    fun setup(cSeq: Int, presentationUrl: String, rtpPort: Int, rtcpPort: Int): WfdMessage =
        WfdMessage(
            "SETUP $presentationUrl ${WfdMessage.PROTOCOL_VERSION}",
            listOf(
                "CSeq" to cSeq.toString(),
                "Transport" to "RTP/AVP/UDP;unicast;client_port=$rtpPort-$rtcpPort",
            ),
        )

    /** M7: start the stream. Only legal after the source answered M6 with a session id. */
    fun play(cSeq: Int, session: String): WfdMessage =
        WfdMessage(
            "PLAY $PLAY_URL ${WfdMessage.PROTOCOL_VERSION}",
            listOf("CSeq" to cSeq.toString(), "Session" to session),
        )

    fun teardown(cSeq: Int, session: String): WfdMessage =
        WfdMessage(
            "TEARDOWN $PLAY_URL ${WfdMessage.PROTOCOL_VERSION}",
            listOf("CSeq" to cSeq.toString(), "Session" to session),
        )

    /** M15: turn UIBC on or off, once the source has said where to connect (M14). */
    fun uibcSetting(cSeq: Int, enable: Boolean, port: Int = 0): WfdMessage {
        val value = if (enable) {
            if (port > 0) "enable:$port" else "enable"
        } else {
            "disable"
        }
        return setParameter(cSeq, mapOf("wfd_uibc_setting" to value))
    }

    /** M7 and M8 both address the stream, not the session: `*` is only valid for OPTIONS. */
    private const val PLAY_URL = "rtsp://localhost/wfd1.0/streamid=0"

    private fun bodyHeader(text: String): List<Pair<String, String>> =
        listOf("Content-Length" to text.toByteArray(StandardCharsets.UTF_8).size.toString())
}
