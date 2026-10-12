# Precorder-One (Android)

Android app for continuous ring-buffer recording in RAM.

- Recording starts immediately when the app opens.
- Trigger by button or optionally volume-down.
- On trigger, the latest 5s/10s ring buffer is saved as MP4.
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
