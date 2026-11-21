# ShelfWatch – Audiobookshelf for the Pixel Watch

🇬🇧 **English** · 🇩🇪 [Deutsch](README.de.md)

A standalone Wear OS app (Wear OS 3+, i.e. every Pixel Watch) for [Audiobookshelf](https://www.audiobookshelf.org/), plus an optional Windows companion app. Both apps are available in English and German; the language follows the system language.

> Unofficial community project, not affiliated with the Audiobookshelf project.

## Download

Get the latest version from **[Releases](../../releases/latest)**:

| File | What it is |
|---|---|
| `ShelfWatch-<version>.apk` | Wear OS app for the watch (install via ADB, see below) |
| `ShelfWatchCompanion-<version>-win-x64.exe` | Windows companion app, self-contained (no .NET needed) |
| `ShelfWatchCompanion-<version>-win-x64-net8.exe` | Small Windows build, needs the [.NET 8 Desktop Runtime](https://dotnet.microsoft.com/download/dotnet/8.0) |

## Features

- **Sign in** with username/password. Supports legacy tokens (ABS < 2.26) and JWT with automatic refresh (ABS ≥ 2.26).
- **Continue listening, libraries, audiobook details**, covers and progress in %
- **Streaming** or **offline playback** of downloaded audiobooks
- **Progress sync**
  - While playing: every 15 s and on pause, end and book change via an ABS playback session. Listening time also shows up in the ABS statistics.
  - Offline progress is stored locally and synced automatically once the network is back (WorkManager).
  - On conflicts the newer position wins, e.g. if you kept listening on your phone afterwards.
  - Sync status is visible: dot in the player (green = synced, orange = pending) and a line like "✓ Synced 12 s ago".
- **Downloads**
  - Run as a foreground service, also with the display off.
  - Interrupted files are resumed (HTTP range requests).
  - Prefers **Wi-Fi**, because the Bluetooth proxy through the phone is very slow. Falls back to the default network if that fails.
- **Player**: ±15/30 s (configurable), previous/next chapter, speed 0.75×–2×, **crown = volume**, Bluetooth pairing right from the player

## Downloads that never fall asleep

| Measure | Effect |
|---|---|
| Foreground service (`dataSync`) + Wear **Ongoing Activity** | The system does not kill the download process |
| Partial wake lock (12 h, renewed per audiobook) + high-perf Wi-Fi lock | CPU and Wi-Fi stay active with the display off |
| Explicitly requested Wi-Fi network | Wear OS keeps Wi-Fi on while the request is active; re-requested if lost |
| Disconnect → wait for network → resume via HTTP range | No restart of the file; gives up only after **30 min without any progress** |
| Persistent queue + `START_STICKY` | Continues after a process kill/reboot (at the latest when the app is opened again) |
| Android 15+: 6 h limit for data sync is handled cleanly | Download pauses, progress is kept and resumed later |
| On the first download: prompt to disable battery optimization | Doze does not throttle the app |

## Playback that never stops

| Measure | Effect |
|---|---|
| Media3 `MediaSessionService` as a foreground service (`mediaPlayback`) | Android does not kill the process while playing |
| `WAKE_MODE_NETWORK` | CPU wake lock and Wi-Fi lock while playing |
| Wear **Ongoing Activity** | Icon on the watch face; the system treats playback as an active user activity |
| 10 min buffer when streaming | Bridges dropouts of the Bluetooth or Wi-Fi connection |
| Auto-retry on errors (up to 40×, backoff up to 30 s) | Playback continues at the same position after network drops |
| `onTaskRemoved` overridden | Swiping the app away does not stop playback |
| **Always-on player** (ambient mode) | The app stays in the foreground (dimmed) when you lower your wrist instead of returning to the watch face |
| Battery optimization can be disabled (settings) | Prevents aggressive Doze behaviour |

**Recommended on the watch:**
- Enable *Settings → Display → Always-on display* so the always-on player works.
- **Download** audiobooks instead of streaming: most reliable and easier on the battery.
- If needed, disable *Settings → Apps → Auto-launch media controls* so the system media controls don't cover the app.
- Use "Pause when headphones disconnect" in the app settings to choose between pausing and continuing on the watch speaker.

## Installing on the Pixel Watch

1. On the watch: tap *Settings → System → About → Build number* 7× → developer options.
2. Enable *Developer options → ADB debugging* and *Wireless debugging*. Watch and PC must be on the same Wi-Fi.
3. *Wireless debugging → Pair new device* shows IP:port and a pairing code.
4. On the PC (`adb` is part of the Android SDK platform tools):
   ```powershell
   adb pair 192.168.x.x:PAIRPORT      # enter the code
   adb connect 192.168.x.x:PORT       # port from "Wireless debugging"
   adb install -r ShelfWatch-1.0.0.apk
   ```

## Windows companion app

Features:
- Shows the **downloads on the watch** with live progress in % (refreshed every 2 s), status, size and duration
- **Remove or cancel** downloads on the watch
- Enter **Audiobookshelf credentials** (server, username, password) and **send them to the watch** ("Sign in on watch")
- **Browse the library** and **start downloads on the watch** (multi-select or double-click)
- Shows the watch's storage, sync status and current playback

How to use:
1. Watch: *ShelfWatch → PC connection → On*. The watch shows its IP address and a 6-digit code.
2. PC: *Find watch* (discovers the watch via UDP broadcast) or enter the IP, enter the code, *Connect*.
3. Tab *Library & account*: sign in to Audiobookshelf. If needed, *Sign in on watch*.
4. Select audiobooks → *Download to watch*. Progress appears in the *On the watch* tab.

Technical details and security:
- The watch provides an HTTP API on your home network (TCP 8765) and answers discovery requests (UDP 8766).
- Every request needs the 6-digit code. After 5 failed attempts the IP is blocked for 60 s. *New code* on the watch disconnects all known PCs.
- The PC connection keeps the watch's Wi-Fi active and switches itself off after 30 min without access, as long as no download is running.
- The companion app stores credentials and the code encrypted with Windows DPAPI in `%APPDATA%\ShelfWatchCompanion\settings.json`.
- If Windows Firewall blocks discovery, enter the IP manually.

## Building

**Watch app** – requires JDK 17 and the Android SDK (platform 35). `JAVA_HOME` must point to the JDK, and `ANDROID_HOME` or `local.properties` (`sdk.dir=…`) to the SDK.

```powershell
.\gradlew.bat assembleDebug
# → app\build\outputs\apk\debug\app-debug.apk
```

Release builds are signed with the key from `keystore.properties` (not part of the repository: `storeFile`, `storePassword`, `keyAlias`, `keyPassword`). Without that file, the debug key is used.

Optionally pre-fill the server URL so you don't have to type it on the watch: set `abs.server=https://abs.example.com` in `gradle.properties`.

**Windows companion** – requires the .NET 8 SDK:

```powershell
cd companion-windows\ShelfWatchCompanion
dotnet publish -c Release -r win-x64 --self-contained false -p:PublishSingleFile=true -o ..\publish
```

GitHub Actions builds the watch APK and the Windows EXE on every push; both are attached as artifacts of the workflow run.

## Notes

- **Server reachability:** Without its own Wi-Fi the watch routes traffic through the phone. If ABS is only reachable via Tailscale, Tailscale must be active on the phone. Wi-Fi downloads only work if the server is also directly reachable on the Wi-Fi; otherwise the app automatically falls back to the default network.
- Plain HTTP (no TLS) and user-installed certificates are allowed for home servers.
- Podcasts are not supported yet, only audiobooks.

## License

[GNU General Public License v3.0](LICENSE). Modified versions that are distributed must also be released under the GPL-3.0 with source code.
