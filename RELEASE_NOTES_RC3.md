# Precorder-One 1.0-RC3 (2026-03-08)

## Zusammenfassung

RC3 stabilisiert die Aufnahme-Pipeline fuer mehrere Kamera-IDs und dokumentiert verbindliche Performance-Guardrails, damit CPU-lastige Regressions nicht erneut eingefuehrt werden.

## Enthaltene Aenderungen

- Live-Aufnahmepfad fuer normale FPS auf Camera2 Surface-to-Surface vereinheitlicht:
  - Kamera -> Preview Surface + MediaCodec Input Surface -> Encoded Ringbuffer
- Neue Session fuer den normalen Camera2-Pfad:
  - `Camera2RecordSession`
- Fokus/AF-Reset funktionieren in normalem Camera2-Pfad und High-Speed-Pfad.
- Kameraauswahl erweitert:
  - Alle gemeldeten Kamera-IDs koennen ausgewaehlt werden (nicht nur eine Teilmenge).
  - Explizit gewaehlte Kamera-ID wird beim Binding priorisiert.
- Pro Kamera fps-sichere Groessenauswahl verbessert:
  - Verwendet `getOutputMinFrameDuration(...)`, um bei 60fps+ stabilere Groessen zu bevorzugen.
- Pipeline-Transparenz eingebaut:
  - Toast + Logcat-Meldung mit aktivem Pfad (`CAMERA2_NORMAL`, `CAMERA2_HIGHSPEED`, `CAMERAX_FALLBACK`) inkl. Kamera-ID/FPS.

## Guardrails (verbindlich)

- Keine CPU-YUV-Kopie im Live-Capture-Pfad.
- `ImageAnalysis` ist nur als Fallback vorgesehen, nicht als Standard-Aufnahmepfad.
- Neue Features duerfen keine per-frame Pixelkopien im laufenden Capture-Loop einfuehren.
- Vor Merge mindestens pruefen:
  - 30/60/120 fps Stabilitaet
  - Fokus setzen/ruecksetzen
  - Trigger-Save

## Version

- `versionName`: `1.0-RC3`
- `versionCode`: `3`
