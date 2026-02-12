# Precorder-One (Android)

Android-App für eine kontinuierliche Ringpuffer-Aufzeichnung im RAM:

- Startet beim Öffnen der App direkt mit einer laufenden Loop-Aufnahme.
- Trigger per Button oder (optional) Vol-Down.
- Beim Trigger wird der letzte 5s/10s-Ringpuffer als MP4 gespeichert.
- Konfiguration für Kamera, FPS, Torch, Zoom und Speicherort.

## Hinweis zum Stand

Diese Implementierung ist ein funktionsfähiger Prototyp auf Basis von CameraX + MediaCodec.
Je nach Geräte-Hersteller kann Feintuning für Farbformat/Encoder notwendig sein.
