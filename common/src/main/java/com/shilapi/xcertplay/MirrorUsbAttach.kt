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
     * The "Android Auto receiver" the selector asks about is the `:autoreceiver` module from M3.
     * It does not exist yet, so DiPlay can recognise an Android phone but cannot serve it; the
     * attach path reports [MirrorDecision.ANDROID_RECEIVER_MISSING] instead of starting anything.
     */
    const val ANDROID_RECEIVER_AVAILABLE = false

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
