# Precorder-One 1.0.0 (2026-03-08)

## Zusammenfassung

Erster offizieller Stable-Release von Precorder-One mit performanter Camera2-Surface-Pipeline, High-Speed-Unterstuetzung und nativen festen Tele-Zoom-Stufen.

## Highlights

- Surface-basierter Live-Aufnahmepfad fuer normale FPS:
  - Kamera -> Preview Surface + MediaCodec Input Surface -> Encoded Ringbuffer
- High-Speed-Aufnahme (capability-basiert) ueber Camera2 constrained high-speed Session.
- Multi-Kamera-Unterstuetzung mit priorisierter expliziter Kamera-ID.
- Feste native Zoom-Stufen in der App:
  - `0.6x`, `1x`, `3x`, `5x`
  - Nur verfuegbare Stufen werden pro Kamera angezeigt.
- Zoom-UI verbessert:
  - Zentrierte Beschriftung
  - Angepasste Button-Breite und klarer Rand
- Fokus setzen/zuruecksetzen in Camera2 normal und high-speed verfuegbar.
- Trigger-Export als MP4 aus dem Ringbuffer.

## Guardrails

- Keine CPU-intensiven per-frame Pixelkopien im Live-Capture-Pfad.
- `ImageAnalysis` nur als Fallback-Pfad.
- Neue Features duerfen die Capture-FPS nicht degradieren.

## Version

- `versionName`: `1.0.0`
- `versionCode`: `4`
