# Android mode

`DiPlay 安卓模式(Android Auto)开发计划` splits the work into six milestones. This file records what is
implemented, how the receiver and sender talk to each other, and how the pair is verified without a
real car.

## Status against the plan

| Milestone | State | Notes |
|---|---|---|
| M0 feasibility | **not done** | Needs the DiAuto sources. There is no network access to that repository from this workspace, so nothing was read. |
| M1 recognition and selection | **done** | `PhoneClassifier`, `MirrorTransportSelector`, the `Phone type` setting, USB attach wiring, USB-debugging hint. |
| M2 transitional (launch DiAuto) | **superseded** | Not needed: M3 was replaced by a receiver in this repository, so there is nothing external to hand a phone to. |
| M3 receiver module | **done, differently** | Instead of importing `:autoreceiver` (~160k lines, NDK 29 against our r25c), `common/.../aa` implements a receiver from scratch. See "Why not DiAuto" below. |
| M4 resource arbitration | **done** | `shared/.../mirroring/MirrorArbiter.kt`, pure Kotlin, every transition covered by `MirrorArbiterTest`. |
| M5 device matrix | **partly** | Automated on two emulators (below). The real-car matrix still needs the cars. |

## Architecture

Two halves of one protocol, both in this repository:

```
phone (sender)                                  head unit (receiver)
AaSenderActivity                                AaReceiverActivity
  AaProjectionService (foreground,             AaVideoDecoder (MediaCodec → SurfaceView)
    type=mediaProjection)                        AaRawFrameView  (raw fallback)
  AaSenderPipeline                                touch → video rect → normalised → TOUCH
    Real screen: VirtualDisplay
      → MediaCodec input surface
    Fallback:    EGL test pattern
    or raw ARGB + Deflater
  AaSession(SENDER)  ──── AaWire frames ────►   AaSession(RECEIVER)
  AaTouchInjector (AccessibilityService)
      ◄──────────── TOUCH ────────────
```

* `shared/.../mirroring/aa/AaWire.kt` — an 18-byte header (magic, version, type, codec, pts, length)
  plus payload. Resynchronises on the magic, so a truncated write or a USB bulk pipe that splits a
  frame cannot wedge the session.
* `shared/.../mirroring/aa/AaMessages.kt` — HELLO/HELLO_ACK, TOUCH, KEY, STATS, PING/PONG.
* `shared/.../mirroring/aa/AaSession.kt` — transport-independent session: handshake, dispatch,
  liveness. Writes go through one writer thread with separate control and video queues, because a
  socket write from a UI thread is a `NetworkOnMainThreadException` and a blocking write from the
  encoder loop would stall capture. Video backlog is two frames; stale frames are dropped rather
  than shown late.
* `common/.../aa/SocketAaDuplex.kt` — the TCP implementation of `AaDuplex`. The same interface is
  what a USB bulk endpoint implements once the accessory handshake lands.
* `common/.../aa/AaProjectionService.kt` — a foreground service of type `mediaProjection` that holds
  the screen-capture grant. The order matters: this app targets 37, and Android 14+ throws from
  `getMediaProjection` unless a foreground service of that type is already running. Older releases
  only need the plain foreground call, so the branch is on API 29.
* `common/.../aa/AaTouchInjector.kt` — an accessibility service that turns incoming touches into
  `dispatchGesture` calls on this phone. `INJECT_EVENTS` is a signature permission and the shell
  `input` command is not reachable from an app, so accessibility is the only route available without
  root. Consequences: the driver must enable it once, and the mirror is view-only while it is off
  (the sender prints `inject off` rather than pretending the tap worked).

Screen capture is a straight line through the GPU: `VirtualDisplay` mirrors into the encoder's own
input surface, so frames never touch the CPU and there is no EGL swap loop to pace. Video dimensions
are derived from the physical display at the long edge of 640 px and rounded to even numbers, so a
9:16 phone arrives as 360x640 rather than squashed into 16:9.

Only one side listens: the phone does, on port 8790 (deliberately not 5277, so a real Android Auto
phone cannot collide with it). The head unit connects and retries every two seconds, so either end
may be started first.

## Why not DiAuto

The plan's M3 is "import DiAuto's app and contract sources into a `:autoreceiver` module". That cannot
be done from this machine: the repository is not cloned here and there is no network access to fetch
it. More importantly, the merge was estimated at 22–36 person-days, most of it build-system and
resource conflict work, and it cannot be verified without real cars.

What is here instead is a receiver that speaks a protocol defined in this repository. It is honest
about the difference: it does **not** implement Android Auto's protobuf/SSL protocol, so a retail
Android Auto phone cannot use it. It does implement everything a receiver needs *around* that
protocol — transport framing, handshake, H.264 decode to a surface, input return path, arbitration,
and a sender to test against — so a real stack can be dropped in behind `AaDuplex` later.

## Verifying with two emulators

One emulator plays the phone, the other the head unit. An emulator has no USB host port, which is
exactly why the transport is TCP.

