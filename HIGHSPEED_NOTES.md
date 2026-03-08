# High-Speed Notes (120/240 fps)

Stand: 2026-03-08

## Kurzfazit

- Der Camera2-High-Speed-Pfad ist im Projekt integriert.
- Wechsel Main <-> Settings funktioniert wieder stabil.
- 120 fps koennen je nach Kamera-ID angeboten werden, sind aber nicht auf jeder Kamera praktisch stabil.
- Fuer die 2. Kamera ist aktuell beobachtet:
  - Vorschau stottert deutlich.
  - Aufnahme wirkt effektiv wie 60 fps trotz 120-fps-Einstellung.

## Wichtige technische Entscheidungen

- High-Speed laeuft ueber `Camera2` constrained high-speed session (nicht ueber CameraX).
- Bei `targetFps >= 120` wird auf `HighSpeedCamera2Session` umgeschaltet.
- Bei `targetFps < 120` laeuft der Standardpfad ebenfalls ueber Camera2 Surface-to-Surface (`Camera2RecordSession`), um CPU-Last durch YUV-Kopien zu vermeiden.
- High-Speed-Preview nutzt `SurfaceView` (nicht `TextureView`) und setzt feste Groesse auf Profilgroesse.
- Encoder nutzt im High-Speed-Pfad eine `MediaCodec`-Input-Surface.

Betroffene Dateien:

- `app/src/main/java/com/precorderone/camera/HighSpeedCamera2Session.kt`
- `app/src/main/java/com/precorderone/camera/Camera2RecordSession.kt`
- `app/src/main/java/com/precorderone/camera/PrecorderEngine.kt`
- `app/src/main/java/com/precorderone/ui/SettingsFragment.kt`
- `app/src/main/java/com/precorderone/MainActivity.kt`
- `app/src/main/res/layout/activity_main.xml`

## Guardrail gegen Regressions (verbindlich)

- Der Live-Capture-Pfad bleibt Surface-basiert (Camera2 -> Encoder-Surface), ohne CPU-YUV-Kopie pro Frame.
- `ImageAnalysis` darf nur im Fallback-Pfad aktiv sein.
- Kamera-IDs werden nicht pauschal ausgefiltert; explizit gewaehlte Kamera-ID hat Vorrang.
- Aenderungen an Aufnahme/Preview muessen vor Merge pruefen:
  - 30/60/120 fps stabil
  - Fokus setzen/zuruecksetzen funktioniert
  - Trigger-Save funktioniert

## Bereits gefixter Fehler (wichtig)

Frueherer harter Fehler:

- `High-Speed-Session Start fehlgeschlagen`
- `IllegalArgumentException: Surface size 720x1414 is not part of the high speed supported size list [1280x720, 1920x1080, ...]`

Erkenntnis:

- Fuer constrained high-speed muessen die Session-Surfaces exakt auf einer vom Geraet gemeldeten High-Speed-Groesse liegen.
- UI-Layout-Groessen (z. B. 720x1414) duerfen nicht als Session-Surface-Groesse verwendet werden.

## Log-Einordnung

### Erwartbares "Vendor-/Framework-Noise"

Diese Meldungen sind auf vielen Geraeten normal und nicht automatisch App-Bugs:

- `Access denied finding property "vendor.display.enable_optimal_refresh_rate"`
- `Media Quality Service not found`
- `Failed to query component interface for required system resources: 6`
- `Codec2Client query -- param skipped ...`
- `CompositionEngine ... Invalid device requested composition type change`
- `SurfaceFlinger ... alpha changed ... window_animation`
- `WindowManager ... destroySurfaces ... SettingsActivity/MainActivity`
- `WindowOnBackDispatcher ... sendCancelIfRunning`
- `qdgralloc ... getInterlacedFlag ... defaulting to interlaced_flag = 0`

Mit "Vendor-Noise" ist gemeint: Logs aus Treiber-/Herstellerkomponenten oder Android-Framework, die oft laut sind, ohne dass die App funktional kaputt ist.

### Aktuell relevante Spam-Meldungen

- `FrameNumberTracker ... frame number X is a repeat` (E)
- `PipelineWatcher ... frameIndex not found` (D)

Wichtig:

- Diese Meldungen kommen aus Framework/Codec-Pfad.
- Sie lassen sich in der App nicht "abfangen" oder unterdruecken.
- Sie fuellen nicht unbegrenzt den Geraetespeicher (Logcat-Ringpuffer rotiert).
- Sie koennen in Logcat ausgeblendet werden.

Beispiel-Filter:

