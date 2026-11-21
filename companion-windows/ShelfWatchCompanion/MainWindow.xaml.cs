using System.Collections.ObjectModel;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Threading;

namespace ShelfWatchCompanion;

public partial class MainWindow : Window
{
    private readonly Settings _settings = Settings.Load();
    private readonly AbsClient _abs = new();
    private WatchClient? _watch;
    private readonly ObservableCollection<DownloadRow> _downloads = new();
    private readonly ObservableCollection<BookRow> _books = new();
    /// <summary>Titel/Autor aus der Bibliothek – für Downloads, deren Metadaten die Uhr noch nicht kennt.</summary>
    private readonly Dictionary<string, (string title, string author)> _titleCache = new();
    private Dictionary<string, WatchDownload> _watchState = new();
    private readonly DispatcherTimer _timer = new() { Interval = TimeSpan.FromSeconds(2) };
    private bool _polling;
    private string? _libraryId;
    private int _libPage, _libTotal;

    public MainWindow()
    {
        InitializeComponent();
        DownloadsGrid.ItemsSource = _downloads;
        BooksGrid.ItemsSource = _books;

        WatchIp.Text = _settings.WatchIp;
        WatchPort.Text = _settings.WatchPort.ToString();
        WatchPin.Password = _settings.WatchPin;
        AbsServer.Text = _settings.AbsServer;
        AbsUser.Text = _settings.AbsUser;
        AbsPassword.Password = _settings.AbsPassword;

        _timer.Tick += async (_, _) => await PollAsync();
        Loaded += async (_, _) =>
        {
            if (_settings.AbsServer != "" && _settings.AbsUser != "" && _settings.AbsPassword != "")
                await Guard(AbsLoginAsync);
            if (_settings.WatchIp != "" && _settings.WatchPin != "")
                await Guard(ConnectAsync);
        };
        Closing += (_, _) => SaveSettings();
    }

    // --- Hilfen --------------------------------------------------------------

    private async Task Guard(Func<Task> action)
    {
        try
        {
            await action();
        }
        catch (Exception e) when (e is WatchException or AbsException or FormatException)
        {
            StatusText.Text = e.Message;
        }
        catch (Exception e)
        {
            StatusText.Text = "Fehler: " + e.Message;
        }
    }

    private void SaveSettings()
    {
        _settings.WatchIp = WatchIp.Text.Trim();
        _settings.WatchPort = int.TryParse(WatchPort.Text, out var p) ? p : 8765;
        _settings.WatchPin = WatchPin.Password.Trim();
        _settings.AbsServer = AbsServer.Text.Trim();
        _settings.AbsUser = AbsUser.Text.Trim();
        _settings.AbsPassword = AbsPassword.Password;
        try { _settings.Save(); } catch { /* nicht kritisch */ }
    }

    // --- Uhr -----------------------------------------------------------------

    private async void Discover_Click(object sender, RoutedEventArgs e) => await Guard(async () =>
    {
        DiscoverBtn.IsEnabled = false;
        StatusText.Text = "Suche Uhr im Netzwerk…";
        try
        {
            var found = await WatchClient.DiscoverAsync(TimeSpan.FromSeconds(3));
            if (found.Count == 0)
            {
                StatusText.Text = "Keine Uhr gefunden. Ist „PC-Verbindung“ auf der Uhr aktiv? Sonst IP manuell eintragen.";
                return;
            }
            var w = found[0];
            WatchIp.Text = w.Ip;
            WatchPort.Text = w.Port.ToString();
            StatusText.Text = found.Count == 1
                ? $"Gefunden: {w}. Code von der Uhr eingeben und „Verbinden“."
                : $"{found.Count} Uhren gefunden, verwende {w}.";
            if (WatchPin.Password != "") await ConnectAsync();
            else WatchPin.Focus();
        }
        finally
        {
            DiscoverBtn.IsEnabled = true;
        }
    });

    private async void Connect_Click(object sender, RoutedEventArgs e) => await Guard(ConnectAsync);

    private async Task ConnectAsync()
    {
        var ip = WatchIp.Text.Trim();
        if (ip == "") throw new WatchException("Bitte IP der Uhr eintragen oder „Uhr suchen“.");
        if (!int.TryParse(WatchPort.Text, out var port)) port = 8765;
        var pin = WatchPin.Password.Trim();
        if (pin == "") throw new WatchException("Bitte den Code von der Uhr eingeben.");

        StatusText.Text = "Verbinde…";
        var client = new WatchClient(ip, port, pin);
        var st = await client.StatusAsync();
        _watch = client;
        SaveSettings();
        ShowStatus(st);
        StatusText.Text = "Mit der Uhr verbunden.";
        _timer.Start();
        await PollAsync();
    }

