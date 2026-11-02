package com.shilapi.xcertplay.carlife

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Field numbers and encodings for the messages the head unit has to get exactly right on the first
 * exchange. The expected bytes below are what the phone's own protobuf parser expects, so they are
 * written out by hand instead of being compared with the encoder that produced them.
 */
class CarLifeProtoTest {

    @Test
    fun protocolVersionEncodesTagThenValue() {
        // field 1 varint -> 0x08, field 2 varint -> 0x10
        assertArrayEquals(byteArrayOf(0x08, 0x01, 0x10, 0x00), CarLifeProto.encodeProtocolVersion(1, 0))
        assertArrayEquals(byteArrayOf(0x08, 0x01, 0x10, 0x02), CarLifeProto.encodeProtocolVersion(1, 2))
    }

    @Test
    fun protocolVersionRoundTripsAcrossVarintWidths() {
        for (value in listOf(0, 1, 127, 128, 300, 16_383, 16_384, Int.MAX_VALUE)) {
            val decoded = CarLifeProto.decodeProtocolVersion(CarLifeProto.encodeProtocolVersion(value, value))
            assertEquals(value, decoded.major)
            assertEquals(value, decoded.minor)
        }
    }

    @Test
    fun aNegativeVersionUsesTheTenByteVarintProtobufSpecifies() {
        val encoded = CarLifeProto.encodeProtocolVersion(-1, 0)
        // 0x08 then ten bytes of 0xff…0x01, then 0x10 0x00
        assertEquals(13, encoded.size)
        assertEquals(0x08.toByte(), encoded[0])
        assertEquals(0xff.toByte(), encoded[1])
        assertEquals(0x01.toByte(), encoded[10])
        assertEquals(-1, CarLifeProto.decodeProtocolVersion(encoded).major)
    }

    @Test
    fun videoEncoderInfoCarriesWidthHeightAndFrameRate() {
        val encoded = CarLifeProto.encodeVideoEncoderInfo(1280, 720, 30)
        assertArrayEquals(
            byteArrayOf(0x08, 0x80.toByte(), 0x0a, 0x10, 0xd0.toByte(), 0x05, 0x18, 0x1e),
            encoded,
        )
        assertEquals(CarLifeVideoEncoderInfo(1280, 720, 30), CarLifeProto.decodeVideoEncoderInfo(encoded))
    }

    @Test
    fun encoderInitDoneReusesTheSameShape() {
        val done = CarLifeProto.encodeVideoEncoderInfo(1080, 1920, 60)
        assertEquals(CarLifeVideoEncoderInfo(1080, 1920, 60), CarLifeProto.decodeVideoEncoderInfo(done))
    }

    @Test
    fun matchStatusIsReadWhateverFieldNumberThePhoneUses() {
        // { matchStatus = 0 } as field 1, and the same value as field 7: both must read as 0.
        assertEquals(0, CarLifeProto.decodeSingleInt(byteArrayOf(0x08, 0x00)))
        assertEquals(0, CarLifeProto.decodeSingleInt(byteArrayOf(0x38, 0x00)))
        assertEquals(CarLifeMessage.VERSION_MATCH_OK, CarLifeProto.decodeSingleInt(byteArrayOf(0x08, 0x00)))
        assertNull(CarLifeProto.decodeSingleInt(ByteArray(0)))
    }

    @Test
    fun deviceInfoSkipsFieldsThisBuildLeavesEmpty() {
        val encoded = CarLifeProto.encodeDeviceInfo(CarLifeDeviceInfo(model = "DiPlay", manufacturer = ""))
        // Only model (field 14) survives: tag 0x72, length 6, "DiPlay".
        assertEquals(1 + 1 + 6, encoded.size)
        assertEquals(0x72.toByte(), encoded[0])
        assertEquals("DiPlay", CarLifeProto.decodeDeviceInfo(encoded).model)
        assertNull(CarLifeProto.decodeDeviceInfo(encoded).manufacturer)
    }

    @Test
    fun deviceInfoRoundTripsAndIgnoresUnknownFields() {
        val info = CarLifeDeviceInfo(
            os = "Android",
            model = "DiPlay HU",
            manufacturer = "DiPlay",
            release = "13",
            sdk = "33",
            sdkInt = 33,
        )
        assertEquals(info, CarLifeProto.decodeDeviceInfo(CarLifeProto.encodeDeviceInfo(info)))
    }

    @Test
    fun touchPayloadIsTwoLittleEndianInts() {
        assertArrayEquals(
            byteArrayOf(0x10, 0x27, 0x00, 0x00, 0xe8.toByte(), 0x03, 0x00, 0x00),
            CarLifeTouch.encode(10_000, 1000),
        )
        assertEquals(10_000 to 1000, CarLifeTouch.decode(CarLifeTouch.encode(10_000, 1000)))
    }

    @Test
    fun aTapInTheLetterboxBarIsDropped() {
        // 1000x1000 picture centred in a 1920x1080 screen: bars are 460px wide on each side.
        val inPicture = CarLifeTouch.toFrame(960f, 540f, 460, 0, 1000, 1080, 1000, 1080)
        assertEquals(500 to 540, inPicture)
        assertNull(CarLifeTouch.toFrame(100f, 540f, 460, 0, 1000, 1080, 1000, 1080))
        assertNull(CarLifeTouch.toFrame(960f, 540f, 460, 0, 0, 1080, 1000, 1080))
    }

    @Test
    fun anUnknownFieldDoesNotAbortTheMessage() {
        // Field 40, length-delimited, two junk bytes — a field this build has no constant for.
        val unknown = byteArrayOf(0xc2.toByte(), 0x02, 0x02, 0x07, 0x07)
        val model = CarLifeProto.encodeDeviceInfo(CarLifeDeviceInfo(model = "HU"))
        val decoded = CarLifeProto.decodeDeviceInfo(unknown + model)
        assertEquals("HU", decoded.model)
    }
}