```text
package:com.precorderone -tag:FrameNumberTracker -tag:PipelineWatcher -tag:SurfaceFlinger -tag:CompositionEngine -tag:BLASTBufferQueue -tag:qdgralloc

```

Erweiterter Filter fuer Navigation/Transition-Logs:

```text
package:com.precorderone -tag:FrameNumberTracker -tag:PipelineWatcher -tag:SurfaceFlinger -tag:CompositionEngine -tag:BLASTBufferQueue -tag:qdgralloc -tag:WindowOnBackDispatcher -tag:WindowManager
```

Oder nur Warnungen/Fehler:

```text
package:com.precorderone level:WARN -tag:FrameNumberTracker -tag:PipelineWatcher
```

## Build-/Run-Workflow (mit Codex Desktop)

Wenn Codex lokal im Workspace aendert:

- In Android Studio reicht in der Regel **direkt Play**, weil Gradle bei Bedarf inkrementell baut.
- `Sync Project with Gradle Files` nur bei Buildscript-/Dependency-Aenderungen.
- `Clean Project` nur bei kaputten Caches/seltsamem Build-Verhalten.
- Git-Pull/Update nur, wenn sich der Stand im Remote geaendert hat.

## Offener Punkt: Kamera 2 (neue Beobachtung)

Symptom bei umgeschalteter Kamera:

- 120 fps in Settings auswählbar.
- Preview stark ruckelig.
- Resultat subjektiv/effektiv 60 fps.

Moegliche Ursachen (noch offen):

- Kamera meldet zwar 120 als High-Speed-Range, liefert aber im realen Stack viele Wiederholframes.
- Device-/HAL-spezifische Begrenzung fuer genau diese Kamera-ID.
- Interne Umstellung/Flush beim Activity-Wechsel erzeugt viele "repeat"/"frameIndex not found"-Ereignisse.

## Update 2026-03-08 (nach weiteren Tests)

Implementiert:

- High-Speed-Angebot bleibt gemaess Kamera-Capabilities sichtbar (keine kuenstliche Begrenzung in der App).
- Auch variable Ranges (z. B. `30..120`) werden weiterhin angeboten, wenn das Geraet sie meldet.
- Hinweis fuer QA: "angebotene FPS" bedeutet nicht immer "stabil gelieferte FPS" auf jeder Kamera-ID.

## Update RC3: Multi-Kamera Stabilitaet

Implementiert:

- Camera2-Normalpfad auf mehrere Kamera-IDs erweitert (keine pauschale Ausgrenzung).
- Explizit gewaehlte Kamera-ID wird beim Binding priorisiert.
- Pro Kamera wird eine fps-sichere Aufnahmegroesse bevorzugt (basierend auf `getOutputMinFrameDuration`), besonders fuer 60fps+.
- Pipeline-Meldung als Toast + Log (`CAMERA2_NORMAL`, `CAMERA2_HIGHSPEED`, `CAMERAX_FALLBACK`) zur schnellen Diagnose pro Kamera.

Ziel:

- Stabilere FPS auch auf Sekundaer-/Tele-/Ultraweit-Kameras, ohne Rueckfall auf CPU-intensive Pfade.

Grund:

- Die App soll keine Herstellercapabilities verstecken; Qualitaets-/Stabilitaetsgrenzen einzelner Linsen sind dann ein Geraeteverhalten.

## Wichtige Guardrail: Features und FPS (verbindlich)

- Der Aufnahme-/Encode-Pfad darf nicht verlangsamt werden.
- Zoom-Feature wurde aus Performancegruenden vorerst komplett entfernt.
- Keine Kamera-Crop-/Zoom-Operationen im laufenden Capture-Loop.
- Falls Zoom spaeter zurueckkommt: nur mit klarer FPS-Absicherung und standardmaessig aus.

## Releasekandidat

- RC2 gesetzt: `versionName = 1.0-RC2`, `versionCode = 2`.

## Debug-Plan fuer naechsten Chat

1. Pro Kamera-ID einen kurzen Capability-Dump loggen:
   - `highSpeedVideoFpsRanges`
   - `getHighSpeedVideoSizesFor(range)`
   - ausgewaehltes Profil (size + range)
2. Im Overlay gleichzeitig beobachten:
   - Source FPS
   - Queued FPS
   - Encoded FPS
3. Optional fuer Test:
   - auf fix 120x120-Range bestehen
   - bei Abweichung expliziter Fallback + Toast "kamera liefert kein echtes 120"
4. Spam in Logcat weiterhin filtern, nicht als Crash-Indikator behandeln.
