using System.ComponentModel;
using System.Runtime.CompilerServices;

namespace ShelfWatchCompanion;

public abstract class Observable : INotifyPropertyChanged
{
    public event PropertyChangedEventHandler? PropertyChanged;

    protected void Set<T>(ref T field, T value, [CallerMemberName] string? name = null)
    {
        if (EqualityComparer<T>.Default.Equals(field, value)) return;
        field = value;
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
    }
}

public class DownloadRow : Observable
{
    private string _title = "", _author = "", _duration = "", _size = "", _statusText = "", _progressText = "";
    private double _progress;

    public required string ItemId { get; init; }
    public string Title { get => _title; set => Set(ref _title, value); }
    public string Author { get => _author; set => Set(ref _author, value); }
    public string Duration { get => _duration; set => Set(ref _duration, value); }
    public string Size { get => _size; set => Set(ref _size, value); }
    public string StatusText { get => _statusText; set => Set(ref _statusText, value); }
    public double Progress { get => _progress; set => Set(ref _progress, value); }
    public string ProgressText { get => _progressText; set => Set(ref _progressText, value); }
}

public class BookRow : Observable
{
    private string _onWatch = "";

    public required string Id { get; init; }
    public string Title { get; init; } = "";
    public string Author { get; init; } = "";
    public string Duration { get; init; } = "";
    public string Size { get; init; } = "";
    public string OnWatch { get => _onWatch; set => Set(ref _onWatch, value); }
}

public static class Fmt
{
    public static string Duration(double sec)
    {
        if (sec <= 0) return "";
        var totalMin = (long)(sec / 60);
        return totalMin >= 60 ? $"{totalMin / 60} h {totalMin % 60} min" : $"{totalMin} min";
    }

    public static string Bytes(long b) => b switch
    {
        <= 0 => "",
        >= 1L << 30 => $"{b / (double)(1L << 30):0.0} GB",
        >= 1L << 20 => $"{b / (double)(1L << 20):0} MB",
        _ => $"{b / 1024} KB",
    };

    public static string Clock(double sec)
    {
        var t = TimeSpan.FromSeconds(Math.Max(0, sec));
        return t.TotalHours >= 1 ? $"{(int)t.TotalHours}:{t.Minutes:00}:{t.Seconds:00}" : $"{t.Minutes}:{t.Seconds:00}";
    }

    public static string Status(WatchDownload d) => d.Status switch
    {
        "complete" => "Auf der Uhr ✓",
        "queued" => "Wartet",
        "running" => "Lädt…",
        "waiting" => "Warte auf Netz",
        "failed" => "Fehler: " + (d.Error ?? "unbekannt"),
        _ => "Unvollständig",
    };
}
