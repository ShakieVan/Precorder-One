# Precorder-One 1.0-RC4 (2026-03-08)

## Zusammenfassung

RC4 fuehrt feste native Tele-Zoom-Stufen fuer den Camera2-Livepfad ein und verbessert die Zoom-UI fuer bessere Lesbarkeit auf Geraeten wie dem Galaxy S24 Ultra.

## Enthaltene Aenderungen

- Native Zoom-Stufen (optisch priorisiert) integriert:
  - Zielstufen: `0.6x`, `1x`, `3x`, `5x`
  - Stufen werden pro Kamera nur angezeigt, wenn sie innerhalb der vom Geraet gemeldeten `CONTROL_ZOOM_RATIO_RANGE` liegen.
  - Hybrid-Stufen (`2x`, `10x`) werden in der App-UI nicht angeboten.
- Camera2-Sessionsteuerung erweitert:
  - `CONTROL_ZOOM_RATIO` wird im normalen Camera2-Pfad und im High-Speed-Pfad gesetzt.
  - Zoomwechsel laufen ohne CPU-basierte Frame-Crops im App-Code.
- UI-Anpassung Zoom-Buttons:
  - Zentrierter Text, breitere Buttons, angepasster Rand/Shape (Pill-Style).
  - Aktive Stufe wird visuell hervorgehoben.
- Notizen zur Log-Einordnung aktualisiert:
  - Vendor-/Framework-Meldungen wie
    `Failed to query component interface for required system resources: 6`,
    `Unable to open libpenguin.so`,
    sowie kurzfristige `Long monitor contention` beim Camera2-Reconfigure sind dokumentiert und nicht automatisch ein funktionaler Defekt.

## Guardrails (verbindlich)

- Live-Capture bleibt Surface-basiert (Camera2 -> Encoder-Surface), ohne per-frame CPU-YUV-Kopien.
- Zoom-Feature darf keine CPU-intensiven Pixelkopien in den laufenden Capture-Loop einfuehren.
- `ImageAnalysis` bleibt Fallback-only.

## Version

- `versionName`: `1.0-RC4`
- `versionCode`: `4`
