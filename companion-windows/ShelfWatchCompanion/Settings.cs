using System.IO;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace ShelfWatchCompanion;

/// <summary>Einstellungen in %APPDATA%\ShelfWatchCompanion. Passwort und Code per DPAPI verschlüsselt.</summary>
public class Settings
{
    public string WatchIp { get; set; } = "";
    public int WatchPort { get; set; } = 8765;
    public string? WatchPinProtected { get; set; }
    public string AbsServer { get; set; } = "";
    public string AbsUser { get; set; } = "";
    public string? AbsPasswordProtected { get; set; }

    private static readonly string Dir = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "ShelfWatchCompanion");
    private static readonly string FilePath = Path.Combine(Dir, "settings.json");

    public string WatchPin
    {
        get => Unprotect(WatchPinProtected);
        set => WatchPinProtected = Protect(value);
    }

    public string AbsPassword
    {
        get => Unprotect(AbsPasswordProtected);
        set => AbsPasswordProtected = Protect(value);
    }

    public static Settings Load()
    {
        try
        {
            if (File.Exists(FilePath))
                return JsonSerializer.Deserialize<Settings>(File.ReadAllText(FilePath)) ?? new Settings();
        }
        catch { /* beschädigt → Standardwerte */ }
        return new Settings();
    }

    public void Save()
    {
        Directory.CreateDirectory(Dir);
        File.WriteAllText(FilePath, JsonSerializer.Serialize(this, new JsonSerializerOptions { WriteIndented = true }));
    }

    private static string? Protect(string value)
    {
        if (string.IsNullOrEmpty(value)) return null;
        var bytes = ProtectedData.Protect(Encoding.UTF8.GetBytes(value), null, DataProtectionScope.CurrentUser);
        return Convert.ToBase64String(bytes);
    }

    private static string Unprotect(string? value)
    {
        if (string.IsNullOrEmpty(value)) return "";
        try
        {
            var bytes = ProtectedData.Unprotect(Convert.FromBase64String(value), null, DataProtectionScope.CurrentUser);
            return Encoding.UTF8.GetString(bytes);
        }
        catch { return ""; }
    }
}
