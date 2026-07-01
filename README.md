# TelloPilot

Native Android app (Kotlin) to fly a **DJI / Ryze Tello** drone (standard model,
not the EDU), as an alternative to the official app and to aTello.

Target: **Android 12** (aimed at a OnePlus 7T). `minSdk 26`, `targetSdk 34`.

> ⚠️ **Validation status.** This project was developed without a drone or a phone
> available. What **is verified**: Kotlin compilation of the logic modules and the
> debug APK build via GitHub Actions. What is **NOT verified**: real piloting, the
> UDP link, video decoding and media writing — they depend on hardware and must be
> validated on the first flight (see the checklist).

## MVP features

| # | Feature | Implementation |
|---|---------|----------------|
| 1 | Connection + SDK mode | `TelloController`: Wi-Fi bind, blocking `command` handshake (waits for `ok`), status shown |
| 2 | Dual joystick (mode 2) | Custom `JoystickView` (Canvas, no asset), normalized [-1,1] output → `rc` channels |
| 3 | Takeoff / Land / EMERGENCY | Buttons; a prominent red EMERGENCY cuts the motors |
| 4 | NORMAL / FAST toggle | Stick amplitude multiplier (0.5 vs 1.0), shown visually |
| 5 | Live video | `TelloVideo`: UDP 11111 → Annex-B NAL reconstruction → `MediaCodec` → `TextureView` |
| 6 | Photo | Grab the current `TextureView` frame → JPEG in `Pictures/TelloPilot/` (MediaStore) |
| 7 | Video recording | Mux the H264 stream to MP4 via `MediaMuxer` in `Movies/TelloPilot/` (MediaStore) |
| 8 | Telemetry | Battery %, altitude (h), ground distance (tof) read from the UDP 8890 state |

## Architecture

```
app/src/main/java/com/tellopilot/
├── MainActivity.kt     UI, runtime permissions, Wi-Fi network binding, stick wiring
├── TelloController.kt  UDP sockets 8889/8890, SDK handshake, 20 Hz rc loop, state parsing
├── TelloVideo.kt       UDP 11111, NAL split, MediaCodec (display) + MediaMuxer (REC) + JPEG
├── JoystickView.kt     Reusable custom touch View, auto-recentering
└── TelloState.kt       Thread-safe parsing of the telemetry string
```

### Notable technical points

- **Network gotcha handled**: the Tello Wi-Fi has no internet, so Android keeps
  mobile data as the default network. We use `ConnectivityManager.requestNetwork`
  (transport **WIFI**) then `bindProcessToNetwork(network)` **before** opening the
  sockets, otherwise the UDP never reaches the drone.
- **Blocking SDK handshake**: `connect` sends `command` and waits for the drone's
  `ok` (with retries) before starting the rc loop / `streamon`. Sending
  `takeoff`/`streamon` before that `ok` is silently ignored by the drone.
- **rc loop gated on `flying`**: streaming `rc 0 0 0 0` is a "hold altitude" (hover)
  command, so if it kept flowing after `land` it would override the descent and the
  drone would never touch down. The loop only streams while airborne; `land` /
  `emergency` set `flying=false` to stop the keep-alive. (While flying, the loop runs
  at ~20 Hz to prevent the drone's ~15 s no-command auto-land.)
- **Dedicated threads**: all sockets and decoding run off the main thread; state is
  shared through `Atomic*`.
- **Scoped storage**: no `WRITE_EXTERNAL_STORAGE` permission. Photos and videos go
  through `MediaStore` (with `IS_PENDING`) → visible in the gallery without a storage
  permission on Android 12.
- **H264**: split on Annex-B start codes (`00 00 01`), detect SPS (type 7) and PPS
  (type 8) to configure the decoder and provide the `csd` to the muxer.

## Build

Self-contained Gradle project (wrapper included). No proprietary dependency.

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

### CI

`.github/workflows/android.yml`: on each push, sets up JDK 17 + Android SDK, builds
`assembleDebug` and **uploads the debug APK as an artifact** (`tellopilot-debug-apk`).

Releases are cut via `.github/workflows/release.yml` — see `CLAUDE.md` for the
`[release vX.Y.Z]` semantic-release mechanism.

## Install on the phone

1. Grab the debug APK (CI artifact, GitHub Release, or local build).
2. Allow unknown sources and install the APK.
3. On first launch, grant the **Location** permission (needed to identify the Tello
   Wi-Fi on Android 12; replaced by `NEARBY_WIFI_DEVICES` on Android 13+).

## ✅ Manual test checklist (to do with the drone)

Nothing below could be verified in the sandbox. Validate in order:

- [ ] **Wi-Fi**: power on the Tello, connect the phone to the `TELLO-XXXXXX` network.
      Let Android show "connected, no internet" (do NOT "forget" the network).
- [ ] **Connection**: open the app, tap **CONNECT**. The status should go to
      "Wi-Fi bound — connecting to the Tello", then "SDK mode active" and a battery reply.
- [ ] **Telemetry**: check that Bat %, Alt and Ground update.
- [ ] **Takeoff**: props clear, tap **TAKEOFF**. The drone lifts off and holds a hover
      (the rc loop should keep it up past the 15 s auto-land).
- [ ] **Sticks**: left stick = throttle (up/down) + yaw (left/right); right stick =
      forward/back + lateral strafe. Check directions and no drift at center.
- [ ] **NORMAL / FAST**: confirm FAST increases the amplitude (full authority).
- [ ] **Video**: the live image should appear in the background after CONNECT (streamon
      is sent automatically). Check latency/smoothness.
- [ ] **Photo**: tap **PHOTO**, check the JPEG in the gallery (`Pictures/TelloPilot/`).
- [ ] **REC**: tap **REC**, fly a few seconds, **STOP**. Check the MP4 is **playable in
      the gallery** (`Movies/TelloPilot/`).
- [ ] **LAND**: the drone must land cleanly (no lingering hover). Then **EMERGENCY**
      (on the ground, props clear): verify the motors cut.

## Known limits / to verify on the first flight

- **Stick axis directions**: the mode-2 convention is implemented but the signs (roll/yaw
  in particular) must be confirmed in flight and flipped if needed in
  `MainActivity.setupJoysticks()`.
- **MP4 muxing**: each VCL NAL is written as one sample with a synthetic PTS at 30 fps
  (the Tello stream carries no timestamps). The file should be playable; if the
  duration/rate looks wrong, adjust `FPS` in `TelloVideo`.
- **Video resolution**: initialized to 960×720 (standard Tello stream) then corrected by
  the decoder via `INFO_OUTPUT_FORMAT_CHANGED`.
- **No hardware validation** was possible in the sandbox: piloting, the UDP link,
  decoding and media writing are to be confirmed on the first real flight.

## Out of scope (phase 2)

Bluetooth/OTG gamepad, autopilots (RTH/orbit/dronie), bitrate/resolution settings,
VR/FPV goggles mode.
