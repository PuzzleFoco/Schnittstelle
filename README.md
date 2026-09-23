# Schnittstelle

Freier, schlanker Videoeditor für Android. Dies ist **v0.1** – der Kern eines
Schnittprogramms, bewusst ohne Übergänge, Cloud- und KI-Funktionen.

## Was drin ist

| Bereich | Umfang in v0.1 |
|---|---|
| Import | Video und Audio über den Android-Dateiauswahl (SAF), Kopie in den App-Speicher |
| Timeline | Drei Spuren: Video, Ton, Text-Overlay; zoom- und scrollbar, Playhead |
| Schnitt | Framegenaues Trimmen an beiden Clip-Kanten, Teilen am Playhead, Reihenfolge per Wischen ändern, Löschen, Duplizieren |
| Text | Overlays mit Größe, Farbe, Position, Ausrichtung, fetter Schrift, Ein-/Ausblendzeit |
| Ton | Musikspur mit Lautstärke 0–200 %, stumm schalten, Schleife wenn die Musik kürzer ist als der Film. Der Originalton importierter Clips bleibt erhalten |
| Filter | Presets (Neutral, Hell, Dunkel, Warm, Kühl, Kräftig) über `Brightness`, `Contrast`, `HslAdjustment` |
| Export | MP4 (H.264/AAC), 720p oder 1080p, Ablage in „Filme/Schnittstelle" plus Teilen-Dialog |
| Projekte | Mehrere Projekte, Speicherung als JSON im App-Verzeichnis |

Bewusst **nicht** in v0.1: Übergänge, Cloud-Funktionen, Auto-Captions,
Rauschunterdrückung und KI-Generierung.

## Technik

- **Media3 Transformer 1.11.1** erzeugt den Film, **CompositionPlayer**
  spielt die Vorschau derselben Komposition ab. Mehrere Sequenzen werden dabei
  gemischt; die Textspur läuft als Kompositionseffekt.
- **`model/TimelineOps.kt` ist frei von Android-Abhängigkeiten**, damit die
  Schnittlogik als JVM-Unit-Test prüfbar ist (Trimmen, Teilen, Verschieben,
  Normalisierung). 23 Tests decken diese Regeln ab, darunter der
  Verstärkungs-Baustein `media/ConstantGainProvider.kt`.
- Oberfläche mit **Jetpack Compose** (Material 3), dunkles Basis-Theme.
- Zeiten im Datenmodell durchgängig in Millisekunden, IDs als UUID.

```
app/src/main/java/com/puzzlefoco/schnittstelle/
├── model/      Model.kt, TimelineOps.kt      (Android-frei, getestet)
├── data/       ProjectStore.kt               (Projekte + Mediendateien)
├── media/      MediaProbe, EffectPresets, TimelineTextOverlay,
│               CompositionFactory, Exporter  (Media3-Schicht)
└── ui/         MainActivity, EditorViewModel, EditorScreen,
                ProjectListScreen, TimelineView, theme/
```

## Bauen

Vorausgesetzt werden JDK 17 und ein Android-SDK mit **Plattform 37.2**
(`compileSdk = 37`, `compileSdkMinor = 2`) und **Build-Tools 37.0.0**.

```bash
export JAVA_HOME="$HOME/tools/jdk17"
export ANDROID_HOME="$HOME/Android/Sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"

# SDK-Teile, falls noch nicht vorhanden:
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" \
    "platforms;android-37.2" "build-tools;37.0.0"

cd schnittstelle
echo "sdk.dir=$ANDROID_HOME" > local.properties   # nicht einchecken

./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Das Ergebnis liegt unter `app/build/outputs/apk/debug/app-debug.apk`.

## Installieren

```bash
adb install -r dist/schnittstelle-0.1.0-debug.apk
```

Alternativ die APK auf das Gerät kopieren und dort öffnen (Installation aus
unbekannten Quellen erlauben). Das Debug-APK ist mit dem üblichen
Android-Debug-Zertifikat signiert (`V2 Signer: CN=Android Debug`), nicht mit
einem echten Release-Schlüssel.

## Bekannte Grenzen

- **Auf dem Testgerät (Pixel 7 Pro, Android 17) läuft der Kern**: Import über den
  Dateidialog, Vorschau-Wiedergabe (framegenau, mit Ton und Text-Overlay) und Export
  wurden mit echtem Material durchgespielt (Details unter „Geprüft").
- Vorschau und Export tragen mehrere Sequenzen, gemischt; praktisch geprüft ist
  bisher eine Video- plus eine Tonspur.
- Nach jeder Änderung wird die Komposition neu aufgebaut; bei langen Projekten
  kann die Vorschau deshalb kurz stocken.
- Der Export läuft im Vordergrund – Bildschirm während des Renderns anlassen.
- Projekte liegen im App-Verzeichnis; ein Verschieben auf ein neues Gerät ist
  noch nicht vorgesehen.

## Geprüft (Pixel 7 Pro, Android 17)

| Prüfung | Ergebnis |
|---|---|
| Bau + Unit-Tests | 23 Tests grün |
| Start und Oberfläche | rendert vollständig, Kaltstart 0,7 s |
| Import (12-s-Clip, 720p) | Timeline zeigt 12,0 s, Dauern korrekt |
| Vorschau-Wiedergabe | läuft durch, Zeitcode wandert, Ende sauber |
| Text-Overlay in der Vorschau | erscheint unten mittig |
| Text-Overlay-Zeitfenster | im Export sichtbar bei 0,5 s, nicht mehr bei 2,0/6,0/11,0 s |
| Export 1080p | 1920×1080, exakt **360 Frames = 12,000 s** bei 30 fps |
| Ton im Export | AAC, Lautheit identisch zur Quelle (−21,1 dB / −14,5 dB) |
| Projektdatei | JSON mit allen Spuren lesbar |

Beweisframes liegen unter `docs/beweis/`.

## Lizenz

GPL-3.0 – siehe [LICENSE](LICENSE).
