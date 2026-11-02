package com.shilapi.xcertplay.link

/**
 * Every phone-to-head-unit link DiPlay can or cannot offer, in one table.
 *
 * This exists because "support HiCar / CarLife / ICCOA Carlink" sounds like three equal work items and
 * is not. Two of the three cannot be implemented by a third party at all — the car side is licensed,
 * not published — and writing that down next to the one that *can* be built is the difference between a
 * roadmap and a wish list. [available] is the honest answer today; [blockedReason] says what would have
 * to change.
 */
enum class PhoneLinkProtocol(
    /** What the settings screen shows. */
    val label: String,

    /** True when DiPlay can start this link by itself. */
    val available: Boolean,

    /** Why not, when [available] is false. Shown in the UI instead of a dead button. */
    val blockedReason: String?,
) {
    /**
     * Apple's protocol, and the one DiPlay already speaks end to end.
     *
     * The phone side is iOS itself, so nothing is installed and nothing is asked of the driver beyond
     * plugging the cable in — which is the bar the Android side is measured against.
     */
    CARPLAY("CarPlay", available = true, blockedReason = null),

    /**
     * Baidu CarLife.
     *
     * The head-unit half is published by Baidu under Apache-2.0 (`CarLife-Vehicle-Lib` and
     * `CarLife-Android-Vehicle` in `ApolloAuto/apollo-DuerOS`), including the `.proto` files, the six
     * channel ports and the `adb forward` wiring, so this one is implemented in
     * `com.shilapi.xcertplay.carlife` rather than merely planned.
     *
     * The phone needs Baidu's CarLife app, which is a normal install — the same kind of "you need the
     * app" CarPlay avoids only because Apple ships it in the OS.
     */
    CARLIFE("百度 CarLife", available = true, blockedReason = null),

    /**
     * The ADB mirroring path: the phone's own `screenrecord` and `input`, no app on the phone.
     *
     * Not a vendor protocol at all. It exists here because it is the only option that covers a phone
     * with no car app installed, and because the CarLife link above rides the same ADB transport.
     */
    ADB_MIRROR("安卓投屏（ADB）", available = true, blockedReason = null),

    /**
     * Huawei HiCar.
     *
     * Blocked, and not for lack of skill: Huawei does not publish the device SDK at all. Their own
     * developer site states the SDKs and tools "have not been publicly released" and that integration
     * requires the partnership process — application, access approval, signed usage agreement, then
     * certification before production. The one public exception is FAW getting the *source* under a
     * bilateral agreement, which is not a path a third-party head unit can take.
     */
    HICAR(
        "华为 HiCar",
        available = false,
        blockedReason = "华为未公开 HiCar 车机端 SDK，需签合作与准入协议后才能集成",
    ),

    /**
     * ICCOA Carlink (the OPPO/vivo/Xiaomi alliance standard).
     *
     * Blocked for the same class of reason, plus a hard gate on the phone side: the alliance states that
     * a head unit must pass Carlink certification before OPPO/vivo/Xiaomi phones will recognise it. An
     * unlicensed implementation is not "unsupported", it is invisible — the phone never offers the
     * connection, so there is nothing to debug.
     */
    ICCOA_CARLINK(
        "ICCOA Carlink",
        available = false,
        blockedReason = "需 ICCOA 联盟 SDK 并通过认证，否则手机端不识别未认证车机",
    ),
    ;

    companion object {
        /** What the settings screen can offer today. */
        fun offered(): List<PhoneLinkProtocol> = entries.filter { it.available }

        /** Everything blocked on a licence, with the reason, so the UI can explain rather than omit. */
        fun blocked(): List<PhoneLinkProtocol> = entries.filter { !it.available }

        fun fromName(name: String?): PhoneLinkProtocol? = entries.firstOrNull { it.name == name }
    }
}
