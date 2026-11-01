using System.IO.Compression;
using System.Net;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
using AstralRecordControl.Contracts;

namespace AstralRecordControl.Agent.Storage;

public sealed record GeyserDisplayEntityPreparedUpdate(bool Installed, string? Version, int? MinimumJava, string? TargetRelativePath,
    string? Sha256, string? PreviousSha256, string? StagePath, string? BackupRelativePath)
{
    public bool Updated => StagePath is not null;
    public object Summary => new { installed = Installed, version = Version, minimumJava = MinimumJava, targetRelativePath = TargetRelativePath,
        sha256 = Sha256, previousSha256 = PreviousSha256, updated = Updated, backupRelativePath = BackupRelativePath };
}

public sealed partial class P2StorageService
{
    private const long MaxDisplayEntityJarBytes = 128L * 1024 * 1024;
    private const string DisplayEntityMain = "me.geyserextensionists.geyserdisplayentity.GeyserDisplayEntity";
    private const string DisplayEntityReleaseUrl = "https://api.github.com/repos/GeyserExtensionists/GeyserDisplayEntity/releases/latest";
    private const string DisplayEntityAssetPrefix = "https://github.com/GeyserExtensionists/GeyserDisplayEntity/releases/download/latest/";

    public async Task<GeyserDisplayEntityPreparedUpdate> PrepareGeyserDisplayEntityUpdateAsync(ServerConfig server, Guid operationId, CancellationToken cancellationToken = default)
    {
        RequireId(operationId);
        EnsureNoRecovery(server);
        var installed = FindDisplayEntity(server);
        if (installed is null) return new(false, null, null, null, null, null, null, null);
        var (target, relative, identity) = installed.Value;
        var previousSha = await Sha256Async(target, cancellationToken);
        using var metadataTimeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        metadataTimeout.CancelAfter(TimeSpan.FromSeconds(30));
        using var metadataResponse = await GetDisplayEntityResponseAsync(new Uri(DisplayEntityReleaseUrl), null, metadataTimeout.Token);
        var metadataBytes = await ReadBoundedAsync(metadataResponse, 1024 * 1024, metadataTimeout.Token);
        // Reject duplicate JSON properties as well as ambiguous asset selections.
        ControlJson.ValidateJson(Encoding.UTF8.GetString(metadataBytes), 1024 * 1024);
        using var metadata = JsonDocument.Parse(metadataBytes);
        var release = metadata.RootElement;
        if (release.GetProperty("draft").GetBoolean() || release.GetProperty("prerelease").GetBoolean() || release.GetProperty("tag_name").GetString() != "latest")
            throw new InvalidDataException("Unexpected GeyserDisplayEntity release.");
        var assets = release.GetProperty("assets").EnumerateArray().ToArray();
        if (assets.Length is < 1 or > 16) throw new InvalidDataException("Invalid GeyserDisplayEntity assets.");
        var candidates = new List<JsonElement>();
        foreach (var asset in assets)
        {
            var name = asset.GetProperty("name").GetString() ?? "";
            if (Regex.IsMatch(name, @"\AGeyserDisplayEntity-[0-9]+\.[0-9]+\.[0-9]+\.jar\z", RegexOptions.CultureInvariant)) candidates.Add(asset);
            else if (name != "GeyserDisplayEntityPack.mcpack" && !Regex.IsMatch(name, @"\AGeyserDisplayEntity-fabric-[0-9]+\.[0-9]+\.[0-9]+\.jar\z", RegexOptions.CultureInvariant))
                throw new InvalidDataException("Unrecognized GeyserDisplayEntity asset.");
        }
        if (candidates.Count != 1) throw new InvalidDataException("A single non-Fabric GeyserDisplayEntity asset is required.");
        var selected = candidates[0];
        var assetName = selected.GetProperty("name").GetString()!;
        var version = assetName["GeyserDisplayEntity-".Length..^4];
        var offeredVersion = DisplayEntityVersion(version);
        var size = selected.GetProperty("size").GetInt64();
        var digest = selected.GetProperty("digest").GetString();
        if (size is < 1 or > MaxDisplayEntityJarBytes || digest is null || !digest.StartsWith("sha256:", StringComparison.Ordinal) || !ControlProtocol.IsHash(digest[7..]))
            throw new InvalidDataException("Invalid GeyserDisplayEntity size or digest.");
        var sha = digest[7..];
        var expectedUrl = DisplayEntityAssetPrefix + assetName;
        if (selected.GetProperty("browser_download_url").GetString() != expectedUrl) throw new InvalidDataException("Unexpected GeyserDisplayEntity asset URL.");
        if (offeredVersion < identity.Version || offeredVersion == identity.Version && sha != previousSha)
            throw new InvalidDataException("Automatic GeyserDisplayEntity downgrade or same-version replacement is not allowed.");
        if (sha == previousSha)
        {
            if (offeredVersion != identity.Version) throw new InvalidDataException("GeyserDisplayEntity version mismatch.");
            var minimumJava = ReadDisplayEntityMinimumJava(target);
            await VerifyDisplayEntityJavaAsync(server, minimumJava, cancellationToken);
            return new(true, version, minimumJava, relative, sha, previousSha, null, null);
        }
        EnsureFreeSpace(target, MaxDisplayEntityJarBytes * 2);
        var stage = DisplayEntityStagePath(server, operationId);
        if (File.Exists(stage) || Directory.Exists(stage)) throw new IOException("GeyserDisplayEntity stage already exists.");
        try
        {
            using var downloadTimeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
            downloadTimeout.CancelAfter(TimeSpan.FromMinutes(3));
            using var response = await GetDisplayEntityResponseAsync(new Uri(expectedUrl), expectedUrl, downloadTimeout.Token);
            if (response.Content.Headers.ContentLength is long responseSize && responseSize != size) throw new InvalidDataException("GeyserDisplayEntity size mismatch.");
            await using (var input = await response.Content.ReadAsStreamAsync(downloadTimeout.Token))
            await using (var output = new FileStream(stage, FileMode.CreateNew, FileAccess.Write, FileShare.None, 128 * 1024, FileOptions.Asynchronous | FileOptions.WriteThrough))
            {
                var buffer = new byte[128 * 1024];
                long length = 0;
                while (true)
                {
                    var read = await input.ReadAsync(buffer, downloadTimeout.Token);
                    if (read == 0) break;
                    length = checked(length + read);
                    if (length > size) throw new InvalidDataException("GeyserDisplayEntity exceeds expected size.");
                    await output.WriteAsync(buffer.AsMemory(0, read), downloadTimeout.Token);
                }
                output.Flush(true);
                if (length != size) throw new InvalidDataException("GeyserDisplayEntity size mismatch.");
            }
            if (await Sha256Async(stage, downloadTimeout.Token) != sha) throw new InvalidDataException("GeyserDisplayEntity checksum mismatch.");
            var offered = ReadDisplayEntityIdentity(stage);
            if (!offered.IsDisplayEntity || offered.Version != offeredVersion) throw new InvalidDataException("GeyserDisplayEntity jar identity mismatch.");
            var minimumJava = ReadDisplayEntityMinimumJava(stage);
            await VerifyDisplayEntityJavaAsync(server, minimumJava, downloadTimeout.Token);
            return new(true, version, minimumJava, relative, sha, previousSha, stage, relative + ".control-" + operationId.ToString("N") + ".backup");
        }
        catch
        {
            StoragePaths.CheckNoReparse(stage);
            if (File.Exists(stage)) File.Delete(stage);
            throw;
        }
    }

