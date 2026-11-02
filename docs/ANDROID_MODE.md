# Android mode

DiPlay is a CarPlay head unit: the car is the USB host, the phone is the device, and the driver plugs a
cable in and sees the phone's screen. This file records how the same experience is built for an Android
phone, what is verified, and what is deliberately not attempted.

## What "the same as CarPlay" means here

Three properties, not a protocol name:

1. The head unit is the host; the phone is just a device on the cable.
2. Nothing is installed on the phone, and nothing is confirmed on it every session.
3. Plug in, drive. No IP address, no pairing code, no "start casting" button.

An Android phone cannot get all three from Google's own path: Android Auto over USB (AOAP + TLS +
protobuf) needs a head-unit certificate issued by Google, and it does nothing at all on the Chinese
retail phones this app is built for — those ship without GMS and without the Android Auto app. HiCar and
CarLife are the same situation from the other side: closed protocols with no public specification, and
no head-unit implementation without the vendor's licence.

What every Android phone does have is `adbd`. It is part of the platform, it is already listening
behind the phone's ADB interface, and it can run the phone's own `screenrecord` and `input` binaries as
the `shell` user. That is the whole transport of this mode.

## Architecture

```
head unit (USB host)                                phone
AdbMirrorActivity                                   adbd  (platform, always there)
  UsbAdbTransport  ── bulk endpoints ──►  ┌──────►  exec:screenrecord --output-format=h264 -
  AdbSession                              │             │
    reader thread, one stream per           │             ▼  Annex-B H.264 on stdout
    service, OKAY flow control             │
  AnnexBSplitter → AnnexBDecoder ─────────┘        exec:input tap|swipe x y
  SurfaceView (letterboxed)  ◄── H.264 frames         (touch injected as the shell user)
  touch → video rect → pixels ─────────────────►
```

* `shared/…/adb/AdbTransport.kt` — the seam. Bytes above it are the plain ADB protocol; the pipe below
  is USB bulk on a real car and a socket in diagnostics.
* `shared/…/adb/AdbSession.kt` — ADB client with multiplexed streams. One reader thread demultiplexes by
  local id; every `WRTE` is answered with `OKAY` and a writer waits for that `OKAY` before the next
  chunk, because adbd's receive buffer overruns otherwise. Video must not block the touch pipe, so the
  two are separate streams over one link.
* `shared/…/adb/AdbCommands.kt` — the exact `screenrecord`/`input` argument lists. No `--size`: a size
  that does not match the display's aspect ratio stretches the picture, and a stretched picture puts
  every touch in the wrong place.
* `shared/…/adb/AnnexB.kt` — `screenrecord` emits a raw Annex-B stream with no container, so the SPS/PPS
  that `MediaCodec` needs are lifted out of the stream itself. A start code split across two cable reads
  is normal and is handled.
* `common/…/adb/UsbAdbTransport.kt` — claims the phone's ADB interface (class 0xFF, subclass 0x42,
  protocol 0x01) and moves bytes over its bulk pair.
* `common/…/adb/AnnexBDecoder.kt` — H.264 into a Surface. The encoded size is unknown until the first
  frame, so the real size comes back from `INFO_OUTPUT_FORMAT_CHANGED` — and that is what touches are
  mapped against.
* `common/…/adb/AdbMirrorSession.kt` — the session: handshake, the `screenrecord` loop, decode, taps.
  `screenrecord` stops itself after 180 s, so the stream is reopened; the decoder survives the boundary.
* `common/…/adb/AdbMirrorActivity.kt` — letterboxed SurfaceView, touch capture, one-line diagnostics.

## Verification

Two emulators on one workstation, with `tools/adb_proxy.py` standing in for the USB bulk pair: adbd
closes its TCP listener as soon as the host adb server takes the transport, so an emulator's adbd cannot
be reached through `adb forward`. The proxy accepts the ADB device protocol and runs each `exec:` through
`adb exec-out` on the real guest, so the handshake, the streams, `screenrecord` and the H.264 are all
genuine — only the two endpoints are replaced.

| Check | Result |
|---|---|
| ADB handshake, banner, stream open | ready, `device::ro.product.model=proxy emulator-5554` |
| H.264 into the surface | `h264 stream 1080x1920`, letterboxed on a 1920x1080 head unit |
| Frames advance with the phone's UI | 3 f on a static screen → 42 f after a scroll |
| Tap round-trip | tap on the mirrored "Sound" row opened `Settings$SoundSettingsActivity` on the phone |
| Drag round-trip | dragging the media slider on the head unit moved the phone's volume 5 → 14 |
| Protocol unit tests | `AdbSessionTest` (7), `AnnexBTest` (8), `AdbCommandsTest` (8) |

A static phone screen produces almost no frames — `screenrecord` only encodes when there is a new frame.
A frozen picture on an idle phone is expected; the frame counter in the status strip shows whether the
link is alive.

## Limits worth knowing

* **USB debugging is the one setup step.** It is a switch in Developer options, not an install, and the
  key approval is once per phone. Without it the phone exposes no ADB interface and this mode cannot
  start.
* **MIUI and some OEM builds** gate `input` injection behind "USB debugging (Security settings)", which
  additionally wants an account login. The picture works without it; taps do not.
* **Taps are `input` processes.** Fine for taps and straight-line drags, roughly 100–300 ms each. A
  persistent injector (a small agent run through `app_process`, as scrcpy does) is the upgrade for
  free-form gestures, multi-touch and lower latency.
* **No audio yet.** Every Android phone has `screenrecord`, but none has a shell-level audio tap; audio
  needs that same agent.
* **No static USB attach filter.** `usb_device_filter.xml` deliberately lists no Android vendor IDs:
  Issue #311 removed static attach filters because they make Android show "Open with DiPlay?" for every
  device and trigger a usbfs race on older kernels. Adding Android VIDs would make DiPlay offer itself
  every time any phone is plugged in to charge, so this is left as a product decision.

## Relation to the earlier `aa` package

`common/…/aa` (sender activity, projection service, accessibility injector) was the first attempt:
a DiPlay app on the phone, a TCP link, and a custom protocol. It works and is kept as a diagnostic
fallback for phones where USB debugging cannot be enabled, but it is not the product path — it needs an
install and a manual start on the phone, which is exactly what this mode exists to avoid.
