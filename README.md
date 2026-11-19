# Floki Store

App-Store für den AYN Thor (benannt nach Hrafna-Flóki, dem Wikinger, der mit Raben navigierte): lädt Emulatoren, Launcher, Recomps, Game Ports, Pokémon- & Companion-Apps, Tools und GPU-Treiber direkt aus den Releases der Projekte.

## Installieren

Das neueste APK liegt unter [Releases](https://github.com/chris2crafter-ops/FlokiStore/releases/latest). Danach hält sich der Store selbst aktuell: Beim Start fragt er GitHub nach einem neueren Release und bietet es zum Installieren an (von Hand: Einstellungen → „Nach neuer Floki-Store-Version suchen“). Er steht außerdem unter „Tools“ im Katalog, dort lassen sich auch ältere Versionen installieren.

## Sprache

Deutsch und Englisch, Standard ist die Systemsprache (Einstellungen → Sprache). Im Code steht jeder sichtbare Text als `t("Deutsch", "English")`; Katalogbeschreibungen und Hinweise stehen in `catalog-src.json` als `description`/`descriptionEn` und `note`/`noteEn` (fehlt Englisch, zeigt die App Deutsch).

## Bauen

```bash
JAVA_HOME=~/Android/jdk ./gradlew :core:test :app:assembleRelease
```

Das APK liegt danach in `app/build/outputs/apk/release/app-release.apk`.

**Signatur:** Updates installiert Android nur mit demselben Schlüssel wie die installierte Version. Der Schlüssel liegt nicht im Repo; `local.properties` zeigt darauf:

```
flokistore.keystore=/pfad/zu/flokistore.keystore
# optional: flokistore.storePassword, flokistore.keyAlias, flokistore.keyPassword
```

## Neue Version veröffentlichen

1. `versionCode` und `versionName` in `app/build.gradle.kts` erhöhen, committen und pushen.
2. `tools/release.sh [notes.md]` baut das Release-APK und legt das GitHub-Release `v<versionName>` mit `FlokiStore-<version>.apk` an.

## Katalog aktualisieren

Die Einträge stehen in `catalog/catalog-src.json`. Das Katalog-Tool prüft jeden Eintrag gegen sein neuestes Release, liest Paketname und Icon per HTTP-Range direkt aus dem APK und schreibt `app/src/main/assets/catalog.json` sowie `app/src/main/assets/icons/`:

```bash
JAVA_HOME=~/Android/jdk ./gradlew :catalogtool:run
```

Felder pro Eintrag: `id`, `name`, `category` (recomps, ports, fangames, emulators, frontends, companions, pc, tools, drivers), `description`, `repo` (owner/name) oder `homepage` (nur Link), `tags`, `apkRegex`, `excludeRegex`, `assetRegex` (Treiber-ZIPs bzw. ZIP mit APK), `apkInZip`, `prerelease`, `note`, `iconUrl`.

Was „＋ App“ im Store aus einem Link machen würde, zeigt das Tool auch am PC:

```bash
JAVA_HOME=~/Android/jdk ./gradlew :catalogtool:run --args="--inspect https://github.com/owner/projekt"
```

## Eigene Apps und Kategorien (ab 1.6)

- **＋ App** (oben rechts, in den Einstellungen oder im Browser über „Teilen → Zum Floki Store hinzufügen“): GitHub-Link eingeben. Der Store sucht das APK im neuesten Release, notfalls in Pre-Releases. Name und Icon liest er aus dem APK, die Beschreibung von der Repo-Seite, und die Kategorie rät er aus Name, Beschreibung und Topics. Releases mit Treiber-ZIPs statt APK werden als Treiber-Quelle angelegt.
- **Bearbeiten** (Detailseite oder Karte lange drücken): Name, Beschreibung, Kategorie und Icon (Bild aus der Galerie, GitHub-Avatar oder Standard). Bei Katalog-Apps gibt es „Original wiederherstellen“, selbst hinzugefügte Apps lassen sich entfernen.
- **Kategorien** (Einstellungen → Kategorien verwalten, oder Tab lange drücken): anlegen, umbenennen, verschieben, löschen (die Apps ziehen in eine wählbare andere Kategorie um).
- Gespeichert wird alles in `files/user.json` der App, getrennt vom mitgelieferten Katalog. Store-Updates überschreiben also nichts.

## Dual-Screen auf dem AYN Thor (ab 1.7)

- Oben: Kategorien und App-Raster (kompaktere Karten). Unten: Icon, Status, große Touch-Knöpfe (Installieren/Öffnen, Alle Details, Bearbeiten), Beschreibung, Version und Release-Notes der oben gewählten App.
- Steuerung: Mit dem D-Pad folgt der untere Bildschirm der Auswahl, A öffnet die ganze Seite. Per Touch zeigt der erste Tipp die App unten, der zweite öffnet sie.
- Detailseite: oben Knöpfe und Versionsinfo, unten Beschreibung und Release-Notes. Treiberseite: unten die Release-Notes der gewählten Version.
- Der untere Bildschirm ist eine `Presentation` mit `FLAG_NOT_FOCUSABLE`: Tippen unten nimmt dem Controller oben nicht den Fokus. Ohne zweiten Bildschirm sieht alles aus wie vorher. Abschalten unter Einstellungen → Zweiter Bildschirm.

## Aufbau

- `core/` – reines Kotlin: GitHub-HTML-Parser (ohne API-Limit), REST-Fallback, ZIP/APK-Leser über Range-Requests, APK-Auswahl, Update-Logik
- `catalogtool/` – Build-Hilfe für Katalog und Icons
- `app/` – Android-App (ohne AndroidX): Tabs, Detailseite, Treiber-Versionen, Download-Service, Installer, Update-Job mit Benachrichtigungen
