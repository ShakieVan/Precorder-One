# Precorder-One (Android)

Android app for continuous ring-buffer recording in RAM.

- Recording starts immediately when the app opens.
- Trigger by button or optionally volume-down.
- On trigger, the latest 5 to 60 s of the ring buffer are saved as MP4, optionally with the running time and a
  slow-motion/time-lapse label burnt in.
- Config for camera, FPS, torch, and storage location.

## Current status

This is a working prototype based on CameraX + MediaCodec.
Depending on device vendor, additional tuning for color format/encoder may be needed.

Current stable release: `1.0.0`.

## Updates & releases

The app updates itself from GitHub releases (Einstellungen → App → Nach Updates suchen; automatic check once a day).
A release is picked up when it is tagged `vX.Y.Z` and has exactly one asset `PrecorderOne-X.Y.Z.apk`
signed with the release key from `key.properties`.

1. Raise `appVersion` in `app/build.gradle.kts` (the versionCode is derived from it) and merge into `main`.
2. Write release notes, e.g. `RELEASE_NOTES_V1.1.md`.
3. Run `scripts\release.ps1 -Notes RELEASE_NOTES_V1.1.md` (builds, tags, pushes and uploads the APK).

Keep the keystore and `key.properties` backed up. Without the same key, installed apps reject every update.

## Performance rule (mandatory)

- Live recording path must stay as lean as possible.
- Features that reduce capture FPS are removed or disabled by default.

## Capture architecture guardrail (mandatory)

- Primary live pipeline (all regular FPS) must use Camera2 surface-to-surface capture:
  - Camera -> Preview Surface + MediaCodec input Surface -> encoded ring buffer
- The encoded ring buffer (`EncodedFrameRingBuffer`) is one shared-memory block with a write pointer and a read lease
  for exports; each frame is copied exactly once (encoder output -> ring). Exports read from the ring without copying.
- Do not reintroduce `ImageAnalysis` + YUV copy on the live path.
- CameraX + `ImageAnalysis` is fallback-only for device compatibility/errors, not the default capture path.
- Any future feature (zoom, filters, overlays, analysis) must not add per-frame CPU pixel copies to the live capture loop.

## Timecode and speed label (export only)

- The timecode (`MM:SS:mmm`, recording time since the clip's first frame) is burnt in when a clip is saved, never
  while recording: `PrecorderEngine.exportWithOverlay` writes the leased frames as a real-time copy into the cache,
  releases the ring, and `TimecodeBurner` re-encodes that copy (decoder -> OpenGL -> encoder). Recording goes on
  without a hole meanwhile.
- What is burnt in is a `ClipOverlay`: the timecode in a corner of the user's choice (top left unless changed) and,
  for clips that do not play at the speed they were recorded at, a label "Zeitlupe 8×" / "Zeitraffer 2×" with a dot
  that blinks once per second of playback. The label takes the top right corner, or the top left one when the
  timecode is top right. Corners always mean the picture as it is shown: `FramePlacement` maps them into the stored
  frame by the clip's orientation hint.
- The burner's encoder is set up with a frame rate inside H.264 level 5.2 and a bit rate lowered by the same share
  (the pictures keep their bits and their real times): asked for level 6 (4032x2268 played at 60 fps), the Galaxy
  S10's encoder fails on the first picture.
- Do not move the timecode onto the live path (camera -> OpenGL -> encoder): it would cost capture FPS, and the clip's
  start is not known while a frame is recorded.
- Video timestamps from the encoder surface are CLOCK_MONOTONIC on every tested device, whatever the sensor reports;
  sound has to use the same clock (`PrecorderEngine.startAudio`).

## Tests

- `gradlew testDebugUnitTest`: ring buffer, timecode and overlay layout, on the PC.
- `gradlew connectedDebugAndroidTest` (set `ANDROID_SERIAL` to pick one phone): real codecs, the timecode burner, the
  settings screens and `LiveCaptureTest`, which records and saves clips with the real camera. The tests ask for the
  activities to be shown above the lock screen themselves, so a locked test phone is enough.
