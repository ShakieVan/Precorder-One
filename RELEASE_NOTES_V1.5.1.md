# Precorder-One 1.5.1

## Behoben

- **Unscharfes Bild bei „60 fps“:** Auf manchen Handys (zum Beispiel dem Galaxy S21) nahm die App mit der
  Voreinstellung 60 fps nur ein winziges Bild von 256×144 Pixeln auf. Vorschau und Clips wirkten dadurch unscharf, und
  Antippen zum Scharfstellen half nicht. Jetzt wird immer das größte Bild aufgenommen, das die Kamera bei der
  eingestellten Bildrate liefert.
- **Zu kurze Clips bei „60 fps“:** In derselben Einstellung konnten gespeicherten Clips die ersten Sekunden fehlen.
  Auch das ist behoben.

## Geändert

- **Bildraten, die die Kamera nicht aufnimmt:** Manche Handys nehmen im normalen Modus höchstens 30 fps auf; schneller
  geht es dort erst wieder mit 120 oder 240 fps. Auf diesen Handys steht 60 fps nicht mehr zur Auswahl. War 60 fps
  eingestellt, stellt die App beim Start auf 30 fps um und weist kurz darauf hin. Zeitlupe gibt es weiterhin mit 120
  oder 240 fps.
- **Kamerawechsel:** Bietet die neue Kamera die eingestellte Bildrate nicht (zum Beispiel 240 fps auf der
  Frontkamera), gilt die nächstniedrigere, ebenfalls mit Hinweis.
- **Diagnose zeigt die Auflösung:** Mit „Diagnose anzeigen“ steht in der Hauptansicht jetzt auch, in welcher Auflösung
  aufgenommen wird.
