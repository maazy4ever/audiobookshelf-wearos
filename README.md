# ShelfWatch – Audiobookshelf für die Pixel Watch

Standalone Wear-OS-App (Wear OS 3+, also alle Pixel Watches) für [Audiobookshelf](https://www.audiobookshelf.org/), dazu eine optionale Windows-Companion-App.

> Inoffizielles Community-Projekt, nicht mit dem Audiobookshelf-Projekt verbunden.

## Funktionen

- **Login** mit Benutzer/Passwort. Unterstützt werden alte Tokens (ABS < 2.26) und JWT mit automatischem Refresh (ABS ≥ 2.26).
- **Weiterhören, Bibliotheken, Hörbuch-Details**, Cover und Fortschritt in %
- **Streaming** oder **lokale Wiedergabe** heruntergeladener Hörbücher
- **Fortschritts-Sync**
  - Während der Wiedergabe alle 15 s sowie bei Pause, Ende und Wechsel über eine ABS-Playback-Session. Die Hörzeit erscheint so auch in den ABS-Statistiken.
  - Offline wird lokal gespeichert und automatisch nachsynchronisiert, sobald wieder Netz da ist (WorkManager).
  - Bei Konflikten gewinnt der neuere Stand, z. B. wenn danach am Handy weitergehört wurde.
  - Der Sync-Status ist sichtbar: Punkt im Player (grün = synchron, orange = ausstehend) und Zeile „✓ Sync vor 12 s“.
- **Downloads**
  - Laufen als Foreground-Service, auch bei ausgeschaltetem Display.
  - Abgebrochene Dateien werden fortgesetzt (HTTP-Range).
  - Fordert bevorzugt **WLAN** an, weil der Bluetooth-Proxy übers Handy sehr langsam ist. Klappt das nicht, wird das Standardnetz genutzt.
- **Player**: ±15/30 s (einstellbar), Kapitel vor/zurück, Geschwindigkeit 0,75×–2×, **Krone = Lautstärke**, Bluetooth-Kopplung direkt aus dem Player

## Damit Downloads nie einschlafen

| Maßnahme | Wirkung |
|---|---|
| Foreground-Service (`dataSync`) + Wear **Ongoing Activity** | System beendet den Download-Prozess nicht |
| Partial-Wakelock (12 h, pro Hörbuch erneuert) + WLAN-High-Perf-Lock | CPU und WLAN bleiben bei Display-aus aktiv |
| Explizit angefordertes WLAN-Netz | Wear OS schaltet WLAN nicht ab, solange die Anfrage besteht; wird bei Verlust neu angefordert |
| Abbruch → Warten auf Netz → Fortsetzen per HTTP-Range | Kein Neustart der Datei; Abbruch erst nach **30 min ohne jeden Fortschritt** |
| Persistente Warteschlange + `START_STICKY` | Nach Prozess-Kill/Neustart geht es weiter (spätestens beim nächsten Öffnen der App) |
| Android 15+: 6-h-Limit für Datensync wird sauber behandelt | Download pausiert, Fortschritt bleibt erhalten und wird später fortgesetzt |
| Beim ersten Download: Abfrage „Akku-Optimierung deaktivieren“ | Doze drosselt die App nicht |

## Damit die Wiedergabe nie stoppt

| Maßnahme | Wirkung |
|---|---|
| Media3 `MediaSessionService` als Foreground-Service (`mediaPlayback`) | Android beendet den Prozess während der Wiedergabe nicht |
| `WAKE_MODE_NETWORK` | CPU-Wakelock und WLAN-Lock, solange abgespielt wird |
| Wear **Ongoing Activity** | Symbol auf dem Zifferblatt; das System behandelt die Wiedergabe als aktive Nutzeraktivität |
| 10 min Puffer beim Streaming | überbrückt Aussetzer der Bluetooth- oder WLAN-Verbindung |
| Auto-Retry bei Fehlern (bis zu 40×, Backoff bis 30 s) | Wiedergabe setzt nach Netzwerkabbrüchen an derselben Stelle fort |
| `onTaskRemoved` überschrieben | Wegwischen der App stoppt die Wiedergabe nicht |
| **Always-on-Player** (Ambient-Modus) | App bleibt beim Senken des Handgelenks im Vordergrund (gedimmt), statt zum Zifferblatt zu springen |
| Akku-Optimierung abschaltbar (Einstellungen) | verhindert aggressives Doze-Verhalten |

**Empfohlen auf der Uhr:**
- *Einstellungen → Display → Always-on-Display* aktivieren, damit der Always-on-Player wirkt.
- Hörbücher **herunterladen** statt streamen: am zuverlässigsten und akkuschonender.
- Bei Bedarf *Einstellungen → Apps → Medien-Steuerung automatisch starten* deaktivieren, damit die System-Mediensteuerung nicht die App überlagert.
- Mit „Pause bei Kopfhörer-Trennung“ in den App-Einstellungen festlegen, ob beim Trennen der Kopfhörer pausiert oder über den Uhr-Lautsprecher weitergespielt wird.

## Bauen

Voraussetzungen: JDK 17 und das Android SDK (Platform 35). `JAVA_HOME` muss auf das JDK zeigen, und `ANDROID_HOME` bzw. `local.properties` (`sdk.dir=…`) auf das SDK.

```powershell
.\gradlew.bat assembleDebug
# → app\build\outputs\apk\debug\app-debug.apk
```

Optional lässt sich die Server-URL vorbelegen, damit man sie nicht auf der Uhr tippen muss: in `gradle.properties` `abs.server=https://abs.example.com` setzen.

Alternativ: Ordner in Android Studio öffnen. GitHub Actions baut bei jedem Push die Uhr-APK und die Windows-EXE; beide erscheinen als Artefakte des Workflow-Laufs.

## Auf die Pixel Watch installieren

1. Auf der Uhr: *Einstellungen → System → Info → Versionsnummer* 7× antippen → Entwickleroptionen.
2. *Entwickleroptionen → ADB-Debugging* und *Debugging über WLAN* aktivieren. Uhr und PC müssen im selben WLAN sein.
3. Unter *Debugging über WLAN → Neues Gerät koppeln* werden IP:Port und Code angezeigt.
4. Am PC (`adb` liegt im Android SDK unter `platform-tools`):
   ```powershell
   adb pair 192.168.x.x:PAIRPORT      # Code eingeben
   adb connect 192.168.x.x:PORT       # Port aus "Debugging über WLAN"
   adb install -r app\build\outputs\apk\debug\app-debug.apk
   ```

## Windows-Companion-App

`companion-windows/publish/ShelfWatchCompanion.exe` (benötigt die .NET 8 Desktop Runtime).

Funktionen:
- **Downloads auf der Uhr** anzeigen, mit Live-Fortschritt in % (Aktualisierung alle 2 s), Status, Größe und Dauer
- Downloads von der Uhr **entfernen bzw. abbrechen**
- **Audiobookshelf-Zugangsdaten** (Server, Benutzer, Passwort) eingeben und **auf die Uhr übertragen** („Auf Uhr anmelden“)
- **Bibliothek durchsuchen** und **Downloads auf der Uhr starten** (Mehrfachauswahl oder Doppelklick)
- Speicherplatz, Sync-Status und aktuelle Wiedergabe der Uhr anzeigen

So geht's:
1. Uhr: *ShelfWatch → PC-Verbindung → Aktiv*. Die Uhr zeigt IP-Adresse und einen 6-stelligen Code.
2. PC: *Uhr suchen* (findet die Uhr per UDP-Broadcast) oder die IP eintragen, Code eingeben, *Verbinden*.
3. Tab *Bibliothek & Konto*: bei Audiobookshelf anmelden. Bei Bedarf *Auf Uhr anmelden*.
4. Hörbücher auswählen → *Auf Uhr herunterladen*. Der Fortschritt erscheint im Tab *Auf der Uhr*.

Technik und Sicherheit:
- Die Uhr stellt im Heimnetz eine HTTP-API bereit (TCP 8765) und antwortet auf die Suche (UDP 8766).
- Jeder Zugriff braucht den 6-stelligen Code. Nach 5 Fehlversuchen ist die IP 60 s gesperrt. *Neuer Code* auf der Uhr trennt alle bekannten PCs.
- Die PC-Verbindung hält das WLAN der Uhr aktiv und schaltet sich nach 30 min ohne Zugriff ab, solange kein Download läuft.
- Die Companion-App speichert Zugangsdaten und Code mit Windows-DPAPI verschlüsselt in `%APPDATA%\ShelfWatchCompanion\settings.json`.
- Falls die Windows-Firewall die Suche blockiert, die IP manuell eintragen.

Bauen: `cd companion-windows\ShelfWatchCompanion` und dann `dotnet publish -c Release -r win-x64 --self-contained false -p:PublishSingleFile=true -o ..\publish`.

## Hinweise

- **Server-Erreichbarkeit:** Ohne eigenes WLAN läuft der Verkehr der Uhr über das Handy. Ist ABS nur über Tailscale erreichbar, muss Tailscale auf dem Handy aktiv sein. Downloads per WLAN funktionieren dann nur, wenn der Server auch direkt im WLAN erreichbar ist; sonst fällt die App automatisch auf das Standardnetz zurück.
- HTTP (ohne TLS) und selbst installierte Zertifikate sind für Heimserver erlaubt.
- Podcasts werden derzeit nicht unterstützt, nur Hörbücher.

## Lizenz

[GNU General Public License v3.0](LICENSE). Veränderte Versionen, die weitergegeben werden, müssen ebenfalls unter der GPL-3.0 mit Quellcode veröffentlicht werden.
