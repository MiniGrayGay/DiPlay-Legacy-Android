package com.shilapi.xcertplay.mirroring

/**
 * Milestone M4 of the Android mode plan: exactly one projection mode may own the hotspot, the USB
 * device and the local ADB link at any moment. This is the decision half — it emits commands and
 * never touches Android, so every transition is covered by a JVM test.
 *
 * The runtime half (in `common`) maps [MirrorArbiterEffect] onto real work: stopping the CarPlay
 * controller, releasing the access point, starting the Android receiver. Keeping them apart is what
 * makes "两部手机同时插入" and "运行中拔插" reproducible without hardware.
 */
enum class MirrorMode { NONE, CARPLAY, ANDROID_AUTO }

enum class MirrorArbiterState { IDLE, CARPLAY_RUNNING, ANDROID_RUNNING, SWITCHING }

sealed interface MirrorArbiterEvent {
    /** A phone of [mode] asked for the head unit: USB attach, wireless association, or a manual tap. */
    data class PhoneAttached(val mode: MirrorMode) : MirrorArbiterEvent
    data class PhoneDetached(val mode: MirrorMode) : MirrorArbiterEvent

    /** The runtime confirmed the mode is actually live on screen. */
    data class ModeStarted(val mode: MirrorMode) : MirrorArbiterEvent

    /** The runtime could not start the mode, or it died. */
    data class ModeFailed(val mode: MirrorMode, val reason: String) : MirrorArbiterEvent

    /**
     * The runtime finished tearing [mode] down on its own: the phone was unplugged, the driver left
     * the screen, or the retained controller stopped. Unlike [ModeFailed] this is not a failure, so
     * it neither counts an attempt nor forces a release of resources the other mode may now hold.
     */
    data class ModeStopped(val mode: MirrorMode) : MirrorArbiterEvent

    /** Nothing was confirmed within the switch budget; fail safe and release everything. */
    data object SwitchTimeout : MirrorArbiterEvent
}

sealed interface MirrorArbiterEffect {
    data class StartMode(val mode: MirrorMode) : MirrorArbiterEffect
    data class StopMode(val mode: MirrorMode) : MirrorArbiterEffect

    /**
     * Drop hotspot, USB claim and ADB ownership without assuming which mode held them. Emitted
     * whenever the arbiter cannot say for certain who owns a resource.
     */
    data object ReleaseAll : MirrorArbiterEffect
}

class MirrorArbiter(private val switchTimeoutMs: Long = DEFAULT_SWITCH_TIMEOUT_MS) {
    var state: MirrorArbiterState = MirrorArbiterState.IDLE
        private set

    /** The mode currently streaming, or [MirrorMode.NONE]. */
    var owner: MirrorMode = MirrorMode.NONE
        private set

    /** Where a switch is heading while [state] is [MirrorArbiterState.SWITCHING]. */
    var target: MirrorMode = MirrorMode.NONE
        private set

    /** Consecutive failed starts, so the UI can stop retrying a mode that never comes up. */
    var failedAttempts: Int = 0
        private set

    /**
     * True when a new phone should be let in. The caller uses this instead of poking [state], so the
     * "one owner" rule has a single enforcement point.
     */
    fun accepts(mode: MirrorMode): Boolean =
        mode != MirrorMode.NONE && (state == MirrorArbiterState.IDLE || mode != owner)

    fun onEvent(event: MirrorArbiterEvent): List<MirrorArbiterEffect> = when (event) {
        is MirrorArbiterEvent.PhoneAttached -> onAttached(event.mode)
        is MirrorArbiterEvent.PhoneDetached -> onDetached(event.mode)
        is MirrorArbiterEvent.ModeStarted -> onStarted(event.mode)
        is MirrorArbiterEvent.ModeFailed -> onFailed(event.mode, event.reason)
        is MirrorArbiterEvent.ModeStopped -> onStopped(event.mode)
        MirrorArbiterEvent.SwitchTimeout -> onTimeout()
    }