    private async Task PollAsync()
    {
        if (_watch == null || _polling) return;
        _polling = true;
        try
        {
            var st = await _watch.StatusAsync();
            var dl = await _watch.DownloadsAsync();
            ShowStatus(st);
            UpdateDownloads(dl);
        }
        catch (WatchException e)
        {
            WatchStatusText.Text = "Verbindung unterbrochen: " + e.Message;
        }
        finally
        {
            _polling = false;
        }
    }

    private void ShowStatus(WatchStatus st)
    {
        var account = st.LoggedIn
            ? $"angemeldet als {st.Username} @ {st.Server}" + (st.AuthExpired ? " – Anmeldung abgelaufen!" : "")
            : "nicht bei Audiobookshelf angemeldet → Tab „Bibliothek & Konto“ → „Auf Uhr anmelden“";
        WatchStatusText.Text = $"Verbunden mit {st.Device} (ShelfWatch {st.AppVersion}) · {account}";

        var sync = st.PendingSync > 0
            ? $"{st.PendingSync} Fortschritt(e) warten auf Sync"
            : st.LastSyncAt > 0
                ? "Sync ok " + DateTimeOffset.FromUnixTimeMilliseconds(st.LastSyncAt).LocalDateTime.ToString("HH:mm:ss")
                : "noch kein Sync";
        StorageText.Text = $"Belegt {Fmt.Bytes(st.UsedBytes)} · frei {Fmt.Bytes(st.FreeBytes)} · {sync}";

        NowPlayingText.Text = st.NowPlaying is { } np
            ? $"{(np.Playing ? "▶ Spielt" : "❚❚ Pausiert")}: {np.Title}  ({Fmt.Clock(np.PositionSec)} / {Fmt.Clock(np.DurationSec)})"
            : "Keine Wiedergabe";
    }

    private void UpdateDownloads(List<WatchDownload> list)
    {
        _watchState = list.ToDictionary(d => d.ItemId);
        var byId = _downloads.ToDictionary(r => r.ItemId);

        foreach (var d in list)
        {
            if (!byId.TryGetValue(d.ItemId, out var row))
            {
                row = new DownloadRow { ItemId = d.ItemId };
                _downloads.Add(row);
            }
            _titleCache.TryGetValue(d.ItemId, out var cached);
            row.Title = d.Title != "" ? d.Title : cached.title ?? d.ItemId;
            row.Author = d.Author != "" ? d.Author : cached.author ?? "";
            row.Duration = Fmt.Duration(d.DurationSec);
            row.Size = Fmt.Bytes(d.SizeBytes);
            row.StatusText = Fmt.Status(d);
            row.Progress = Math.Round(d.Progress * 100, 1);
            row.ProgressText = $"{d.Progress * 100:0} %";
        }
        foreach (var gone in _downloads.Where(r => !_watchState.ContainsKey(r.ItemId)).ToList())
            _downloads.Remove(gone);

        foreach (var b in _books) b.OnWatch = OnWatchText(b.Id);
    }

    private string OnWatchText(string id) =>
        _watchState.TryGetValue(id, out var d)
            ? d.Status == "complete" ? "✓" : $"{Fmt.Status(d)} {d.Progress * 100:0} %"
            : "";

    private async void Refresh_Click(object sender, RoutedEventArgs e) => await Guard(PollAsync);

    private async void RemoveDownload_Click(object sender, RoutedEventArgs e) => await Guard(async () =>
    {
        if (_watch == null) throw new WatchException("Nicht mit der Uhr verbunden.");
        var rows = DownloadsGrid.SelectedItems.Cast<DownloadRow>().ToList();
        if (rows.Count == 0) return;
        var names = string.Join("\n", rows.Select(r => "• " + r.Title));
        if (MessageBox.Show(this, $"Von der Uhr entfernen?\n\n{names}", "ShelfWatch",
                MessageBoxButton.YesNo, MessageBoxImage.Question) != MessageBoxResult.Yes) return;
        foreach (var r in rows) await _watch.RemoveAsync(r.ItemId);
        StatusText.Text = $"{rows.Count} Hörbuch/Hörbücher entfernt.";
        await PollAsync();
    });

    // --- Audiobookshelf ------------------------------------------------------

    private async void AbsLogin_Click(object sender, RoutedEventArgs e) => await Guard(AbsLoginAsync);

