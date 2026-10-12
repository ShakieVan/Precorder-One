# Precorder-One 1.2.1

## Behoben

- **Gespeicherte Clips waren kaputt** (seit 1.2.0): Bei 120 fps kam nur ein Standbild heraus, bei 60 fps ein halb
  zerstörtes Bild. Der MP4-Muxer las jedes Bild vom Anfang des Ringpuffers statt von seiner eigenen Stelle.
  Ein neuer Gerätetest kodiert, puffert, speichert und dekodiert einen Clip und schlägt bei diesem Fehler an.

## Neu

- **Schnellmenü:** Ein Tipp auf die Anzeige oben öffnet Bildrate, Wiedergabe (Zeitlupe), Puffer-Länge und
  Seitenverhältnis – direkt in der Kamera, ohne in die Einstellungen zu wechseln.