    private fun onAttached(mode: MirrorMode): List<MirrorArbiterEffect> {
        if (mode == MirrorMode.NONE) return emptyList()
        if (state == MirrorArbiterState.SWITCHING && target == mode) return emptyList()
        if (state != MirrorArbiterState.SWITCHING && owner == mode) return emptyList()
        val effects = ArrayList<MirrorArbiterEffect>(3)
        // A switch that is already in flight may have left the previous mode half-running, so the
        // safe order is always "release everything, then start the newcomer".
        if (state == MirrorArbiterState.SWITCHING) {
            effects.add(MirrorArbiterEffect.ReleaseAll)
        } else if (owner != MirrorMode.NONE) {
            effects.add(MirrorArbiterEffect.StopMode(owner))
        }
        effects.add(MirrorArbiterEffect.StartMode(mode))
        target = mode
        state = MirrorArbiterState.SWITCHING
        return effects
    }

    private fun onDetached(mode: MirrorMode): List<MirrorArbiterEffect> {
        if (mode == MirrorMode.NONE) return emptyList()
        if (state == MirrorArbiterState.SWITCHING && target == mode) {
            // The phone we were switching to is gone; whatever the runtime was doing, release it.
            reset()
            return listOf(MirrorArbiterEffect.ReleaseAll)
        }
        if (owner != mode) return emptyList()
        reset()
        return listOf(MirrorArbiterEffect.StopMode(mode))
    }

    private fun onStarted(mode: MirrorMode): List<MirrorArbiterEffect> {
        if (state == MirrorArbiterState.SWITCHING && target == mode) {
            owner = mode
            failedAttempts = 0
            state = when (mode) {
                MirrorMode.CARPLAY -> MirrorArbiterState.CARPLAY_RUNNING
                MirrorMode.ANDROID_AUTO -> MirrorArbiterState.ANDROID_RUNNING
                MirrorMode.NONE -> MirrorArbiterState.IDLE
            }
            target = MirrorMode.NONE
            return emptyList()
        }
        // A late confirmation from an abandoned attempt: it must not be treated as the owner.
        if (owner == mode) return emptyList()
        return listOf(MirrorArbiterEffect.StopMode(mode))
    }

    private fun onFailed(mode: MirrorMode, reason: String): List<MirrorArbiterEffect> {
        if (state == MirrorArbiterState.SWITCHING && target == mode) {
            failedAttempts++
            reset()
            return listOf(MirrorArbiterEffect.ReleaseAll)
        }
        if (owner != mode) return emptyList()
        failedAttempts++
        reset()
        return listOf(MirrorArbiterEffect.StopMode(mode), MirrorArbiterEffect.ReleaseAll)
    }

    /**
     * The runtime stopped [mode] by itself.
     *
     * Only the mode that was about to start, or the one that already owned the screen, changes the
     * state: a late stop notification from a mode the arbiter already abandoned is ignored, for the
     * same reason a late confirmation is.
     */
    private fun onStopped(mode: MirrorMode): List<MirrorArbiterEffect> {
        if (mode == MirrorMode.NONE) return emptyList()
        if (state == MirrorArbiterState.SWITCHING) {
            if (target != mode) return emptyList()
            reset()
            return emptyList()
        }
        if (owner != mode) return emptyList()
        reset()
        return emptyList()
    }

    private fun onTimeout(): List<MirrorArbiterEffect> {        if (state != MirrorArbiterState.SWITCHING) return emptyList()
        failedAttempts++
        reset()
        return listOf(MirrorArbiterEffect.ReleaseAll)
    }

    private fun reset() {
        state = MirrorArbiterState.IDLE
        owner = MirrorMode.NONE
        target = MirrorMode.NONE
    }

    companion object {
        /** A projection either paints within this budget or something is wrong with USB or WiFi. */
        const val DEFAULT_SWITCH_TIMEOUT_MS = 12_000L
    }
}
