# Precorder-One 1.2.0

## Neues Design

- Neue Hauptansicht im Stil einer Kamera-App:
  - Oben ein Status-Chip (PUFFER 57 % / BEREIT / SPEICHERT) und das Aufnahmeprofil, z. B. „120 fps · 4× Zeitlupe“.
  - Um den Auslöser ein Ring, der sich beim Füllen des Puffers bernsteinfarben füllt und grün wird, sobald ein voller
    Clip bereitsteht.
  - Objektiv-Leiste wie in der System-Kamera, Taschenlampe, Belichtung und Kamera-Wechsel direkt erreichbar.
  - Vorschaubild des letzten Clips unten links: antippen zum Abspielen.
  - Symbole drehen sich mit, wenn das Handy quer gehalten wird.
- Neues App-Icon (passt sich auch an farbige Android-Themes an).
- Einstellungen mit Titelleiste, Zurück-Button und Kategorien, Speicherort als lesbarer Pfad.
- Die grüne Diagnosezeile erscheint nur noch, wenn sie unter Einstellungen → Bedienung eingeschaltet ist.

## Aufnahme

- **Puffer bleibt nach dem Speichern voll:** Der Ringpuffer arbeitet jetzt mit Schreib- und Lesezeiger. Beim Speichern
  läuft die Aufnahme weiter, und direkt danach kann der nächste volle Clip gespeichert werden.
- Der Puffer liegt in einem festen Speicherblock außerhalb des Java-Speichers; pro Bild wird nur noch einmal kopiert.
- Zeitlupen-Clips laufen auch dann gleichmäßig, wenn die Kamera einzelne Bilder auslässt oder weniger liefert als
  eingestellt.

## Bild

- **Vorschau ohne Verzerrung:** Sie zeigt das Bild im echten Seitenverhältnis, genau so, wie es aufgenommen wird.
- **Tippen zum Fokussieren trifft die richtige Stelle** – auch bei 3x/5x und mit der Frontkamera. Ein Fokusring zeigt
  den Punkt; „Fokus fixiert ✕“ hebt die Sperre wieder auf.
- **Belichtung funktioniert:** ±2 Blendenstufen in Drittelschritten, auch bei 120/240 fps. Langes Drücken auf das
  Belichtungs-Symbol setzt sie zurück.
