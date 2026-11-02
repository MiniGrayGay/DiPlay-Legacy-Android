package com.shilapi.xcertplay.proto

/**
 * A hand-rolled protobuf writer/reader, shared by the protocols whose message shapes are small and
 * fixed (CarLife, Android Auto's WPP bootstrap, AAP control messages).
 *
 * The alternative is `protoc` plus the protobuf Gradle plugin. Android Auto alone ships 256 `.proto`
 * files, and the head unit uses perhaps a dozen of them; generating 256 classes to read five fields
 * each would put a code-generation step in the build and a megabyte of runtime in a module that has
 * no dependencies. Since the wire format is a tag byte plus a varint, being able to assert the exact
 * bytes in a unit test is worth more than generated classes.
 *
 * Wire types 0 (varint), 1 (64-bit), 2 (length-delimited) and 5 (32-bit) are handled; groups (3/4)
 * are not, because nothing here uses them and a wrong guess would desynchronise silently.
 */
internal object ProtoWire {

    const val TYPE_VARINT = 0
    const val TYPE_FIXED64 = 1
    const val TYPE_LENGTH_DELIMITED = 2
    const val TYPE_FIXED32 = 5

    class Writer {
        private val out = java.io.ByteArrayOutputStream(64)

        /** `int32` field. A negative value is sign-extended to 64 bits, so it takes ten bytes. */
        fun int32(field: Int, value: Int): Writer {
            tag(field, TYPE_VARINT)
            varint(value.toLong())
            return this
        }

        fun uint32(field: Int, value: Int): Writer {
            tag(field, TYPE_VARINT)
            varint(value.toLong() and 0xffffffffL)
            return this
        }

        /**
         * `int64` field.
         *
         * AAP's ping carries a microsecond timestamp here, and a `System.nanoTime()/1000` value is
         * already past what an `int32` holds, so the wide form is the one that matters.
         */
        fun int64(field: Int, value: Long): Writer {
            tag(field, TYPE_VARINT)
            varint(value)
            return this
        }

        /**
         * `uint64` field. Same wire form as [int64]; the distinction only matters for values with
         * the top bit set, which a microsecond clock will not reach for 292 000 years.
         */
        fun uint64(field: Int, value: Long): Writer = int64(field, value)

        /** Fixed 32-bit little-endian field (`fixed32` / `sfixed32`). */
        fun fixed32(field: Int, value: Int): Writer {
            tag(field, TYPE_FIXED32)
            out.write(value and 0xff)
            out.write((value ushr 8) and 0xff)
            out.write((value ushr 16) and 0xff)
            out.write((value ushr 24) and 0xff)
            return this
        }

        /**
         * `sint32`: zigzag. Negative numbers are common here (`priority` is often -1), and plain
         * `int32` would spend ten bytes on each of them.
         */
        fun sint32(field: Int, value: Int): Writer {
            tag(field, TYPE_VARINT)
            varint(((value shl 1) xor (value shr 31)).toLong() and 0xffffffffL)
            return this
        }

        fun bool(field: Int, value: Boolean): Writer {
            tag(field, TYPE_VARINT)
            varint(if (value) 1L else 0L)
            return this
        }

        fun string(field: Int, value: String): Writer = bytes(field, value.toByteArray(Charsets.UTF_8))

        /** Length-delimited field. An empty value is still written; callers that need "absent" skip. */
        fun bytes(field: Int, value: ByteArray): Writer {
            tag(field, TYPE_LENGTH_DELIMITED)
            varint(value.size.toLong())
            out.write(value)
            return this
        }

        /** Nested message: length-delimited, identical on the wire to [bytes]. */
        fun message(field: Int, value: ByteArray): Writer = bytes(field, value)

        fun build(): ByteArray = out.toByteArray()

        private fun tag(field: Int, wireType: Int) = varint(((field shl 3) or wireType).toLong())

        private fun varint(value: Long) {
            var remaining = value
            while (true) {
                if ((remaining and 0x7fL.inv()) == 0L) {
                    out.write(remaining.toInt() and 0x7f)
                    return
                }
                out.write((remaining.toInt() and 0x7f) or 0x80)
                remaining = remaining ushr 7
            }
        }
    }

    /** One decoded field: [number] and [wireType], plus whichever value that wire type carries. */
    class Field(
        val number: Int,
        val wireType: Int,
        val intValue: Long,
        val bytesValue: ByteArray?,
    ) {
        /** Zigzag-decoded `sint32`. */
        fun sint32Value(): Int = ((intValue ushr 1).toInt()) xor -((intValue and 1L).toInt())

        fun stringValue(): String = bytesValue?.toString(Charsets.UTF_8) ?: ""

        fun asInt(): Int = intValue.toInt()
    }

    /**
     * Walks a message, skipping fields the caller does not know about.
     *
     * Skipping rather than failing is what lets this build talk to a newer phone: Android Auto adds
     * fields to these messages between releases, and a reader that rejected anything unexpected
     * would stop working on the next app update.
     */
    class Reader(private val data: ByteArray) {
        private var offset = 0

        fun next(): Field? {
            if (offset >= data.size) return null
            val key = varint()
            val number = (key ushr 3).toInt()
            val wireType = (key and 0x7L).toInt()
            return when (wireType) {
                TYPE_VARINT -> Field(number, wireType, varint(), null)

                TYPE_LENGTH_DELIMITED -> {
                    val length = varint().toInt()
                    if (length < 0 || offset + length > data.size) {
                        throw IllegalStateException("field $number claims $length bytes past the end")
                    }
                    val slice = data.copyOfRange(offset, offset + length)
                    offset += length
                    Field(number, wireType, 0, slice)
                }

                TYPE_FIXED64 -> {
                    skip(8)
                    Field(number, wireType, 0, null)
                }

                TYPE_FIXED32 -> {
                    skip(4)
                    Field(number, wireType, 0, null)
                }

                else -> throw IllegalStateException("unsupported wire type $wireType on field $number")
            }
        }

        private fun skip(count: Int) {
            if (offset + count > data.size) throw IllegalStateException("field runs past the end")
            offset += count
        }

        private fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                if (offset >= data.size) throw IllegalStateException("truncated varint")
                val byte = data[offset++].toInt() and 0xff
                result = result or ((byte and 0x7f).toLong() shl shift)
                if ((byte and 0x80) == 0) return result
                shift += 7
                if (shift > 63) throw IllegalStateException("varint longer than 10 bytes")
            }
        }
    }
}
