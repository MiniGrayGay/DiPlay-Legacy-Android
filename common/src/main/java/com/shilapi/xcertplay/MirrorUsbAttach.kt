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
     * True since the experimental Android mode receiver landed in `common/.../aa`: the selector may
     * now hand an Android phone to [com.shilapi.xcertplay.aa.AaReceiverActivity] instead of reporting
     * that nothing can serve it. The USB accessory handshake itself still needs real hardware, so the
     * receiver is started and then waits for the phone's stream.
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
