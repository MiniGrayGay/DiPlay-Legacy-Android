package com.shilapi.xcertplay.mirror

/** What the driver allows. [AUTO] picks by the phone that is plugged in. */
enum class MirrorPreference { AUTO, CARPLAY_ONLY, ANDROID_ONLY }

/** One phone currently attached. [id] is the USB device name, used only to tell phones apart. */
data class AttachedPhone(val id: String, val kind: PhoneKind)

sealed interface MirrorDecision {
    data class StartCarPlay(val phone: AttachedPhone) : MirrorDecision
    data class StartAndroidMirror(val phone: AttachedPhone) : MirrorDecision

    /** An Android phone is attached but shows no ADB interface. */
    data class AskEnableUsbDebugging(val phone: AttachedPhone) : MirrorDecision

    /** Nothing usable is attached. */
    data object Wait : MirrorDecision
}

/**
 * Chooses between CarPlay and Android mirroring from the attached phones. Pure and stateless so
 * the activity can call it on every attach or detach.
 */
object MirrorTransportSelector {
    /**
     * [phones] is in attach order. With both an iPhone and an Android phone attached, [prefer]
     * decides; it defaults to the iPhone so existing CarPlay users see no change.
     */
    fun decide(
        phones: List<AttachedPhone>,
        preference: MirrorPreference = MirrorPreference.AUTO,
        prefer: PhoneKind = PhoneKind.IPHONE,
    ): MirrorDecision {
        require(prefer == PhoneKind.IPHONE || prefer == PhoneKind.ANDROID) { "prefer must be IPHONE or ANDROID" }
        val allowed = phones.filter { phone ->
            when (preference) {
                MirrorPreference.AUTO -> true
                MirrorPreference.CARPLAY_ONLY -> phone.kind == PhoneKind.IPHONE
                MirrorPreference.ANDROID_ONLY ->
                    phone.kind == PhoneKind.ANDROID || phone.kind == PhoneKind.ANDROID_DEBUGGING_OFF
            }
        }
        val iphone = allowed.firstOrNull { it.kind == PhoneKind.IPHONE }
        val android = allowed.firstOrNull { it.kind == PhoneKind.ANDROID }
        val ready = when {
            iphone != null && android != null -> if (prefer == PhoneKind.IPHONE) iphone else android
            else -> iphone ?: android
        }
        if (ready != null) {
            return if (ready.kind == PhoneKind.IPHONE) MirrorDecision.StartCarPlay(ready)
            else MirrorDecision.StartAndroidMirror(ready)
        }
        val needsDebugging = allowed.firstOrNull { it.kind == PhoneKind.ANDROID_DEBUGGING_OFF }
        return if (needsDebugging != null) MirrorDecision.AskEnableUsbDebugging(needsDebugging) else MirrorDecision.Wait
    }
}
