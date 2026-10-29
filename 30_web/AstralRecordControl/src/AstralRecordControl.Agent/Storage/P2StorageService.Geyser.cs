using System.Net;
using System.Security.Cryptography;
using System.Text.Json;
using AstralRecordControl.Contracts;

namespace AstralRecordControl.Agent.Storage;

public sealed record GeyserPreparedUpdate(string Version, int Build, string Sha256, string PreviousSha256, string? StagePath, string? BackupRelativePath)
{
    public bool Updated => StagePath is not null;
    public object Summary => new { version = Version, build = Build, sha256 = Sha256, previousSha256 = PreviousSha256,
        updated = Updated, backupRelativePath = BackupRelativePath };
}

public sealed partial class P2StorageService
{
    private const long MaxGeyserJarBytes = 128L * 1024 * 1024;
    private static readonly Uri GeyserBuildUri = new("https://download.geysermc.org/v2/projects/geyser/versions/latest/builds/latest");

    public async Task<GeyserPreparedUpdate> PrepareGeyserUpdateAsync(ServerConfig server, Guid operationId, CancellationToken cancellationToken = default)
    {
        RequireId(operationId);
        if (server.Kind != ServerKind.Velocity) throw new ArgumentException("Geyser updates require a Velocity server.");
        EnsureNoRecovery(server);
        var target = GeyserPluginPath(server);
        if (!File.Exists(target)) throw new FileNotFoundException("Geyser-Velocity.jar is not installed.", target);
        var previousSha = await Sha256Async(target, cancellationToken);
        using var metadataTimeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        metadataTimeout.CancelAfter(TimeSpan.FromSeconds(30));
        using var metadataResponse = await GetGeyserResponseAsync(GeyserBuildUri, metadataTimeout.Token);
        var metadataBytes = await ReadBoundedAsync(metadataResponse, 128 * 1024, metadataTimeout.Token);
        using var metadata = JsonDocument.Parse(metadataBytes);
        var root = metadata.RootElement;
        if (root.GetProperty("project_id").GetString() != "geyser") throw new InvalidDataException("Unexpected Geyser project.");
        var version = root.GetProperty("version").GetString() ?? throw new InvalidDataException("Missing Geyser version.");
        var build = root.GetProperty("build").GetInt32();
        var velocity = root.GetProperty("downloads").GetProperty("velocity");
        var name = velocity.GetProperty("name").GetString();
        var sha = velocity.GetProperty("sha256").GetString();
        if (name != "Geyser-Velocity.jar" || version.Length is < 1 or > 64 || version.Any(c => !(char.IsAsciiLetterOrDigit(c) || c is '.' or '-')) ||
            build < 1 || !ControlProtocol.IsHash(sha)) throw new InvalidDataException("Invalid Geyser build metadata.");
        if (string.Equals(sha, previousSha, StringComparison.Ordinal)) return new GeyserPreparedUpdate(version, build, sha!, previousSha, null, null);

        EnsureFreeSpace(target, MaxGeyserJarBytes * 2);
        var stage = Path.Combine(Path.GetDirectoryName(target)!, ".control-geyser-" + operationId.ToString("N") + ".tmp");
        StoragePaths.CheckNoReparse(stage);
        if (File.Exists(stage)) throw new IOException("Geyser update stage already exists.");
        var downloadUri = new Uri($"https://download.geysermc.org/v2/projects/geyser/versions/{version}/builds/{build}/downloads/velocity");
        try
        {
            using var downloadTimeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
            downloadTimeout.CancelAfter(TimeSpan.FromMinutes(3));
            using var response = await GetGeyserResponseAsync(downloadUri, downloadTimeout.Token);
            if (response.Content.Headers.ContentLength is > MaxGeyserJarBytes) throw new InvalidDataException("Geyser jar exceeds limit.");
            await using var input = await response.Content.ReadAsStreamAsync(downloadTimeout.Token);
            await using var output = new FileStream(stage, FileMode.CreateNew, FileAccess.Write, FileShare.None, 128 * 1024, FileOptions.WriteThrough | FileOptions.Asynchronous);
            using var hash = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
            var buffer = new byte[128 * 1024];
            long length = 0;
            while (true)
            {
                var read = await input.ReadAsync(buffer, downloadTimeout.Token);
                if (read == 0) break;
                length = checked(length + read);
                if (length > MaxGeyserJarBytes) throw new InvalidDataException("Geyser jar exceeds limit.");
                hash.AppendData(buffer, 0, read);
                await output.WriteAsync(buffer.AsMemory(0, read), downloadTimeout.Token);
            }
            output.Flush(true);
            if (length == 0 || Convert.ToHexStringLower(hash.GetHashAndReset()) != sha) throw new InvalidDataException("Geyser jar checksum mismatch.");
            return new GeyserPreparedUpdate(version, build, sha!, previousSha, stage,
                "plugins/Geyser-Velocity.jar.control-" + operationId.ToString("N") + ".backup");
        }
        catch
        {
            if (File.Exists(stage)) File.Delete(stage);
            throw;
        }
    }