    public async Task ApplyGeyserDisplayEntityUpdateAsync(ServerConfig server, Guid operationId, GeyserDisplayEntityPreparedUpdate prepared,
        Func<CancellationToken, Task> verifyGuard, CancellationToken cancellationToken = default)
    {
        RequireId(operationId);
        if (!prepared.Installed || !prepared.Updated) throw new ArgumentException("No GeyserDisplayEntity update is staged.");
        await gate.WaitAsync(cancellationToken);
        try
        {
            EnsureNoRecovery(server);
            var stage = DisplayEntityStagePath(server, operationId);
            if (prepared.StagePath != stage || !File.Exists(stage)) throw new InvalidOperationException("GeyserDisplayEntity stage is missing.");
            async Task<string> VerifyAsync()
            {
                var installed = FindDisplayEntity(server) ?? throw new FileConflictException("GeyserDisplayEntity installation changed.");
                var target = installed.Path;
                if (installed.Relative != prepared.TargetRelativePath || await Sha256Async(target, cancellationToken) != prepared.PreviousSha256 ||
                    await Sha256Async(stage, cancellationToken) != prepared.Sha256) throw new FileConflictException("GeyserDisplayEntity jar changed during update.");
                return target;
            }
            _ = await VerifyAsync();
            await Guard(verifyGuard, cancellationToken);
            var target = await VerifyAsync();
            var backup = target + ".control-" + operationId.ToString("N") + ".backup";
            StoragePaths.CheckNoReparse(backup);
            if (File.Exists(backup) || Directory.Exists(backup)) throw new IOException("GeyserDisplayEntity backup already exists.");
            File.Replace(stage, target, backup);
        }
        finally { gate.Release(); }
    }

