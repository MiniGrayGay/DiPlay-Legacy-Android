package com.shilapi.xcertplay.carlife

/**
 * CarLife message type codes, copied from `EPackageHeadType` in Baidu's head-unit library.
 *
 * The high half of every code is the channel it belongs on — `0x0001xxxx` command, `0x0002xxxx` video,
 * `0x0003xxxx` media, `0x0004xxxx` TTS, `0x0005xxxx` VR, `0x0006xxxx` control — and the top bit of the
 * low half says who sent it: `0x…8xxx` is head unit to phone, `0x…0xxx`…`0x…7xxx` is phone to head unit.
 * That asymmetry is how a session tells its own echo from an answer, so it is spelled out once here.
 */
object CarLifeMessage {

    // ---- command channel, head unit to phone ----
    const val HU_PROTOCOL_VERSION = 0x00018001
    const val HU_INFO = 0x00018003
    const val HU_BT_PAIR_INFO = 0x00018005
    const val VIDEO_ENCODER_INIT = 0x00018007
    const val VIDEO_ENCODER_START = 0x00018009
    const val VIDEO_ENCODER_PAUSE = 0x0001800A
    const val VIDEO_ENCODER_RESET = 0x0001800B
    const val VIDEO_ENCODER_FRAME_RATE_CHANGE = 0x0001800C
    const val PAUSE_MEDIA = 0x0001800E
    const val HU_AUTHEN_REQUEST = 0x00018048
    const val HU_AUTHEN_RESULT = 0x0001804A
    const val ERROR_CODE = 0x00018055

    // ---- command channel, phone to head unit ----
    const val PROTOCOL_VERSION_MATCH_STATUS = 0x00010002
    const val MD_INFO = 0x00010004
    const val MD_BT_PAIR_INFO = 0x00010006
    const val VIDEO_ENCODER_INIT_DONE = 0x00010008
    const val VIDEO_ENCODER_FRAME_RATE_CHANGE_DONE = 0x0001000D
    const val CONNECT_EXCEPTION = 0x00010037
    const val MD_AUTHEN_RESPONSE = 0x00010049
    const val MD_AUTHEN_RESULT = 0x0001004B
    const val MD_EXIT = 0x00010059

    // ---- video channel ----
    const val VIDEO_DATA = 0x00020001
    const val VIDEO_HEARTBEAT = 0x00020002

    // ---- media channel ----
    const val MEDIA_INIT = 0x00030001
    const val MEDIA_STOP = 0x00030002
    const val MEDIA_PAUSE = 0x00030003
    const val MEDIA_RESUME_PLAY = 0x00030004
    const val MEDIA_SEEK_TO = 0x00030005
    const val MEDIA_DATA = 0x00030006

    // ---- TTS channel ----
    const val TTS_INIT = 0x00040001
    const val TTS_STOP = 0x00040002
    const val TTS_DATA = 0x00040003

    // ---- VR channel ----
    const val VR_MIC_DATA = 0x00058001
    const val VR_INIT = 0x00050002
    const val VR_DATA = 0x00050003
    const val VR_STOP = 0x00050004

    // ---- control channel, head unit to phone ----
    const val TOUCH_ACTION = 0x00068001
    const val TOUCH_ACTION_DOWN = 0x00068002
    const val TOUCH_ACTION_UP = 0x00068003
    const val TOUCH_ACTION_MOVE = 0x00068004
    const val TOUCH_SINGLE_CLICK = 0x00068005
    const val TOUCH_DOUBLE_CLICK = 0x00068006
    const val TOUCH_LONG_PRESS = 0x00068007
    const val TOUCH_CAR_HARD_KEY_CODE = 0x00068008
    const val TOUCH_UI_ACTION_SOUND = 0x00060009

    /**
     * `MSG_CMD_PROTOCOL_VERSION_MATCH_STATUS` payload: the only value the reference library accepts.
     *
     * Its `execGetPropCmd` / version-match path treats "0" as success and everything else as a session
     * that must be torn down, so the head unit checks for exactly this.
     */
    const val VERSION_MATCH_OK = 0

    /**
     * Touch is sent as the *per-action* message types rather than [TOUCH_ACTION].
     *
     * That is not a shortcut: [TOUCH_ACTION] carries `S_TOUCH_ACTION { action, x, y }` and the meaning of
     * `action` is only defined in Baidu's SPC document, which is not public. The per-action types carry a
     * bare `{ x, y }` struct, so the message type itself says what the gesture is and there is nothing to
     * guess. This is also what the reference head unit's `ctrlTouchActionDown/Up/Move` wrappers use.
     */
    val TOUCH_BY_GESTURE: Map<CarLifeTouchGesture, Int> = mapOf(
        CarLifeTouchGesture.DOWN to TOUCH_ACTION_DOWN,
        CarLifeTouchGesture.MOVE to TOUCH_ACTION_MOVE,
        CarLifeTouchGesture.UP to TOUCH_ACTION_UP,
    )
}

/** The three gestures a drag is made of; named instead of magic action codes. */
enum class CarLifeTouchGesture {
    DOWN,
    MOVE,
    UP,
}
