# Precorder-One 1.0-RC2 (2026-03-08)

## Zusammenfassung

RC2 stabilisiert den High-Speed-Pfad (Camera2 constrained high-speed), behebt den Session-Fehler beim Wechsel zwischen Main/Settings und hält die FPS-Auswahl bewusst capability-basiert.

## Enthaltene Änderungen

- High-Speed-Aufnahme über Camera2 constrained high-speed integriert (`targetFps >= 120`).
- High-Speed-Preview auf `SurfaceView` umgestellt.
- Session-Surfaces werden auf unterstützte High-Speed-Größen gebracht (Fix für vorherige `IllegalArgumentException` bei nicht unterstützten Surface-Größen).
- Main/Settings-Wechsel funktioniert stabil, inklusive erneuter Initialisierung der Session.
- Erweiterte FPS-Auswahl in den Settings (inkl. High-Speed-Capabilities der gewählten Kamera-ID).
- UI zeigt weiterhin an, was die Kamera als Capability meldet (keine künstliche Limitierung durch die App).

## Bekannte Einschränkungen

- Auf einzelnen Kamera-IDs kann ein angebotener 120-fps-Modus in der Praxis nur mit ~60 fps laufen (Treiber/HAL/ISP-Geräteverhalten).
- Häufige Framework-/Vendor-Logs in Logcat (`SurfaceFlinger`, `CompositionEngine`, `qdgralloc`, `Codec2Client`) sind erwartbar und nicht automatisch ein App-Fehler.

## Version

- `versionName`: `1.0-RC2`
- `versionCode`: `2`
