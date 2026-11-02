package com.shilapi.xcertplay.carlife

/**
 * The six TCP channels CarLife opens between the head unit and the phone.
 *
 * The numbers are not invented: they are the constants in Baidu's own head-unit implementation
 * (`CConnectManager.h` in `ApolloAuto/apollo-DuerOS`, Apache-2.0), and [forwardCommand] is the exact
 * line that implementation runs on the phone before connecting. Keeping them in one table means the
 * protocol layer never spells a port inline and a test can pin every value.
 *
 * Head length differs by channel because the command and control channels carry no timestamp: they are
 * interactive, so a 4-byte header is saved on every message. The streaming channels (video, media, TTS,
 * VR) carry a millisecond timestamp for lip-sync and frame pacing.
 */
enum class CarLifeChannel(
    /** Bytes before the payload: `size + type` (8) or `size + timestamp + type` (12). */
    val headLength: Int,
    /** Refuse anything larger; a phone never legitimately sends more, and a bad size is a hang. */
    val maxPayload: Int,
    /** Port the head unit listens on when the phone dials in over Wi-Fi. */
    val huPort: Int,
    /** Port the phone's CarLife service listens on, loopback only. */
    val mdPort: Int,
) {
    /** Control channel: version match, device info, encoder setup, authentication. */
    CMD(headLength = 8, maxPayload = 40 * 1024, huPort = 7200, mdPort = 7240),

    /** H.264 elementary stream, one access unit per message. */
    VIDEO(headLength = 12, maxPayload = 600 * 1024, huPort = 8200, mdPort = 8240),

    /** PCM media audio. */
    MEDIA(headLength = 12, maxPayload = 100 * 1024, huPort = 9200, mdPort = 9240),

    /** Text-to-speech prompts from the phone's navigation. */
    TTS(headLength = 12, maxPayload = 50 * 1024, huPort = 9201, mdPort = 9241),

    /** Voice recognition: microphone up, results down. */
    VR(headLength = 12, maxPayload = 50 * 1024, huPort = 9202, mdPort = 9242),

    /** Touch and hard-key events, head unit to phone. */
    CTRL(headLength = 8, maxPayload = 10 * 1024, huPort = 9300, mdPort = 9340),
    ;

    /**
     * The `adb forward` line Baidu's head unit runs on the phone.
     *
     * DiPlay does not need to spawn it: `AdbSession.open(adbService)` asks `adbd` for the same tunnel
     * directly, which also avoids binding six host ports. The string is kept because it documents the
     * exact wiring and because a diagnostics screen can show it to a human.
     */
    val forwardCommand: String
        get() = "adb forward tcp:$huPort tcp:$mdPort"

    /** Service name to hand to `adb`'s `OPEN`, which is what `forward` reduces to. */
    val adbService: String
        get() = "tcp:$mdPort"

    /** True when the head is `size + timestamp + type` rather than `size + type`. */
    val hasTimestamp: Boolean
        get() = headLength == HEAD_WITH_TIMESTAMP

    private companion object {
        const val HEAD_WITH_TIMESTAMP = 12
    }
}
