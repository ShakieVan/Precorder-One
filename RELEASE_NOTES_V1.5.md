# Precorder-One 1.5.0

## Neu

- **Ecke für den Timecode wählbar:** Der Timecode kann oben links, oben rechts, unten links oder unten rechts im Bild
  stehen – einstellbar unter Einstellungen → Aufnahme → „Timecode-Position“ und direkt im Schnellmenü („Timecode im
  Bild“: Aus oder eine der vier Ecken). **Voreinstellung ist jetzt oben links** (in 1.4.0 stand er unten links).
  Gemeint ist immer das Bild, wie es abgespielt wird, egal ob im Hoch- oder Querformat aufgenommen wurde.
- **Hinweis „Zeitlupe“ / „Zeitraffer“ im Bild:** Läuft ein Clip nicht in Originalgeschwindigkeit, steht in einer
  oberen Ecke „Zeitlupe 8ד (langsamer als aufgenommen, hier 240 fps mit 30 fps abgespielt) oder „Zeitraffer 2ד
  (schneller als aufgenommen), davor ein roter Punkt, der im Sekundentakt blinkt. Der Hinweis steht oben rechts; steht
  dort der Timecode, weicht er nach oben links aus. Bei Clips in Originalgeschwindigkeit erscheint nichts.
  Ein- und ausschalten: Einstellungen → Aufnahme → „Zeitlupe/Zeitraffer einblenden“; nach dem Update ist der Hinweis
  eingeschaltet.

## Behoben

- **Einbrennen bei 60 fps Wiedergabe in höchster Auflösung:** Auf manchen Handys (zum Beispiel dem Galaxy S10)
  scheiterte das Einbrennen, wenn ein Clip in höchster Auflösung mit 60 Bildern pro Sekunde abgespielt werden sollte
  (etwa mit 30 fps aufgenommen und als Zeitraffer mit 60 fps gespeichert). Der Clip wurde dann ohne Timecode
  gespeichert. Jetzt wird er auch in diesem Fall mit Timecode und Hinweis gespeichert.

## Gut zu wissen

- Auch der Hinweis wird erst beim Speichern eingebrannt. Wer den Timecode ausgeschaltet hat, bekommt Zeitlupen- und
  Zeitraffer-Clips deshalb nur dann weiterhin sofort gespeichert, wenn auch „Zeitlupe/Zeitraffer einblenden“
  ausgeschaltet ist. Clips in Originalgeschwindigkeit ohne Timecode werden wie bisher sofort gespeichert.
