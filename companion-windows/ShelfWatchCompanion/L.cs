using System.Globalization;

namespace ShelfWatchCompanion;

/// <summary>
/// UI texts. German when the Windows display language is German, English otherwise.
/// Used from XAML via {x:Static local:L.Name} and from code.
/// </summary>
public static class L
{
    public static readonly bool De = CultureInfo.CurrentUICulture.TwoLetterISOLanguageName == "de";

    private static string T(string en, string de) => De ? de : en;

    // --- Window / watch connection ---
    public static string FindWatch => T("Find watch", "Uhr suchen");
    public static string Connect => T("Connect", "Verbinden");
    public static string NotConnectedHint => T(
        "Not connected. On the watch: ShelfWatch → PC connection → On, then “Find watch” and enter the code shown.",
        "Nicht verbunden. Auf der Uhr: ShelfWatch → PC-Verbindung einschalten, dann „Uhr suchen“ und den angezeigten Code eingeben.");
    public static string Searching => T("Searching for the watch on the network…", "Suche Uhr im Netzwerk…");
    public static string NoWatchFound => T(
        "No watch found. Is “PC connection” on the watch switched on? Otherwise enter the IP manually.",
        "Keine Uhr gefunden. Ist „PC-Verbindung“ auf der Uhr aktiv? Sonst IP manuell eintragen.");
    public static string FoundOne(string w) => T($"Found: {w}. Enter the code from the watch and click “Connect”.",
        $"Gefunden: {w}. Code von der Uhr eingeben und „Verbinden“.");
    public static string FoundMany(int n, string w) => T($"{n} watches found, using {w}.", $"{n} Uhren gefunden, verwende {w}.");
    public static string EnterIp => T("Please enter the watch IP or use “Find watch”.", "Bitte IP der Uhr eintragen oder „Uhr suchen“.");
    public static string EnterCode => T("Please enter the code from the watch.", "Bitte den Code von der Uhr eingeben.");
    public static string ConnectingWatch => T("Connecting…", "Verbinde…");
    public static string ConnectedWatch => T("Connected to the watch.", "Mit der Uhr verbunden.");
    public static string ConnectionLost(string msg) => T("Connection lost: ", "Verbindung unterbrochen: ") + msg;
    public static string SignedInAs(string user, string server) => T($"signed in as {user} @ {server}", $"angemeldet als {user} @ {server}");
    public static string SignInExpired => T(" – sign-in expired!", " – Anmeldung abgelaufen!");
    public static string NotSignedIn => T(
        "not signed in to Audiobookshelf → tab “Library & account” → “Sign in on watch”",
        "nicht bei Audiobookshelf angemeldet → Tab „Bibliothek & Konto“ → „Auf Uhr anmelden“");
    public static string ConnectedTo(string device, string version, string account) =>
        T($"Connected to {device} (ShelfWatch {version}) · {account}", $"Verbunden mit {device} (ShelfWatch {version}) · {account}");
    public static string PendingSync(int n) => T($"{n} progress update(s) waiting to sync", $"{n} Fortschritt(e) warten auf Sync");
    public static string SyncOk(string time) => T($"Sync ok {time}", $"Sync ok {time}");
    public static string NoSyncYet => T("not synced yet", "noch kein Sync");
    public static string Storage(string used, string free, string sync) =>
        T($"Used {used} · free {free} · {sync}", $"Belegt {used} · frei {free} · {sync}");
    public static string Playing => T("▶ Playing", "▶ Spielt");
    public static string Paused => T("❚❚ Paused", "❚❚ Pausiert");
    public static string NoPlayback => T("Nothing playing", "Keine Wiedergabe");
    public static string Error(string msg) => T("Error: ", "Fehler: ") + msg;

    // --- Tab: on the watch ---
    public static string TabOnWatch => T("On the watch", "Auf der Uhr");
    public static string RemoveSelected => T("Remove / cancel selected", "Ausgewählte entfernen / abbrechen");
    public static string Refresh => T("Refresh", "Aktualisieren");
    public static string ColTitle => T("Title", "Titel");
    public static string ColAuthor => T("Author", "Autor");
    public static string ColDuration => T("Duration", "Dauer");
    public static string ColSize => T("Size", "Größe");
    public static string ColStatus => "Status";
    public static string ColProgress => T("Progress", "Fortschritt");
    public static string ColOnWatch => T("On watch", "Auf der Uhr");
    public static string NotConnected => T("Not connected to the watch.", "Nicht mit der Uhr verbunden.");
    public static string ConnectFirst => T("Connect to the watch first.", "Zuerst mit der Uhr verbinden.");
    public static string ConfirmRemove(string names) => T($"Remove from the watch?\n\n{names}", $"Von der Uhr entfernen?\n\n{names}");
    public static string Removed(int n) => T($"{n} audiobook(s) removed.", $"{n} Hörbuch/Hörbücher entfernt.");

    // --- Download states ---
    public static string StComplete => T("On the watch ✓", "Auf der Uhr ✓");
    public static string StQueued => T("Queued", "Wartet");
    public static string StRunning => T("Downloading…", "Lädt…");
    public static string StWaiting => T("Waiting for network", "Warte auf Netz");
    public static string StFailed(string? err) => T("Error: ", "Fehler: ") + (err ?? T("unknown", "unbekannt"));
    public static string StIncomplete => T("Incomplete", "Unvollständig");