    public void DiscardGeyserDisplayEntityStage(ServerConfig server, Guid operationId)
    {
        RequireId(operationId);
        var stage = DisplayEntityStagePath(server, operationId);
        if (File.Exists(stage)) File.Delete(stage);
    }

    private string DisplayEntityDirectory(ServerConfig server)
    {
        if (server.Kind != ServerKind.Velocity) throw new ArgumentException("GeyserDisplayEntity updates require Velocity.");
        return paths.Under(paths.Server(server), "plugins/Geyser-Velocity/extensions");
    }

    private string DisplayEntityStagePath(ServerConfig server, Guid operationId) =>
        paths.Under(DisplayEntityDirectory(server), ".control-geyser-displayentity-" + operationId.ToString("N") + ".tmp");

    private (string Path, string Relative, DisplayEntityIdentity Identity)? FindDisplayEntity(ServerConfig server)
    {
        var directory = DisplayEntityDirectory(server);
        if (!Directory.Exists(directory)) return null;
        var jars = Directory.EnumerateFileSystemEntries(directory).Where(x => Path.GetExtension(x).Equals(".jar", StringComparison.OrdinalIgnoreCase)).Take(257).ToArray();
        if (jars.Length > 256) throw new InvalidDataException("Too many Geyser extensions.");
        (string Path, string Relative, DisplayEntityIdentity Identity)? found = null;
        foreach (var jar in jars)
        {
            StoragePaths.CheckNoReparse(jar);
            var identity = ReadDisplayEntityIdentity(jar);
            if (!identity.IsDisplayEntity) continue;
            if (found is not null) throw new InvalidDataException("Multiple GeyserDisplayEntity extensions are installed.");
            found = (jar, "plugins/Geyser-Velocity/extensions/" + Path.GetFileName(jar), identity);
        }
        return found;
    }

    private sealed record DisplayEntityIdentity(bool IsDisplayEntity, Version? Version);

    private static DisplayEntityIdentity ReadDisplayEntityIdentity(string path)
    {
        StoragePaths.CheckNoReparse(path);
        if (new FileInfo(path).Length is < 1 or > MaxDisplayEntityJarBytes) throw new InvalidDataException("Invalid extension jar size.");
        using var archive = ZipFile.OpenRead(path);
        if (archive.Entries.Count > 10000) throw new InvalidDataException("Too many extension jar entries.");
        var descriptors = archive.Entries.Where(x => x.FullName.Equals("extension.yml", StringComparison.OrdinalIgnoreCase)).ToArray();
        if (descriptors.Length != 1 || descriptors[0].FullName != "extension.yml" || descriptors[0].Length is < 1 or > 64 * 1024)
            throw new InvalidDataException("A single extension.yml is required in every extension jar.");
        using var descriptorStream = descriptors[0].Open();
        var descriptorBytes = new byte[64 * 1024 + 1];
        var descriptorLength = descriptorStream.ReadAtLeast(descriptorBytes, descriptorBytes.Length, throwOnEndOfStream: false);
        if (descriptorLength > 64 * 1024) throw new InvalidDataException("Extension metadata exceeds limit.");
        var text = new UTF8Encoding(false, true).GetString(descriptorBytes, 0, descriptorLength);
        var fields = new Dictionary<string, string>(StringComparer.Ordinal);
        foreach (var line in text.Split('\n'))
        {
            if (string.IsNullOrWhiteSpace(line) || line[0] == '#' || char.IsWhiteSpace(line[0])) continue;
            var match = Regex.Match(line.TrimEnd('\r'), @"\A([A-Za-z][A-Za-z0-9_-]*):\s*(.*?)\s*\z", RegexOptions.CultureInvariant);
            if (!match.Success || !fields.TryAdd(match.Groups[1].Value, match.Groups[2].Value)) throw new InvalidDataException("Invalid or duplicate extension metadata.");
        }
        string Scalar(string key)
        {
            if (!fields.TryGetValue(key, out var value)) throw new InvalidDataException("Missing extension identity.");
            if (value.Length >= 2 && (value[0] == '"' && value[^1] == '"' || value[0] == '\'' && value[^1] == '\'')) value = value[1..^1];
            if (!Regex.IsMatch(value, key == "name" ? @"\A[A-Za-z0-9_. -]+\z" : @"\A[A-Za-z0-9_.-]+\z", RegexOptions.CultureInvariant))
                throw new InvalidDataException("Unsupported extension identity.");
            return value;
        }
        var id = Scalar("id"); var name = Scalar("name"); var main = Scalar("main");
        var related = id.Equals("geyserdisplayentity", StringComparison.OrdinalIgnoreCase) || name.Equals("GeyserDisplayEntity", StringComparison.OrdinalIgnoreCase) || main == DisplayEntityMain;
        if (!related) return new(false, null);
        if (id != "geyserdisplayentity" || name != "GeyserDisplayEntity" || main != DisplayEntityMain)
            throw new InvalidDataException("Ambiguous GeyserDisplayEntity identity.");
        return new(true, DisplayEntityVersion(Scalar("version")));
    }

