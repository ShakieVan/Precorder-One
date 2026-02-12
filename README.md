# Precorder-One (Android)

Precorder-One ist eine Android-App, die kontinuierlich Video in einem RAM-Ringpuffer hält und beim Trigger die letzten Sekunden als MP4 speichert.

## Features
- Trigger über Schaltfläche oder `Volume Down`
- RAM-Ringpuffer für die letzten 5s oder 10s
- Speichern als MP4 in konfigurierbaren Zielordner (Standard: `DCIM/Precorder-One`)
- Kamera-Auswahl (Camera2-Kamera-ID)
- Torch (Kameralicht)
- Einstellbare Aufnahme-FPS und Speicher-FPS (für Zeitlupeneffekt)
- Digitalzoom

## Build
```bash
./gradlew assembleDebug
```
