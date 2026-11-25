using System.IO;
using System.Net;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;

namespace ShelfWatchCompanion;

public record WatchHello(string App, string Device, string Version);
public record WatchNowPlaying(string ItemId, string Title, double PositionSec, double DurationSec, bool Playing);
public record WatchStatus(
    string Device, string AppVersion, bool LoggedIn, bool AuthExpired, string Server, string Username,
    long FreeBytes, long UsedBytes, int PendingSync, long LastSyncAt, WatchNowPlaying? NowPlaying);
public record WatchDownload(
    string ItemId, string Title, string Author, double DurationSec, long SizeBytes,
    string Status, double Progress, string? Error);
public record WatchResult(bool Ok, string? Error);
public record WatchLocalFile(string Name, long SizeBytes);
public record DiscoveredWatch(string App, string Device, string Ip, int Port)
{
    public override string ToString() => $"{Device} ({Ip})";
}

public class WatchException(string message) : Exception(message);

/// <summary>Spricht mit der Companion-API der ShelfWatch-App auf der Uhr (TCP 8765, UDP 8766).</summary>
public class WatchClient
{
    public const int DiscoveryPort = 8766;
    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web);
    private readonly HttpClient _http;
    private readonly HttpClient _upload;

    public WatchClient(string ip, int port, string pin)
    {
        var baseUri = new Uri($"http://{ip}:{port}/");
        _http = new HttpClient { BaseAddress = baseUri, Timeout = TimeSpan.FromSeconds(10) };
        _http.DefaultRequestHeaders.Add("X-ShelfWatch-Pin", pin);
        // Uploads können lange dauern – eigener Client ohne Zeitlimit
        _upload = new HttpClient { BaseAddress = baseUri, Timeout = Timeout.InfiniteTimeSpan };
        _upload.DefaultRequestHeaders.Add("X-ShelfWatch-Pin", pin);
    }

    public Task<WatchHello> HelloAsync() => Send<WatchHello>(HttpMethod.Get, "api/hello");
    public Task<WatchStatus> StatusAsync() => Send<WatchStatus>(HttpMethod.Get, "api/status");
    public Task<List<WatchDownload>> DownloadsAsync() => Send<List<WatchDownload>>(HttpMethod.Get, "api/downloads");
    public Task EnqueueAsync(string itemId) => Send<WatchResult>(HttpMethod.Post, "api/downloads", new { itemId });
    public Task RemoveAsync(string itemId) => Send<WatchResult>(HttpMethod.Delete, $"api/downloads/{Uri.EscapeDataString(itemId)}");
    public Task LoginAsync(string server, string username, string password) =>
        Send<WatchResult>(HttpMethod.Post, "api/login", new { server, username, password });
    public Task LogoutAsync() => Send<WatchResult>(HttpMethod.Post, "api/logout");
    public Task<List<WatchLocalFile>> LocalFilesAsync() => Send<List<WatchLocalFile>>(HttpMethod.Get, "api/local");
    public Task DeleteLocalAsync(string name) =>
        Send<WatchResult>(HttpMethod.Delete, $"api/local/{Uri.EscapeDataString(name)}");

    /// <summary>Lädt eine MP3/Opus-Datei auf die Uhr. Rohdaten mit Content-Length, damit die Uhr sie direkt speichern kann.</summary>
    public async Task UploadLocalAsync(string filePath, CancellationToken ct = default)
    {
        var name = Path.GetFileName(filePath);
        await using var fs = File.OpenRead(filePath);
        using var content = new StreamContent(fs);
        content.Headers.ContentLength = fs.Length; // explizit, sonst sendet HttpClient "chunked"
        content.Headers.ContentType = new MediaTypeHeaderValue("application/octet-stream");
        using var req = new HttpRequestMessage(HttpMethod.Post, $"api/local?name={Uri.EscapeDataString(name)}")
        {
            Content = content,
        };
        HttpResponseMessage resp;
        try
        {
            resp = await _upload.SendAsync(req, ct);
        }
        catch (HttpRequestException e)
        {
            throw new WatchException(L.WatchUnreachable(e.Message));
        }
        catch (IOException e)
        {
            throw new WatchException(L.WatchUnreachable(e.Message));
        }
        using (resp)
        {
            var text = await resp.Content.ReadAsStringAsync(ct);
            if (!resp.IsSuccessStatusCode)
            {
                string? err = null;
                try { err = JsonSerializer.Deserialize<WatchResult>(text, Json)?.Error; } catch { }
                throw new WatchException(err ?? $"HTTP {(int)resp.StatusCode}");
            }
        }
    }

    private async Task<T> Send<T>(HttpMethod method, string path, object? body = null)
    {
        using var req = new HttpRequestMessage(method, path);
        // StringContent statt JsonContent: sendet Content-Length (JsonContent würde "chunked" senden)
        if (body != null)
            req.Content = new StringContent(JsonSerializer.Serialize(body, Json), Encoding.UTF8, "application/json");
        HttpResponseMessage resp;
        try
        {
            resp = await _http.SendAsync(req);
        }
        catch (TaskCanceledException)
        {
            throw new WatchException(L.WatchNoResponse);
        }
        catch (HttpRequestException e)
        {
            throw new WatchException(L.WatchUnreachable(e.Message));
        }
        using (resp)
        {
            var text = await resp.Content.ReadAsStringAsync();
            if (!resp.IsSuccessStatusCode)
            {
                string? err = null;
                try { err = JsonSerializer.Deserialize<WatchResult>(text, Json)?.Error; } catch { }
                throw new WatchException(err ?? $"HTTP {(int)resp.StatusCode}");
            }
            return JsonSerializer.Deserialize<T>(text, Json)!;
        }
    }

    /// <summary>Sucht Uhren per UDP-Broadcast an alle lokalen IPv4-Netze.</summary>
    public static async Task<List<DiscoveredWatch>> DiscoverAsync(TimeSpan timeout)
    {
        var found = new Dictionary<string, DiscoveredWatch>();
        using var udp = new UdpClient(AddressFamily.InterNetwork) { EnableBroadcast = true };
        udp.Client.Bind(new IPEndPoint(IPAddress.Any, 0));
        var payload = Encoding.UTF8.GetBytes("SHELFWATCH_DISCOVER");

        var targets = new HashSet<IPAddress> { IPAddress.Broadcast };
        foreach (var nic in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (nic.OperationalStatus != OperationalStatus.Up) continue;
            foreach (var ua in nic.GetIPProperties().UnicastAddresses)
            {
                if (ua.Address.AddressFamily != AddressFamily.InterNetwork || ua.IPv4Mask == null) continue;
                var ip = ua.Address.GetAddressBytes();
                var mask = ua.IPv4Mask.GetAddressBytes();
                var bc = new byte[4];
                for (var i = 0; i < 4; i++) bc[i] = (byte)(ip[i] | ~mask[i]);
                targets.Add(new IPAddress(bc));
            }
        }

        // mehrfach senden – WLAN-Broadcasts gehen gelegentlich verloren
        for (var round = 0; round < 3; round++)
            foreach (var t in targets)
                try { await udp.SendAsync(payload, payload.Length, new IPEndPoint(t, DiscoveryPort)); } catch { }

        using var cts = new CancellationTokenSource(timeout);
        try
        {
            while (!cts.IsCancellationRequested)
            {
                var result = await udp.ReceiveAsync(cts.Token);
                try
                {
                    var w = JsonSerializer.Deserialize<DiscoveredWatch>(Encoding.UTF8.GetString(result.Buffer), Json);
                    if (w != null && w.App == "ShelfWatch") found[w.Ip] = w;
                }
                catch { }
            }
        }
        catch (OperationCanceledException) { }
        return found.Values.ToList();
    }
}