    private static Version DisplayEntityVersion(string value)
    {
        if (value.Length > 32 || !Regex.IsMatch(value, @"\A[0-9]+\.[0-9]+\.[0-9]+\z", RegexOptions.CultureInvariant) || !Version.TryParse(value, out var version))
            throw new InvalidDataException("Unrecognized GeyserDisplayEntity version.");
        return version;
    }

    private static int ReadDisplayEntityMinimumJava(string path)
    {
        StoragePaths.CheckNoReparse(path);
        using var archive = ZipFile.OpenRead(path);
        if (archive.Entries.Count > 10000 || archive.Entries.Count(x => x.FullName == DisplayEntityMain.Replace('.', '/') + ".class") != 1)
            throw new InvalidDataException("GeyserDisplayEntity main class is missing or ambiguous.");
        var minimum = 1;
        var names = new HashSet<string>(StringComparer.Ordinal);
        Span<byte> header = stackalloc byte[8];
        foreach (var entry in archive.Entries.Where(x => x.FullName.EndsWith(".class", StringComparison.Ordinal) && !x.FullName.StartsWith("META-INF/versions/", StringComparison.Ordinal)))
        {
            if (!names.Add(entry.FullName)) throw new InvalidDataException("Duplicate extension class.");
            using var input = entry.Open();
            input.ReadExactly(header);
            var major = (header[6] << 8) | header[7];
            if (!header[..4].SequenceEqual(new byte[] { 0xca, 0xfe, 0xba, 0xbe }) || major is < 45 or > 144 || header[4] != 0 || header[5] != 0)
                throw new InvalidDataException("Unsupported extension class format.");
            minimum = Math.Max(minimum, major - 44);
        }
        return minimum;
    }

    private async Task VerifyDisplayEntityJavaAsync(ServerConfig server, int minimumJava, CancellationToken cancellationToken)
    {
        if (await javaVersionProbe(paths.JavaExecutable(server), cancellationToken) < minimumJava)
            throw new InvalidDataException("Registered Java runtime is too old for GeyserDisplayEntity.");
    }

    private async Task<HttpResponseMessage> GetDisplayEntityResponseAsync(Uri uri, string? assetUrl, CancellationToken cancellationToken)
    {
        for (var redirects = 0; redirects <= 3; redirects++)
        {
            var metadata = assetUrl is null;
            var validPath = metadata ? uri.AbsoluteUri == DisplayEntityReleaseUrl : uri.AbsoluteUri == assetUrl ||
                uri.Host == "release-assets.githubusercontent.com" && Regex.IsMatch(uri.AbsolutePath, @"\A/github-production-release-asset/[0-9]+/[A-Za-z0-9-]+\z", RegexOptions.CultureInvariant);
            if (!validPath || uri.Scheme != "https" || uri.Port != 443 || !string.IsNullOrEmpty(uri.UserInfo) || !string.IsNullOrEmpty(uri.Fragment))
                throw new InvalidDataException("Unexpected GeyserDisplayEntity download destination.");
            using var request = new HttpRequestMessage(HttpMethod.Get, uri);
            request.Headers.UserAgent.ParseAdd("AstralRecordControl/1.0 (+https://astralrecord.com)");
            HttpResponseMessage response;
            try { response = await geyserHttpClient.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancellationToken); }
            catch (HttpRequestException) { throw new HttpRequestException("Official GeyserDisplayEntity request failed."); }
            if (response.RequestMessage?.RequestUri is Uri actual && actual != uri)
            {
                response.Dispose();
                throw new InvalidDataException("Automatic GeyserDisplayEntity redirects are not allowed.");
            }
            if (response.StatusCode is HttpStatusCode.Found or HttpStatusCode.MovedPermanently or HttpStatusCode.SeeOther or HttpStatusCode.TemporaryRedirect or HttpStatusCode.PermanentRedirect)
            {
                var location = response.Headers.Location;
                response.Dispose();
                if (metadata || location is null) throw new InvalidDataException("Unexpected GeyserDisplayEntity redirect.");
                uri = new Uri(uri, location);
                continue;
            }
            if (!response.IsSuccessStatusCode)
            {
                response.Dispose();
                throw new HttpRequestException("Official GeyserDisplayEntity request failed.");
            }
            return response;
        }
        throw new InvalidDataException("Too many GeyserDisplayEntity redirects.");
    }
}