    public async Task ApplyGeyserUpdateAsync(ServerConfig server, Guid operationId, GeyserPreparedUpdate prepared,
        Func<CancellationToken, Task> verifyGuard, CancellationToken cancellationToken = default)
    {
        if (!prepared.Updated || prepared.StagePath is null) throw new ArgumentException("No Geyser update is staged.");
        await gate.WaitAsync(cancellationToken);
        try
        {
            EnsureNoRecovery(server);
            var target = GeyserPluginPath(server);
            var expectedStage = Path.Combine(Path.GetDirectoryName(target)!, ".control-geyser-" + operationId.ToString("N") + ".tmp");
            if (!string.Equals(prepared.StagePath, expectedStage, StringComparison.OrdinalIgnoreCase) || !File.Exists(target) || !File.Exists(expectedStage))
                throw new InvalidOperationException("Geyser update stage or target is missing.");
            if (await Sha256Async(target, cancellationToken) != prepared.PreviousSha256 || await Sha256Async(expectedStage, cancellationToken) != prepared.Sha256)
                throw new FileConflictException("Geyser jar changed during update.");
            await Guard(verifyGuard, cancellationToken);
            _ = GeyserPluginPath(server);
            StoragePaths.CheckNoReparse(expectedStage);
            if (await Sha256Async(target, cancellationToken) != prepared.PreviousSha256) throw new FileConflictException("Geyser jar changed during update.");
            var backup = target + ".control-" + operationId.ToString("N") + ".backup";
            StoragePaths.CheckNoReparse(backup);
            if (File.Exists(backup)) throw new IOException("Geyser backup already exists.");
            File.Replace(expectedStage, target, backup);
        }
        finally { gate.Release(); }
    }

    public void DiscardGeyserStage(ServerConfig server, Guid operationId)
    {
        var target = GeyserPluginPath(server);
        var stage = Path.Combine(Path.GetDirectoryName(target)!, ".control-geyser-" + operationId.ToString("N") + ".tmp");
        StoragePaths.CheckNoReparse(stage);
        if (File.Exists(stage)) File.Delete(stage);
    }

    private string GeyserPluginPath(ServerConfig server)
    {
        var root = paths.Server(server);
        var path = Path.Combine(root, "plugins", "Geyser-Velocity.jar");
        StoragePaths.CheckNoReparse(path);
        if (!Directory.Exists(Path.GetDirectoryName(path))) throw new DirectoryNotFoundException("Velocity plugins directory is missing.");
        return path;
    }

    private async Task<HttpResponseMessage> GetGeyserResponseAsync(Uri uri, CancellationToken cancellationToken)
    {
        for (var redirects = 0; redirects <= 3; redirects++)
        {
            if (uri.Scheme != Uri.UriSchemeHttps || uri.Host != "download.geysermc.org" || uri.Port != 443 ||
                !uri.AbsolutePath.StartsWith("/v2/projects/geyser/versions/", StringComparison.Ordinal) ||
                !string.IsNullOrEmpty(uri.UserInfo) || !string.IsNullOrEmpty(uri.Query) || !string.IsNullOrEmpty(uri.Fragment))
                throw new InvalidDataException("Unexpected Geyser download host.");
            using var request = new HttpRequestMessage(HttpMethod.Get, uri);
            var response = await geyserHttpClient.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancellationToken);
            if (response.StatusCode is HttpStatusCode.Found or HttpStatusCode.MovedPermanently or HttpStatusCode.TemporaryRedirect or HttpStatusCode.PermanentRedirect)
            {
                uri = response.Headers.Location is Uri location ? new Uri(uri, location) : throw new InvalidDataException("Missing Geyser redirect location.");
                response.Dispose();
                continue;
            }
            if (!response.IsSuccessStatusCode)
            {
                response.Dispose();
                throw new HttpRequestException("Geyser download request failed.");
            }
            return response;
        }
        throw new InvalidDataException("Too many Geyser redirects.");
    }

    private static async Task<byte[]> ReadBoundedAsync(HttpResponseMessage response, int limit, CancellationToken cancellationToken)
    {
        if (response.Content.Headers.ContentLength is long length && length > limit) throw new InvalidDataException("Geyser metadata exceeds limit.");
        await using var input = await response.Content.ReadAsStreamAsync(cancellationToken);
        using var output = new MemoryStream();
        var buffer = new byte[8192];
        while (true)
        {
            var read = await input.ReadAsync(buffer, cancellationToken);
            if (read == 0) return output.ToArray();
            if (output.Length + read > limit) throw new InvalidDataException("Geyser metadata exceeds limit.");
            output.Write(buffer, 0, read);
        }
    }
}
