# Precorder-One 1.4.0

## Neu

- **Puffer bis zu einer Minute:** Die Puffer-Länge lässt sich auf 5, 10, 30 oder 60 Sekunden stellen (Schnellmenü und
  Einstellungen → Aufnahme). Der Puffer liegt weiterhin im Arbeitsspeicher, höchstens 512 MB. Bei sehr hoher
  Auflösung oder Bildrate senkt die App bei langen Puffern die Datenrate so weit, dass die volle Länge hineinpasst.
- **Timecode im Bild:** Unten links steht die Laufzeit des Clips als Min:Sek:Millisekunden, zum Beispiel `00:03:333`,
  beginnend bei `00:00:000` auf dem ersten Bild. Gezählt wird die Zeit, die vor der Kamera wirklich vergangen ist –
  in Zeitlupe läuft der Timecode also entsprechend langsamer (bei 240 fps rund 4 ms pro Bild). Er steht aufrecht
  unten links, egal ob im Hoch- oder Querformat, mit Rück- oder Frontkamera aufgenommen wurde.
  Ein- und ausschalten: Einstellungen → Aufnahme → „Timecode einbrennen“ oder im Schnellmenü; nach dem Update ist er
  eingeschaltet.
- **Speichern mit Timecode dauert länger:** Der Timecode wird erst beim Speichern eingebrannt (der Clip wird dafür neu
  kodiert); die laufende Aufnahme bleibt unberührt und geht währenddessen ohne Lücke weiter. Oben läuft
  „SPEICHERT 42 %“ mit. Richtwerte vom Galaxy S21 und S10: 5 Sekunden in höchster Auflösung brauchen etwa 5 Sekunden,
  eine Minute etwa 50 Sekunden, 5 Sekunden mit 240 fps etwa 15 Sekunden. Ohne Timecode wird wie bisher sofort
  gespeichert. Gelingt das Einbrennen einmal nicht, wird der Clip trotzdem gespeichert, ohne Timecode und mit Hinweis.

## Behoben

- **Ton fehlte im Clip:** Bild und Ton bekamen ihre Zeitstempel von zwei verschiedenen Uhren, die auseinanderlaufen,
  sobald das Handy seit dem Einschalten einmal im Ruhezustand war. Der Ton passte dann zeitlich nicht mehr zum Bild
  und wurde weggelassen. Jetzt nutzen beide dieselbe Uhr.
- Das Schnellmenü öffnet in voller Höhe und lässt sich scrollen, damit auf kleineren Displays nichts abgeschnitten wird.
- Dauert ein Speichervorgang länger, als der Puffer Reserve hat, fängt der Puffer danach sauber neu an, statt einen
  Clip mit Standbild und Zeitsprung zu ermöglichen.
