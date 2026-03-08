# Precorder-One (Android)

Android app for continuous ring-buffer recording in RAM.

- Recording starts immediately when the app opens.
- Trigger by button or optionally volume-down.
- On trigger, the latest 5s/10s ring buffer is saved as MP4.
- Config for camera, FPS, torch, and storage location.

## Current status

This is a working prototype based on CameraX + MediaCodec.
Depending on device vendor, additional tuning for color format/encoder may be needed.

Current release candidate: `1.0-RC3`.

## Performance rule (mandatory)

- Live recording path must stay as lean as possible.
- Features that reduce capture FPS are removed or disabled by default.

## Capture architecture guardrail (mandatory)

- Primary live pipeline (all regular FPS) must use Camera2 surface-to-surface capture:
  - Camera -> Preview Surface + MediaCodec input Surface -> encoded ring buffer
- Do not reintroduce `ImageAnalysis` + YUV copy on the live path.
- CameraX + `ImageAnalysis` is fallback-only for device compatibility/errors, not the default capture path.
- Any future feature (zoom, filters, overlays, analysis) must not add per-frame CPU pixel copies to the live capture loop.