    // --- Tab: library & account ---
    public static string TabLibrary => T("Library & account", "Bibliothek & Konto");
    public static string AbsAccount => T("Audiobookshelf account", "Audiobookshelf-Konto");
    public static string Server => "Server:";
    public static string User => T("Username:", "Benutzer:");
    public static string Password => T("Password:", "Passwort:");
    public static string SignIn => T("Sign in", "Anmelden");
    public static string SignInOnWatch => T("Sign in on watch", "Auf Uhr anmelden");
    public static string CredentialsHint => T(
        "Credentials are stored encrypted (Windows DPAPI).",
        "Zugangsdaten werden verschlüsselt (Windows-DPAPI) gespeichert.");
    public static string Library => T("Library:", "Bibliothek:");
    public static string Search => T("Search:", "Suche:");
    public static string SearchButton => T("Search", "Suchen");
    public static string DownloadToWatch => T("Download to watch", "Auf Uhr herunterladen");
    public static string LoadMore => T("Load more", "Mehr laden");
    public static string SigningIn => T("Signing in to Audiobookshelf…", "Melde bei Audiobookshelf an…");
    public static string SignedInAbs(string user, string server) => T($"Signed in as {user} @ {server}", $"Angemeldet als {user} @ {server}");
    public static string LibrariesFound(int n) => T($"{n} audiobook librar{(n == 1 ? "y" : "ies")} found.", $"{n} Hörbuch-Bibliothek(en) gefunden.");
    public static string EnterServerUser => T("Enter server and username.", "Server und Benutzer eintragen.");
    public static string WatchSigningIn => T("Watch is signing in to Audiobookshelf…", "Uhr meldet sich bei Audiobookshelf an…");
    public static string WatchSignedIn => T("The watch is signed in.", "Uhr ist angemeldet.");
    public static string LoadingLibrary => T("Loading library…", "Lade Bibliothek…");
    public static string NOfTotal(int n, int total) => T($"{n} of {total}", $"{n} von {total}");
    public static string Hits(int n) => T($"{n} results", $"{n} Treffer");
    public static string SignInFirst => T("Sign in to Audiobookshelf first.", "Zuerst bei Audiobookshelf anmelden.");
    public static string SelectBooks => T("Please select audiobook(s).", "Bitte Hörbuch(er) auswählen.");
    public static string DownloadsSent(int n) => T(
        $"{n} download(s) sent to the watch – see progress in the “On the watch” tab.",
        $"{n} Download(s) an die Uhr gesendet – Fortschritt im Tab „Auf der Uhr“.");
    public static string Unknown => T("Unknown", "Unbekannt");

    // --- Client errors ---
    public static string WatchNoResponse => T(
        "The watch does not respond – is “PC connection” switched on?",
        "Uhr antwortet nicht – ist „PC-Verbindung“ auf der Uhr aktiv?");
    public static string WatchUnreachable(string msg) => T("Watch unreachable: ", "Uhr nicht erreichbar: ") + msg;
    public static string ServerUnreachable(string? msg) => T("Server unreachable: ", "Server nicht erreichbar: ") + msg;
    public static string BadCredentials => T("Wrong username or password", "Benutzername oder Passwort falsch");
    public static string SignInFailed(int code) => T($"Sign-in failed: HTTP {code}", $"Anmeldung fehlgeschlagen: HTTP {code}");
    public static string NoToken => T("No token received", "Kein Token erhalten");
    public static string NotSignedInAbs => T("Not signed in", "Nicht angemeldet");

    // --- Tab: local audio files ---
    public static string TabLocal => T("Local audio", "Lokale Audio");
    public static string AddFiles => T("Add files…", "Dateien hinzufügen…");
    public static string RemoveSelectedLocal => T("Remove selected", "Ausgewählte entfernen");
    public static string LocalHint => T(
        "MP3 and Opus files are copied to the watch and play with the watch's media controls (ShelfWatch → Local audio files). They are separate from the audiobook library.",
        "MP3- und Opus-Dateien werden auf die Uhr kopiert und lassen sich über die Medien-Steuerung der Uhr abspielen (ShelfWatch → Lokale Audiodateien). Sie sind unabhängig von der Hörbuch-Bibliothek.");
    public static string ColName => T("Name", "Name");
    public static string ColType => T("Type", "Typ");
    public static string AudioFiles => T("Audio files", "Audiodateien");
    public static string ChooseFiles => T("Choose audio files", "Audiodateien auswählen");
    public static string Uploading(string name, int i, int n) =>
        T($"Uploading {name} ({i}/{n})…", $"Übertrage {name} ({i}/{n})…");
    public static string UploadDone(int ok, int n) =>
        T($"{ok} of {n} file(s) uploaded.", $"{ok} von {n} Datei(en) übertragen.");
    public static string UploadErrors => T("Some files could not be uploaded:", "Einige Dateien konnten nicht übertragen werden:");
    public static string ConfirmRemoveLocal(string names) =>
        T($"Remove from the watch?\n\n{names}", $"Von der Uhr entfernen?\n\n{names}");
    public static string RemovedLocal(int n) => T($"{n} file(s) removed.", $"{n} Datei(en) entfernt.");
    public static string LocalCount(int n, string size) => T($"{n} file(s) · {size}", $"{n} Datei(en) · {size}");
}
