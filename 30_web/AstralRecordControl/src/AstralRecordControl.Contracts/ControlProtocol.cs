using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Text.RegularExpressions;

namespace AstralRecordControl.Contracts;

public static partial class ControlProtocol
{
    public const int Version = 1;
    public const int MaxMessageBytes = 1024 * 1024;
    public const int MaxInventoryBytes = 512 * 1024;
    public const string AgentHubPath = "/hubs/control-agent/v1";
    public const string UiHubPath = "/hubs/control-ui/v1";
    public const string RunnerPipeName = "AstralRecordControl.Runner.v1";
    public static string Hash(string text) => Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(text)));
    public static bool IsKey(string value) => KeyPattern().IsMatch(value);
    public static bool IsHash(string value) => HashPattern().IsMatch(value);
    public static bool IsRelativePath(string path)
    {
        if (string.IsNullOrEmpty(path) || path.Length > 512 || path.Contains('\\') || path.StartsWith('/') || path.Contains(':')) return false;
        return path.Split('/').All(part => part.Length > 0 && part is not "." and not ".."
            && !part.EndsWith('.') && !part.EndsWith(' ') && !part.Any(c => c < 32 || "<>\"|?*".Contains(c))
            && !DevicePattern().IsMatch(part));
    }

    [GeneratedRegex("^[a-z0-9][a-z0-9-]{0,47}$", RegexOptions.CultureInvariant)] private static partial Regex KeyPattern();
    [GeneratedRegex("^[0-9a-f]{64}$", RegexOptions.CultureInvariant)] private static partial Regex HashPattern();
    [GeneratedRegex("^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\.|$)", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant)] private static partial Regex DevicePattern();
}

public static class ControlJson
{
    public static JsonSerializerOptions Options { get; } = CreateOptions();
    public static JsonSerializerOptions CreateOptions()
    {
        var options = new JsonSerializerOptions
        {
            PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
            PropertyNameCaseInsensitive = false,
            UnmappedMemberHandling = JsonUnmappedMemberHandling.Disallow,
            MaxDepth = 32
        };
        options.Converters.Add(new StrictEnumConverterFactory());
        return options;
    }
    public static string Serialize<T>(T value) => JsonSerializer.Serialize(value, Options);
    public static T Deserialize<T>(string json)
    {
        ValidateJson(json);
        return JsonSerializer.Deserialize<T>(json, Options) ?? throw new JsonException("Null is not a message.");
    }
    public static JsonElement Element<T>(T value) => JsonSerializer.SerializeToElement(value, Options);
    public static void ValidateJson(string json)
    {
        if (Encoding.UTF8.GetByteCount(json) > ControlProtocol.MaxMessageBytes) throw new JsonException("Message too large.");
        using var document = JsonDocument.Parse(json, new JsonDocumentOptions { MaxDepth = 32 });
        Visit(document.RootElement);
    }
    private static void Visit(JsonElement element)
    {
        if (element.ValueKind == JsonValueKind.Object)
        {
            var names = new HashSet<string>(StringComparer.Ordinal);
            foreach (var property in element.EnumerateObject())
            {
                if (!names.Add(property.Name)) throw new JsonException("Duplicate JSON property.");
                Visit(property.Value);
            }
        }
        else if (element.ValueKind == JsonValueKind.Array)
            foreach (var item in element.EnumerateArray()) Visit(item);
    }
}

internal sealed class StrictEnumConverterFactory : JsonConverterFactory
{
    public override bool CanConvert(Type type) => type.IsEnum;
    public override JsonConverter CreateConverter(Type type, JsonSerializerOptions options) =>
        (JsonConverter)Activator.CreateInstance(typeof(StrictEnumConverter<>).MakeGenericType(type))!;
    private sealed class StrictEnumConverter<T> : JsonConverter<T> where T : struct, Enum
    {
        public override T Read(ref Utf8JsonReader reader, Type type, JsonSerializerOptions options)
        {
            if (reader.TokenType != JsonTokenType.String) throw new JsonException("Enum must be a string.");
            var text = reader.GetString();
            return Enum.TryParse<T>(text, false, out var value) && Enum.IsDefined(value) && value.ToString() == text
                ? value : throw new JsonException("Unknown enum value.");
        }
        public override void Write(Utf8JsonWriter writer, T value, JsonSerializerOptions options) => writer.WriteStringValue(value.ToString());
    }
}

public enum ServerKind { Java, Bedrock, Velocity }
public enum HostConnectionState { Offline, Reconciling, Online, Disabled, Incompatible }
public enum ProcessState { Stopped, Starting, Running, Stopping, Unmanaged }
public enum GameReadiness { Unknown, Preparing, Ready, Failed }
public enum DependencyReadiness { Running, AstralReady }
public enum HoldMode { Exclusive, Maintenance }
public enum ErrorCode { ProtocolMismatch, AgentDisabled, HostOffline, StaleRevision, StateConflict, ReservationConflict, DeadlineExpired, OperationConflict, UnsupportedCapability, PathOutsideRoot, HashMismatch, TransferOffsetMismatch, DiskFull, Timeout, RecoveryRequired }
public enum OperationKind { Start, Stop, Restart, Kill, ConsoleCommand, ListFiles, ReadFile, WriteFile, FileMutation, ExportFiles, ImportArtifact, CreateBackup, RestoreBackup, ProvisionServer, UpdateServer, PlayerAction, RunMaintenanceStep, PruneBackups, SetBackupProtection, DeleteBackup, QueryLogs, ExportLogs }
