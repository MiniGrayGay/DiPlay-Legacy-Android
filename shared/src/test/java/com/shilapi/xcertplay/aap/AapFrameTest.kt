package com.shilapi.xcertplay.aap

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AapFrameTest {

    private fun readAll(bytes: ByteArray): List<AapMessage> {
        val reader = AapFrameReader(ByteArrayInputStream(bytes))
        val assembler = AapMessageAssembler()
        val out = ArrayList<AapMessage>()
        while (true) {
            val message = reader.nextMessage(assembler) ?: return out
            out += message
        }
    }

    @Test
    fun aShortMessageIsOneFrameWithFirstAndLast() {
        val body = byteArrayOf(0x08, 0x01)
        val frames = AapFrame.encode(0, control = false, encrypted = false, AapFrame.plaintext(1, body))
        assertEquals(1, frames.size)

        val frame = frames[0]
        assertEquals(0, frame[0].toInt()) // channel
        assertEquals(AapFrame.FLAG_FIRST or AapFrame.FLAG_LAST, frame[1].toInt())
        // 4 header bytes + 2 id bytes + 2 body bytes; the size field is big endian, so 4 is
        // 0x00 0x04 and not 0x04 0x00.
        assertEquals(0, frame[2].toInt())
        assertEquals(4, frame[3].toInt())
        assertEquals(8, frame.size)
    }

    @Test
    fun theSizeFieldIsBigEndian() {
        // A 300-byte payload must encode as 0x01 0x2C, not 0x2C 0x01.
        val plaintext = ByteArray(300)
        plaintext[0] = 0
        plaintext[1] = 1
        val frame = AapFrame.encode(3, control = false, encrypted = false, plaintext)[0]
        assertEquals(0x01, frame[2].toInt())
        assertEquals(0x2C, frame[3].toInt())
    }

    @Test
    fun controlAndEncryptedFlagsSurviveTheRoundTrip() {
        // An encrypted payload is opaque — a sealed TLS record. Framing must not read a message id
        // out of it: those first bytes are the record header, and splitting them off is what makes
        // the engine report "Unrecognized record version" a layer later.
        val sealed = byteArrayOf(0x17, 0x03, 0x03, 0x00, 0x2E, 0x01, 0x02)
        val message = readAll(
            AapFrame.encode(7, control = true, encrypted = true, sealed)
                .fold(ByteArray(0)) { acc, bytes -> acc + bytes },
        ).single()
        assertEquals(7, message.channel)
        assertTrue(message.control)
        assertTrue(message.encrypted)
        assertEquals(AapMessage.UNKNOWN_ID, message.id)
        assertArrayEquals(sealed, message.body)
    }

    @Test
    fun aVideoFrameLargerThanOneChunkIsSplitAndReassembled() {
        // A 1080p keyframe is far bigger than 16120 bytes.
        val body = ByteArray(70_000) { (it % 251).toByte() }
        val frames = AapFrame.encode(1, control = false, encrypted = false, AapFrame.plaintext(0, body))
        assertTrue("expected several frames, got ${frames.size}", frames.size >= 5)

        // Only the first frame carries the extended total size.
        assertEquals(AapFrame.FLAG_FIRST, frames[0][1].toInt())
        assertEquals(8, AapFrame.EXTENDED_HEADER)
        for (index in 1 until frames.size) {
            val frame = frames[index]
            val payloadSize = ((frame[2].toInt() and 0xff) shl 8) or (frame[3].toInt() and 0xff)
            assertEquals(AapFrame.BASE_HEADER, frame.size - payloadSize)
        }
        // Every frame but the last has no LAST flag.
        assertFalse(AapFrame.isLast(frames[1][1].toInt()))
        assertTrue(AapFrame.isLast(frames.last()[1].toInt()))

        val message = readAll(frames.fold(ByteArray(0)) { acc, bytes -> acc + bytes }).single()
        assertEquals(0, message.id)
        assertArrayEquals(body, message.body)
    }

    @Test
    fun aHeaderSplitAcrossReadsIsReassembled() {
        // A USB bulk read ends wherever the packet ends: one byte at a time is the worst case.
        val body = ByteArray(200) { it.toByte() }
        val wire = AapFrame.encode(2, control = false, encrypted = false, AapFrame.plaintext(0x8001, body))
            .fold(ByteArray(0)) { acc, bytes -> acc + bytes }

        val dribble = object : InputStream() {
            private var index = 0
            override fun read(): Int = if (index < wire.size) wire[index++].toInt() and 0xff else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (index >= wire.size) return -1
                b[off] = wire[index++]
                return 1
            }
        }
        val message = AapFrameReader(dribble).nextMessage(AapMessageAssembler())!!
        assertEquals(0x8001, message.id)
        assertArrayEquals(body, message.body)
    }

    @Test
    fun aTruncatedPayloadRaisesInsteadOfReturningShortData() {
        val frame = AapFrame.encode(0, control = false, encrypted = false, AapFrame.plaintext(1, ByteArray(100)))[0]
        val cut = frame.copyOfRange(0, frame.size - 10)
        try {
            AapFrameReader(ByteArrayInputStream(cut)).nextFrame()
        } catch (expected: EOFException) {
            return
        }
        throw AssertionError("a truncated frame must not read as a complete one")
    }

    @Test
    fun aContinuationWithoutAFirstFrameIsDropped() {
        val assembler = AapMessageAssembler()
        val orphan = RawFrame(1, 0, -1, byteArrayOf(0x00, 0x01))
        assertNull(assembler.accept(orphan))
        assertEquals(1, assembler.dropped)
    }

    @Test
    fun aNewFirstFrameDiscardsAnUnfinishedMessage() {
        val assembler = AapMessageAssembler()
        // Claim a 100-byte message, deliver 10, then start a new one.
        assertNull(assembler.accept(RawFrame(1, AapFrame.FLAG_FIRST, 100, ByteArray(10))))
        val complete = AapFrame.plaintext(5, byteArrayOf(0x01))
        val message = assembler.accept(RawFrame(1, AapFrame.FLAG_FIRST or AapFrame.FLAG_LAST, -1, complete))
        assertNotNull(message)
        assertEquals(5, message!!.id)
        assertEquals(1, assembler.dropped)
    }

    @Test
    fun theWriterProducesWhatTheReaderExpects() {
        val sink = ByteArrayOutputStream()
        val writer = AapFrameWriter(sink)
        writer.write(
            AapMessage(4, control = false, encrypted = false, id = 0x8003, body = byteArrayOf(1, 2, 3)),
            flush = true,
        )
        val message = AapFrameReader(ByteArrayInputStream(sink.toByteArray())).nextMessage(AapMessageAssembler())!!
        assertEquals(4, message.channel)
        assertEquals(0x8003, message.id)
        assertArrayEquals(byteArrayOf(1, 2, 3), message.body)
    }

    @Test
    fun anOversizedFrameSizeIsRejected() {
        // 0xFFFF is above MAX_PAYLOAD; accepting it would allocate 64 KiB per bogus header.
        val bogus = byteArrayOf(0, 0x03, 0xFF.toByte(), 0xFF.toByte())
        try {
            AapFrameReader(ByteArrayInputStream(bogus)).nextFrame()
        } catch (expected: IllegalStateException) {
            return
        }
        throw AssertionError("a frame larger than MAX_PAYLOAD must be refused")
    }
}
