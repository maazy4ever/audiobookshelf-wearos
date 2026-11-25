using System.Collections.ObjectModel;
using System.IO;
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
    private readonly ObservableCollection<LocalRow> _local = new();
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
        LocalGrid.ItemsSource = _local;

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
            StatusText.Text = L.Error(e.Message);
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
        StatusText.Text = L.Searching;
        try
        {
            var found = await WatchClient.DiscoverAsync(TimeSpan.FromSeconds(3));
            if (found.Count == 0)
            {
                StatusText.Text = L.NoWatchFound;
                return;
            }
            var w = found[0];
            WatchIp.Text = w.Ip;
            WatchPort.Text = w.Port.ToString();
            StatusText.Text = found.Count == 1
                ? L.FoundOne(w.ToString())
                : L.FoundMany(found.Count, w.ToString());
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
        if (ip == "") throw new WatchException(L.EnterIp);
        if (!int.TryParse(WatchPort.Text, out var port)) port = 8765;
        var pin = WatchPin.Password.Trim();
        if (pin == "") throw new WatchException(L.EnterCode);

        StatusText.Text = L.ConnectingWatch;
        var client = new WatchClient(ip, port, pin);
        var st = await client.StatusAsync();
        _watch = client;
        SaveSettings();
        ShowStatus(st);
        StatusText.Text = L.ConnectedWatch;
        _timer.Start();
        await PollAsync();
        try { await RefreshLocalAsync(); }
        catch (WatchException) { /* ältere Uhr-Version ohne Dateiliste – ignorieren */ }
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
            WatchStatusText.Text = L.ConnectionLost(e.Message);
        }
        finally
        {
            _polling = false;
        }
    }

    private void ShowStatus(WatchStatus st)
    {
        var account = st.LoggedIn
            ? L.SignedInAs(st.Username, st.Server) + (st.AuthExpired ? L.SignInExpired : "")
            : L.NotSignedIn;
        WatchStatusText.Text = L.ConnectedTo(st.Device, st.AppVersion, account);

        var sync = st.PendingSync > 0
            ? L.PendingSync(st.PendingSync)
            : st.LastSyncAt > 0
                ? L.SyncOk(DateTimeOffset.FromUnixTimeMilliseconds(st.LastSyncAt).LocalDateTime.ToString("HH:mm:ss"))
                : L.NoSyncYet;
        StorageText.Text = L.Storage(Fmt.Bytes(st.UsedBytes), Fmt.Bytes(st.FreeBytes), sync);

        NowPlayingText.Text = st.NowPlaying is { } np
            ? $"{(np.Playing ? L.Playing : L.Paused)}: {np.Title}  ({Fmt.Clock(np.PositionSec)} / {Fmt.Clock(np.DurationSec)})"
            : L.NoPlayback;
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
        if (_watch == null) throw new WatchException(L.NotConnected);
        var rows = DownloadsGrid.SelectedItems.Cast<DownloadRow>().ToList();
        if (rows.Count == 0) return;
        var names = string.Join("\n", rows.Select(r => "• " + r.Title));
        if (MessageBox.Show(this, L.ConfirmRemove(names), "ShelfWatch",
                MessageBoxButton.YesNo, MessageBoxImage.Question) != MessageBoxResult.Yes) return;
        foreach (var r in rows) await _watch.RemoveAsync(r.ItemId);
        StatusText.Text = L.Removed(rows.Count);
        await PollAsync();
    });

    // --- Lokale Audiodateien (MP3 / Opus) ------------------------------------

    private async Task RefreshLocalAsync()
    {
        if (_watch == null) return;
        var files = await _watch.LocalFilesAsync();
        _local.Clear();
        foreach (var f in files)
            _local.Add(new LocalRow(f.Name, Path.GetExtension(f.Name).TrimStart('.').ToUpperInvariant(), Fmt.Bytes(f.SizeBytes)));
        LocalInfoText.Text = L.LocalCount(files.Count, Fmt.Bytes(files.Sum(f => f.SizeBytes)));
    }

    private async void RefreshLocal_Click(object sender, RoutedEventArgs e) => await Guard(RefreshLocalAsync);

    private async void AddLocal_Click(object sender, RoutedEventArgs e) => await Guard(async () =>
    {
        if (_watch == null) throw new WatchException(L.ConnectFirst);
        var dlg = new Microsoft.Win32.OpenFileDialog
        {
            Multiselect = true,
            Title = L.ChooseFiles,
            Filter = $"{L.AudioFiles} (*.mp3;*.opus)|*.mp3;*.opus",
        };
        if (dlg.ShowDialog(this) != true) return;

        var files = dlg.FileNames;
        var uploaded = 0;
        var errors = new List<string>();
        for (var i = 0; i < files.Length; i++)
        {
            var name = Path.GetFileName(files[i]);
            StatusText.Text = L.Uploading(name, i + 1, files.Length);
            try
            {
                await _watch.UploadLocalAsync(files[i]);
                uploaded++;
            }
            catch (Exception ex) when (ex is WatchException or IOException)
            {
                errors.Add($"{name}: {ex.Message}");
            }
        }
        StatusText.Text = L.UploadDone(uploaded, files.Length);
        if (errors.Count > 0)
            MessageBox.Show(this, L.UploadErrors + "\n\n" + string.Join("\n", errors), "ShelfWatch",
                MessageBoxButton.OK, MessageBoxImage.Warning);
        await RefreshLocalAsync();
    });

    private async void RemoveLocal_Click(object sender, RoutedEventArgs e) => await Guard(async () =>
    {
        if (_watch == null) throw new WatchException(L.ConnectFirst);
        var rows = LocalGrid.SelectedItems.Cast<LocalRow>().ToList();
        if (rows.Count == 0) return;
        var names = string.Join("\n", rows.Select(r => "• " + r.Name));
        if (MessageBox.Show(this, L.ConfirmRemoveLocal(names), "ShelfWatch",
                MessageBoxButton.YesNo, MessageBoxImage.Question) != MessageBoxResult.Yes) return;
        foreach (var r in rows) await _watch.DeleteLocalAsync(r.Name);
        StatusText.Text = L.RemovedLocal(rows.Count);
        await RefreshLocalAsync();
    });

    // --- Audiobookshelf ------------------------------------------------------

    private async void AbsLogin_Click(object sender, RoutedEventArgs e) => await Guard(AbsLoginAsync);

    private async Task AbsLoginAsync()
    {
        StatusText.Text = L.SigningIn;
        var used = await _abs.LoginAsync(AbsServer.Text, AbsUser.Text.Trim(), AbsPassword.Password);
        AbsServer.Text = used;
        SaveSettings();
        AbsStatusText.Text = L.SignedInAbs(AbsUser.Text.Trim(), used);
        var libs = await _abs.LibrariesAsync();
        LibraryBox.ItemsSource = libs;
        if (libs.Count > 0) LibraryBox.SelectedIndex = 0;
        StatusText.Text = L.LibrariesFound(libs.Count);
    }

    private async void WatchLogin_Click(object sender, RoutedEventArgs e) => await Guard(async () =>
    {
        if (_watch == null) throw new WatchException(L.ConnectFirst);
        var server = _abs.IsLoggedIn ? _abs.Server : AbsServer.Text.Trim();
        if (server == "" || AbsUser.Text.Trim() == "") throw new AbsException(L.EnterServerUser);
        StatusText.Text = L.WatchSigningIn;
        await _watch.LoginAsync(server, AbsUser.Text.Trim(), AbsPassword.Password);
        SaveSettings();
        StatusText.Text = L.WatchSignedIn;
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
        StatusText.Text = L.LoadingLibrary;
        var (items, total) = await _abs.ItemsAsync(_libraryId, _libPage);
        _libPage++;
        _libTotal = total;
        AddBooks(items);
        LibCountText.Text = L.NOfTotal(_books.Count, total);
        MoreBtn.IsEnabled = _books.Count < total;
        StatusText.Text = "";
    }

    private void AddBooks(IEnumerable<AbsItem> items)
    {
        foreach (var i in items.Where(i => i.MediaType == "book"))
        {
            var title = i.Media?.Metadata.Title ?? L.Unknown;
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
        if (_libraryId == null) throw new AbsException(L.SignInFirst);
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
        LibCountText.Text = L.Hits(_books.Count);
        MoreBtn.IsEnabled = false;
    }

    private async void DownloadToWatch_Click(object sender, RoutedEventArgs e) => await Guard(DownloadSelectedAsync);

    private async void Books_DoubleClick(object sender, MouseButtonEventArgs e) => await Guard(DownloadSelectedAsync);

    private async Task DownloadSelectedAsync()
    {
        if (_watch == null) throw new WatchException(L.ConnectFirst);
        var rows = BooksGrid.SelectedItems.Cast<BookRow>().ToList();
        if (rows.Count == 0)
        {
            StatusText.Text = L.SelectBooks;
            return;
        }
        foreach (var r in rows) await _watch.EnqueueAsync(r.Id);
        StatusText.Text = L.DownloadsSent(rows.Count);
        await PollAsync();
    }
}
