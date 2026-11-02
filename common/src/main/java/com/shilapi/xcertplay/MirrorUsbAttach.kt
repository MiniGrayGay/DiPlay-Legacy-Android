package com.shilapi.xcertplay

import android.content.Context
import android.hardware.usb.UsbDevice
import com.shilapi.xcertplay.mirroring.MirrorDecision
import com.shilapi.xcertplay.mirroring.MirrorTransportSelector
import com.shilapi.xcertplay.mirroring.PhoneClassifier
import com.shilapi.xcertplay.mirroring.UsbDeviceIdentity
import com.shilapi.xcertplay.mirroring.UsbInterfaceIdentity

/**
 * Bridges Android's [UsbDevice] to the pure classifier and applies the "Phone type" setting.
 *
 * Classification only reads descriptors the framework already parsed. It never calls
 * `openDevice`, so it needs no permission grant and cannot race the usbfs claim that the iPhone
 * path performs later — the reason USB handling stays free of extra work on older kernels.
 */
internal object MirrorUsbAttach {
    /**
     * True since the Android Auto receiver landed in [com.shilapi.xcertplay.AapProjectionActivity]:
     * the selector may now hand an Android phone to a real session instead of reporting that nothing
     * can serve it. The USB accessory handshake still needs real hardware to exercise, so on a
     * workstation the receiver is verified over TCP against the sender in `samples/aapphone`.
     */
    const val ANDROID_RECEIVER_AVAILABLE = true

    fun identity(device: UsbDevice): UsbDeviceIdentity = UsbDeviceIdentity(
        vendorId = device.vendorId,
        productId = device.productId,
        interfaces = (0 until device.interfaceCount).map { index ->
            val usbInterface = device.getInterface(index)
            UsbInterfaceIdentity(
                interfaceClass = usbInterface.interfaceClass,
                subclass = usbInterface.interfaceSubclass,
                protocol = usbInterface.interfaceProtocol,
            )
        },
    )

    fun decision(context: Context, device: UsbDevice): MirrorDecision = MirrorTransportSelector.decide(
        kind = PhoneClassifier.classify(identity(device)),
        preference = DiPlayPreferences.phoneType(context),
        androidReceiverAvailable = ANDROID_RECEIVER_AVAILABLE,
    )
}
