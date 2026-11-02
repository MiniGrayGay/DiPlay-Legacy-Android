package com.shilapi.xcertplay.adb

/**
 * The shell commands that turn an ADB stream into a mirroring session.
 *
 * No helper binary and no APK goes onto the phone: `screenrecord` and `input` ship in every Android
 * `/system/bin`, so the whole pipeline is these two commands plus the ADB protocol underneath. That is
 * what makes "plug the cable, get the phone's screen" work on a phone the driver has installed nothing
 * on — the only setup is the phone's own USB debugging switch.
 *
 * Kept pure so the exact argument list, including the ones `screenrecord` silently rejects, is covered
 * by a JVM test rather than by a cable.
 */
object AdbCommands {

    /** `screenrecord` refuses more than three minutes per run, so the caller restarts it. */
    const val SCREEN_RECORD_MAX_SECONDS = 180

    /** Above this the encoder on a mid-range phone drops frames, below it text goes soft. */
    const val DEFAULT_BIT_RATE = 8_000_000

    /**
     * Streams H.264 to stdout without a container.
     *
     * `--output-format=h264` with `-` writes the raw Annex-B elementary stream, which is exactly what
     * `MediaCodec` takes after the SPS/PPS is lifted out of it. [width]/[height] are left null on
     * purpose: a `--size` that does not match the display's aspect ratio stretches the picture, and a
     * stretched picture puts every touch back in the wrong place.
     */
    fun screenRecord(
        bitRate: Int = DEFAULT_BIT_RATE,
        timeLimitSeconds: Int = SCREEN_RECORD_MAX_SECONDS,
        width: Int = 0,
        height: Int = 0,
        displayId: Int = 0,
    ): String = buildString {
        append("screenrecord --output-format=h264")
        append(" --bit-rate ").append(bitRate.coerceIn(1_000_000, 50_000_000))
        append(" --time-limit ").append(timeLimitSeconds.coerceIn(1, SCREEN_RECORD_MAX_SECONDS))
        if (width > 0 && height > 0) append(" --size ").append(even(width)).append('x').append(even(height))
        if (displayId > 0) append(" --display-id ").append(displayId)
        append(" -")
    }

    fun tap(x: Int, y: Int): String = "input tap ${x.coerceAtLeast(0)} ${y.coerceAtLeast(0)}"

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMillis: Int): String =
        "input swipe ${x1.coerceAtLeast(0)} ${y1.coerceAtLeast(0)} " +
            "${x2.coerceAtLeast(0)} ${y2.coerceAtLeast(0)} ${durationMillis.coerceIn(1, 10_000)}"

    fun keyEvent(keyCode: Int): String = "input keyevent $keyCode"

    /** H.264 encoders reject odd dimensions; rounding down keeps the aspect error under one pixel. */
    fun even(value: Int): Int = (value / 2) * 2

    /**
     * Maps a point inside the mirrored picture onto the phone's own pixels.
     *
     * [x]/[y] are 0..1 inside the video rectangle — not inside the head unit's window, because the
     * letterbox around a portrait phone is not part of the phone and a tap there must do nothing.
     */
    fun toDevice(x: Float, y: Float, videoWidth: Int, videoHeight: Int): Pair<Int, Int> {
        val px = (x.coerceIn(0f, 1f) * videoWidth).toInt().coerceIn(0, (videoWidth - 1).coerceAtLeast(0))
        val py = (y.coerceIn(0f, 1f) * videoHeight).toInt().coerceIn(0, (videoHeight - 1).coerceAtLeast(0))
        return px to py
    }
}
