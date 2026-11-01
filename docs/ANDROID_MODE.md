# Android mode, milestone M1

`DiPlay 安卓模式(Android Auto)开发计划` puts the work in six milestones. This file records what M1
delivers and what the next milestones plug into; M0 (the conflict list) and M3 (the `:autoreceiver`
module) are not part of it yet.

## What M1 ships

* `shared/.../mirroring/PhoneClassifier.kt` — decides what is on the other end of the cable from
  the USB descriptors Android already parsed: `IPHONE`, `ANDROID`, `USB_BLUETOOTH_DONGLE`,
  `USB_SERIAL_BRIDGE`, `OTHER`. It never calls `openDevice`, so it needs no permission grant and
  cannot race the usbfs claim the iPhone path performs later (`Issue #311`).
* `shared/.../mirroring/MirrorTransportSelector.kt` — turns "what is plugged in" plus the driver's
  `Phone type` choice into a decision: start CarPlay, start Android Auto, or ignore.
* `common/.../MirrorUsbAttach.kt` — the Android bridge: `UsbDevice` → descriptors → decision.
* `common/.../AskEnableUsbDebugging.kt` — the hint shown when Android mode is selected and the head
  unit still has USB debugging off.
* The `Phone type` setting in **Connection → Connection setup**: `Automatic`, `CarPlay only`,
  `Android only (experimental)`.

Both classes are pure JVM logic, covered by `PhoneClassifierTest` and
`MirrorTransportSelectorTest`; `MirrorPhoneTypeSettingTest` covers the persisted setting and the
bridge.

## Where M3 plugs in

`MirrorUsbAttach.ANDROID_RECEIVER_AVAILABLE` is `false` while `:autoreceiver` does not exist, so an
Android phone is recognised and reported as `ANDROID_RECEIVER_MISSING` instead of starting
anything. Flipping that constant to `true` once the module is on the classpath is the only change
the selector needs; `START_ANDROID_AUTO` is already a branch in
`CarPlayHostActivity.onNewIntent` and needs the receiver start there.

## Why there is no Android entry in `usb_device_filter.xml`

An Android vendor entry there would make the system offer DiPlay for every phone the driver plugs
in, which is the "误触发、打扰已有用户" risk in the plan. M1 keeps the static filter as Apple,
CH341 and Bluetooth only, so nothing is launched while DiPlay is closed. Classification runs on the
attach broadcast the app already receives while it is open, which also means an Android phone can
never pull DiPlay up by itself.

## Default and fallback

`Automatic` is the default and behaves exactly like the pre-M1 build for an iPhone. Selecting
`Android only` never affects an iPhone session that is already running; it only decides what the
next attach starts. With no receiver in the build, an Android phone shows one toast (and the USB
debugging hint when it is off) rather than failing silently.
