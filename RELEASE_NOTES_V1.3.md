# Precorder-One 1.3.0

## Neu

- **Nachlauf:** Nach dem Auslösen läuft die Aufnahme noch 1, 2, 3 oder 5 Sekunden weiter, dann wird gespeichert – der
  Clip zeigt also, was vor *und* nach dem Moment passiert ist. Oben läuft ein Countdown („NACHLAUF 2 s“). Einstellbar im
  Schnellmenü und unter Einstellungen → Aufnahme. Wird die App währenddessen verlassen, wird sofort gespeichert.
- **Fernauslöser:** Neben der Leiser-Taste lösen jetzt auch Lauter-Taste, Bluetooth-Selfie-Auslöser, Enter, Kamera-
  und Kopfhörertaste aus. Gedrückt halten löst nur einmal aus; die Lautstärke ändert sich dabei nicht.
- **Ton:** Unter Einstellungen → Aufnahme → „Ton aufnehmen“. Der Ton läuft in einem eigenen Puffer neben dem Video
  und kommt in Echtzeit-Clips (Aufnahme- gleich Wiedergabe-Bildrate, z. B. 30/30 oder 60/60). Bei Zeitlupe wird ohne
  Ton gespeichert. Die Mikrofon-Berechtigung wird erst beim Einschalten abgefragt. Oben erscheint dann „· Ton“.

## Geändert

- Der Zeitlupen-Faktor richtet sich nach der Einstellung (z. B. 120 → 30 fps = 4×), angewendet auf die echten
  Zeitstempel. Echtzeit bleibt so immer Echtzeit, auch wenn die Kamera weniger Bilder liefert als eingestellt.
