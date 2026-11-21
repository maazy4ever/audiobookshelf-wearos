using System.Net;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace ShelfWatchCompanion;

public class AbsLibrary
{
    public string Id { get; set; } = "";
    public string Name { get; set; } = "";
    public string MediaType { get; set; } = "book";
    public override string ToString() => Name;
}

public class AbsMetadata
{
    public string? Title { get; set; }
    public string? AuthorName { get; set; }
}

public class AbsMedia
{
    public AbsMetadata Metadata { get; set; } = new();
    public double Duration { get; set; }
    public long Size { get; set; }
}

public class AbsItem
{
    public string Id { get; set; } = "";
    public string MediaType { get; set; } = "book";
    public AbsMedia? Media { get; set; }
}

public class AbsException(string message) : Exception(message);

/// <summary>Direkter Zugriff auf Audiobookshelf vom PC aus (Bibliothek durchsuchen).</summary>
public class AbsClient
{
    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web)
    {
        NumberHandling = JsonNumberHandling.AllowReadingFromString,
    };
    private readonly HttpClient _http = new() { Timeout = TimeSpan.FromSeconds(20) };
    private string _user = "", _password = "";

    public string Server { get; private set; } = "";
    public bool IsLoggedIn => _http.DefaultRequestHeaders.Authorization != null;

    /// <summary>Meldet an; ergänzt bei fehlendem Schema https bzw. http. Liefert die verwendete URL.</summary>
    public async Task<string> LoginAsync(string serverInput, string user, string password)
    {
        var s = serverInput.Trim().TrimEnd('/');
        var candidates = s.StartsWith("http://") || s.StartsWith("https://")
            ? new[] { s }
            : new[] { $"https://{s}", $"http://{s}" };

        Exception? last = null;
        foreach (var c in candidates)
        {
            try
            {
                await DoLogin(c, user, password);
                Server = c;
                _user = user;
                _password = password;
                return c;
            }
            catch (AbsException) { throw; }
            catch (Exception e) { last = e; }
        }
        throw new AbsException($"Server nicht erreichbar: {last?.Message}");
    }

    private async Task DoLogin(string server, string user, string password)
    {
        using var req = new HttpRequestMessage(HttpMethod.Post, $"{server}/login")
        {
            Content = JsonContent.Create(new { username = user, password }),
        };
        req.Headers.Add("x-return-tokens", "true");
        using var resp = await _http.SendAsync(req);
        if (resp.StatusCode == HttpStatusCode.Unauthorized) throw new AbsException("Benutzername oder Passwort falsch");
        if (!resp.IsSuccessStatusCode) throw new AbsException($"Anmeldung fehlgeschlagen: HTTP {(int)resp.StatusCode}");
        using var doc = JsonDocument.Parse(await resp.Content.ReadAsStringAsync());
        var u = doc.RootElement.GetProperty("user");
        string? token = null;
        if (u.TryGetProperty("accessToken", out var at) && at.ValueKind == JsonValueKind.String) token = at.GetString();
        if (token == null && u.TryGetProperty("token", out var t) && t.ValueKind == JsonValueKind.String) token = t.GetString();
        if (token == null) throw new AbsException("Kein Token erhalten");
        _http.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", token);
    }

    private async Task<JsonDocument> Get(string path)
    {
        if (!IsLoggedIn) throw new AbsException("Nicht angemeldet");
        var resp = await _http.GetAsync(Server + path);
        if (resp.StatusCode == HttpStatusCode.Unauthorized && _password != "")
        {
            // Access-Token abgelaufen → neu anmelden
            resp.Dispose();
            await DoLogin(Server, _user, _password);
            resp = await _http.GetAsync(Server + path);
        }
        using (resp)
        {
            if (!resp.IsSuccessStatusCode) throw new AbsException($"HTTP {(int)resp.StatusCode} bei {path}");
            return JsonDocument.Parse(await resp.Content.ReadAsStringAsync());
        }
    }

    public async Task<List<AbsLibrary>> LibrariesAsync()
    {
        using var doc = await Get("/api/libraries");
        return doc.RootElement.GetProperty("libraries").Deserialize<List<AbsLibrary>>(Json)!
            .Where(l => l.MediaType == "book").ToList();
    }

    public async Task<(List<AbsItem> items, int total)> ItemsAsync(string libraryId, int page, int limit = 50)
    {
        using var doc = await Get($"/api/libraries/{libraryId}/items?limit={limit}&page={page}&sort=media.metadata.title&minified=1");
        var items = doc.RootElement.GetProperty("results").Deserialize<List<AbsItem>>(Json)!;
        var total = doc.RootElement.TryGetProperty("total", out var t) ? t.GetInt32() : items.Count;
        return (items, total);
    }

    public async Task<List<AbsItem>> SearchAsync(string libraryId, string query)
    {
        using var doc = await Get($"/api/libraries/{libraryId}/search?q={Uri.EscapeDataString(query)}&limit=50");
        var result = new List<AbsItem>();
        if (doc.RootElement.TryGetProperty("book", out var books))
            foreach (var b in books.EnumerateArray())
                if (b.TryGetProperty("libraryItem", out var li))
                    result.Add(li.Deserialize<AbsItem>(Json)!);
        return result;
    }
}