```bash
# 1. Both ends run the same debug APK.
adb -s emulator-5554 install -r -t mobile/build/outputs/apk/debug/mobile-debug.apk
adb -s emulator-5556 install -r -t mobile/build/outputs/apk/debug/mobile-debug.apk

# 2. The phone listens; forward its port to the workstation.
adb -s emulator-5554 forward tcp:8790 tcp:8790

# 3. Start the sender first, then the receiver. 10.0.2.2 is the emulator's view of the host.
adb -s emulator-5554 shell am start -n com.shihab.diplay.hudtest/com.shilapi.xcertplay.aa.AaSenderActivity
adb -s emulator-5556 shell am start -n com.shihab.diplay.hudtest/com.shilapi.xcertplay.aa.AaReceiverActivity

# 4. Only while the sender is running: accept its consent dialog (tap "START NOW").
#    Coordinates are physical pixels; `wm size` reports 1080x1920 on these AVDs.
adb -s emulator-5554 shell input tap 849 1071

# 5. Enable touch injection. Reinstalling the APK clears this, so it has to be repeated
#    after every install, and it is the step that makes the mirror controllable.
adb -s emulator-5554 shell settings put secure enabled_accessibility_services \
  com.shihab.diplay.hudtest/com.shilapi.xcertplay.aa.AaTouchInjector
adb -s emulator-5554 shell settings put secure accessibility_enabled 1
adb -s emulator-5554 shell dumpsys accessibility | grep capabilities   # 32 = canPerformGestures
```

`--ei aa_sender_codec 0` switches the sender to the raw path. Both activities are also reachable
through the UI: **Connection → Connection setup → Android mode receiver** on the head unit, and
**Diagnostics → Android mode sender** on the phone. They are `exported="false"` in the shipping
manifest; `mobile/src/debug/AndroidManifest.xml` exports them for debug builds only so the commands
above work.

What to look for on the receiver: the strip reads `STREAMING`, a rising `h264 … frames` counter with
the resolution, and an `rtt` in single-digit milliseconds, then fades after six seconds so the picture
is unobstructed. The phone's own screen appears in the middle, letterboxed to its aspect ratio, and
taps on the receiver land on the phone — pressing **Network & internet** in the mirrored Settings
opens that page on the phone.

If the consent dialog is dismissed, the sender says so on its status line and streams the test pattern
instead (a moving bar, a hue sweep and a square that follows the head unit's finger). That fallback is
deliberate: a dead black rectangle with no explanation is the worst outcome.

Measured on 2026-11-02, `JingCang_Test_API28` as sender and `JingCang_Android11_API30` as receiver,
real screen at 360x640, ~15 fps: 2–3 KiB per frame, `rtt` 17–33 ms, tap and 500 px swipe both landed
in the right place on the phone. On 2026-11-01 the same pair carried the 640x360 test pattern at
8–12 ms rtt with no dropped frames.

The Android 14 path was checked separately on `JingCang_Android14_API34`, because that release is the
one that refuses `getMediaProjection` without a matching foreground service. Logs show
`Background started FGS: Allowed … act=…PROJECTION_START` followed by `mirroring 360x640 @420 dpi`,
and the head unit reproduced the phone's Settings home and the follow-up **Network & internet** page
after a tap. A handful of `decode dropped: input queue full` lines appear while the decoder warms up;
they are the intended degradation (the newest frame wins) rather than an error.

## Settings, defaults and fallback

* `Phone type` in **Connection → Connection setup**: `Automatic` (default), `CarPlay only`,
  `Android only (experimental)`.
* `Automatic` behaves exactly like the pre-M1 build for an iPhone; `Android only` never disturbs a
  running CarPlay session, it only decides what the next attach starts.
* Android mode needs API 21 for `MediaCodec` surface input and `MediaProjection`; below that the
  entries are replaced by one explanatory line.
* Screen capture consent is per session and per launch: the dialog comes back after a restart, which
  is how Android keeps an app from recording forever. Refusing it is not fatal — the sender falls back
  to the test pattern and says why.
* Touch injection needs the accessibility service enabled once. `aa_injector_description` is what
  Settings shows the driver; the service requests `canPerformGestures` and reads no window content.
* H.264 is the default codec. The raw path exists because a device with a broken AVC encoder should
  still show a picture; the receiver picks the path per frame, so no restart is involved. The raw path
  always sends the test pattern, because reading back a projected screen per frame would mean a
  `PixelCopy` round trip per frame.
* `usb_device_filter.xml` still lists Apple, CH341 and Bluetooth only. Adding an Android vendor entry
  would offer DiPlay for every phone the driver plugs in — the "误触发" risk in the plan — so
  classification keeps running on the attach broadcast the app already receives while open.

## Resource arbitration (M4)

`MirrorArbiter` owns the "one mode at a time" rule. It emits `StartMode`, `StopMode` and
`ReleaseAll`; the runtime maps those onto stopping the CarPlay controller, releasing the access point
and starting the receiver. Notable decisions, all covered by tests:

* attaching a second phone stops the owner *before* starting the newcomer;
* an attach during a switch releases everything first, because a half-stopped mode may still hold
  the hotspot;
* a confirmation from an abandoned attempt (`ModeStarted` for a mode nobody is waiting for) is
  stopped rather than adopted;
* a switch that never confirms fails safe to `IDLE` and increments `failedAttempts`.

## Still missing

* The USB accessory handshake (AOAP) — `AaDuplex` is the seam. Until then the receiver is started by
  the attach broadcast but waits on TCP.
* Android Auto's real wire protocol (protobuf over TLS, service discovery, media/audio/sensor
  channels), which is what makes a retail phone work.
* Rotation while streaming. The video size is fixed when the session starts, so turning the phone
  sideways mid-session keeps the old shape until the next connection. `VirtualDisplay.resize` plus an
  encoder restart is the fix; neither is wired up.
* Multi-touch. `AaMessages.Touch` carries one pointer; pinch-zoom will not work until the message
  grows a pointer list.
* Audio routing and the media keys the CarPlay path already forwards.
* The M5 matrix on real cars. Emulator testing cannot cover the USB attach path at all: an AVD cannot
  present itself as a USB device.