    private async Task AbsLoginAsync()
    {
        StatusText.Text = "Melde bei Audiobookshelf an…";
        var used = await _abs.LoginAsync(AbsServer.Text, AbsUser.Text.Trim(), AbsPassword.Password);
        AbsServer.Text = used;
        SaveSettings();
        AbsStatusText.Text = $"Angemeldet als {AbsUser.Text.Trim()} @ {used}";
        var libs = await _abs.LibrariesAsync();
        LibraryBox.ItemsSource = libs;
        if (libs.Count > 0) LibraryBox.SelectedIndex = 0;
        StatusText.Text = $"{libs.Count} Hörbuch-Bibliothek(en) gefunden.";
    }

    private async void WatchLogin_Click(object sender, RoutedEventArgs e) => await Guard(async () =>
    {
        if (_watch == null) throw new WatchException("Zuerst mit der Uhr verbinden.");
        var server = _abs.IsLoggedIn ? _abs.Server : AbsServer.Text.Trim();
        if (server == "" || AbsUser.Text.Trim() == "") throw new AbsException("Server und Benutzer eintragen.");
        StatusText.Text = "Uhr meldet sich bei Audiobookshelf an…";
        await _watch.LoginAsync(server, AbsUser.Text.Trim(), AbsPassword.Password);
        SaveSettings();
        StatusText.Text = "Uhr ist angemeldet.";
        await PollAsync();
    });

    private async void Library_Changed(object sender, SelectionChangedEventArgs e) => await Guard(async () =>
    {
        if (LibraryBox.SelectedItem is not AbsLibrary lib) return;
        _libraryId = lib.Id;
        _libPage = 0;
        _books.Clear();
        SearchBox.Text = "";
        await LoadPageAsync();
    });

    private async Task LoadPageAsync()
    {
        if (_libraryId == null) return;
        StatusText.Text = "Lade Bibliothek…";
        var (items, total) = await _abs.ItemsAsync(_libraryId, _libPage);
        _libPage++;
        _libTotal = total;
        AddBooks(items);
        LibCountText.Text = $"{_books.Count} von {total}";
        MoreBtn.IsEnabled = _books.Count < total;
        StatusText.Text = "";
    }

    private void AddBooks(IEnumerable<AbsItem> items)
    {
        foreach (var i in items.Where(i => i.MediaType == "book"))
        {
            var title = i.Media?.Metadata.Title ?? "Unbekannt";
            var author = i.Media?.Metadata.AuthorName ?? "";
            _titleCache[i.Id] = (title, author);
            _books.Add(new BookRow
            {
                Id = i.Id,
                Title = title,
                Author = author,
                Duration = Fmt.Duration(i.Media?.Duration ?? 0),
                Size = Fmt.Bytes(i.Media?.Size ?? 0),
                OnWatch = OnWatchText(i.Id),
            });
        }
    }

    private async void More_Click(object sender, RoutedEventArgs e) => await Guard(LoadPageAsync);

    private async void Search_Click(object sender, RoutedEventArgs e) => await Guard(SearchAsync);

    private async void Search_KeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Enter) await Guard(SearchAsync);
    }

    private async Task SearchAsync()
    {
        if (_libraryId == null) throw new AbsException("Zuerst bei Audiobookshelf anmelden.");
        var q = SearchBox.Text.Trim();
        _books.Clear();
        if (q == "")
        {
            _libPage = 0;
            await LoadPageAsync();
            return;
        }
        var results = await _abs.SearchAsync(_libraryId, q);
        AddBooks(results);
        LibCountText.Text = $"{_books.Count} Treffer";
        MoreBtn.IsEnabled = false;
    }

    private async void DownloadToWatch_Click(object sender, RoutedEventArgs e) => await Guard(DownloadSelectedAsync);

    private async void Books_DoubleClick(object sender, MouseButtonEventArgs e) => await Guard(DownloadSelectedAsync);

    private async Task DownloadSelectedAsync()
    {
        if (_watch == null) throw new WatchException("Zuerst mit der Uhr verbinden.");
        var rows = BooksGrid.SelectedItems.Cast<BookRow>().ToList();
        if (rows.Count == 0)
        {
            StatusText.Text = "Bitte Hörbuch(er) auswählen.";
            return;
        }
        foreach (var r in rows) await _watch.EnqueueAsync(r.Id);
        StatusText.Text = $"{rows.Count} Download(s) an die Uhr gesendet – Fortschritt im Tab „Auf der Uhr“.";
        await PollAsync();
    }
}
