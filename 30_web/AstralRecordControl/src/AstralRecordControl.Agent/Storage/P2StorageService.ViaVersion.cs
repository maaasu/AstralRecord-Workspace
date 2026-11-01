using System.IO.Compression;
using System.Net;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
using AstralRecordControl.Contracts;

namespace AstralRecordControl.Agent.Storage;

public sealed record ViaVersionPreparedUpdate(bool Installed, string? Version, int? MinimumJava, string? TargetRelativePath,
    string? Sha256, string? PreviousSha256, string? StagePath, string? BackupRelativePath)
{
    public bool Updated => StagePath is not null;
    public object Summary => new { installed = Installed, version = Version, minimumJava = MinimumJava, targetRelativePath = TargetRelativePath,
        sha256 = Sha256, previousSha256 = PreviousSha256, updated = Updated, backupRelativePath = BackupRelativePath };
}

public sealed partial class P2StorageService
{
    private const long MaxViaVersionJarBytes = 128L * 1024 * 1024;
    private const string ViaVersionMain = "com.viaversion.viaversion.VelocityPlugin";
    private const string ViaVersionReleaseUrl = "https://api.github.com/repos/ViaVersion/ViaVersion/releases/latest";

    public async Task<ViaVersionPreparedUpdate> PrepareViaVersionUpdateAsync(ServerConfig server, Guid operationId, CancellationToken cancellationToken = default)
    {
        RequireId(operationId);
        EnsureNoRecovery(server);
        var installed = FindViaVersion(server);
        if (installed is null) return new(false, null, null, null, null, null, null, null);
        var (target, relative, identity) = installed.Value;
        var previousSha = await Sha256Async(target, cancellationToken);
        using var metadataTimeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        metadataTimeout.CancelAfter(TimeSpan.FromSeconds(30));
        using var metadataResponse = await GetViaVersionResponseAsync(new Uri(ViaVersionReleaseUrl), null, metadataTimeout.Token);
        var metadataBytes = await ReadBoundedAsync(metadataResponse, 1024 * 1024, metadataTimeout.Token);
        // Reject duplicate JSON properties as well as ambiguous asset selections.
        ControlJson.ValidateJson(Encoding.UTF8.GetString(metadataBytes), 1024 * 1024);
        using var metadata = JsonDocument.Parse(metadataBytes);
        var release = metadata.RootElement;
        if (release.GetProperty("draft").GetBoolean() || release.GetProperty("prerelease").GetBoolean())
            throw new InvalidDataException("Unexpected ViaVersion release.");
        var version = release.GetProperty("tag_name").GetString() ?? "";
        var offeredVersion = ViaVersionVersion(version);
        var assets = release.GetProperty("assets").EnumerateArray().ToArray();
        if (assets.Length is < 1 or > 16) throw new InvalidDataException("Invalid ViaVersion assets.");
        var candidates = new List<JsonElement>();
        foreach (var asset in assets)
        {
            var name = asset.GetProperty("name").GetString() ?? "";
            if (name == "ViaVersion-" + version + ".jar") candidates.Add(asset);
        }
        if (candidates.Count != 1) throw new InvalidDataException("A single version-matching ViaVersion asset is required.");
        var selected = candidates[0];
        var assetName = selected.GetProperty("name").GetString()!;
        var size = selected.GetProperty("size").GetInt64();
        var digest = selected.GetProperty("digest").GetString();
        if (size is < 1 or > MaxViaVersionJarBytes || digest is null || !digest.StartsWith("sha256:", StringComparison.Ordinal) || !ControlProtocol.IsHash(digest[7..]))
            throw new InvalidDataException("Invalid ViaVersion size or digest.");
        var sha = digest[7..];
        var expectedUrl = "https://github.com/ViaVersion/ViaVersion/releases/download/" + version + "/" + assetName;
        if (selected.GetProperty("browser_download_url").GetString() != expectedUrl) throw new InvalidDataException("Unexpected ViaVersion asset URL.");
        if (offeredVersion < identity.Version || offeredVersion == identity.Version && sha != previousSha)
            throw new InvalidDataException("Automatic ViaVersion downgrade or same-version replacement is not allowed.");
        if (sha == previousSha)
        {
            if (offeredVersion != identity.Version) throw new InvalidDataException("ViaVersion version mismatch.");
            var minimumJava = ReadViaVersionMinimumJava(target);
            await VerifyViaVersionJavaAsync(server, minimumJava, cancellationToken);
            return new(true, version, minimumJava, relative, sha, previousSha, null, null);
        }
        EnsureFreeSpace(target, MaxViaVersionJarBytes * 2);
        var stage = ViaVersionStagePath(server, operationId);
        if (File.Exists(stage) || Directory.Exists(stage)) throw new IOException("ViaVersion stage already exists.");
        var stageCreated = false;
        try
        {
            using var downloadTimeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
            downloadTimeout.CancelAfter(TimeSpan.FromMinutes(3));
            using var response = await GetViaVersionResponseAsync(new Uri(expectedUrl), expectedUrl, downloadTimeout.Token);
            if (response.Content.Headers.ContentLength is long responseSize && responseSize != size) throw new InvalidDataException("ViaVersion size mismatch.");
            await using (var input = await response.Content.ReadAsStreamAsync(downloadTimeout.Token))
            await using (var output = new FileStream(stage, FileMode.CreateNew, FileAccess.Write, FileShare.None, 128 * 1024, FileOptions.Asynchronous | FileOptions.WriteThrough))
            {
                stageCreated = true;
                var buffer = new byte[128 * 1024];
                long length = 0;
                while (true)
                {
                    var read = await input.ReadAsync(buffer, downloadTimeout.Token);
                    if (read == 0) break;
                    length = checked(length + read);
                    if (length > size) throw new InvalidDataException("ViaVersion exceeds expected size.");
                    await output.WriteAsync(buffer.AsMemory(0, read), downloadTimeout.Token);
                }
                output.Flush(true);
                if (length != size) throw new InvalidDataException("ViaVersion size mismatch.");
            }
            if (await Sha256Async(stage, downloadTimeout.Token) != sha) throw new InvalidDataException("ViaVersion checksum mismatch.");
            var offered = ReadViaVersionIdentity(stage);
            if (!offered.IsViaVersion || offered.Version != offeredVersion) throw new InvalidDataException("ViaVersion jar identity mismatch.");
            var minimumJava = ReadViaVersionMinimumJava(stage);
            await VerifyViaVersionJavaAsync(server, minimumJava, downloadTimeout.Token);
            return new(true, version, minimumJava, relative, sha, previousSha, stage, relative + ".control-" + operationId.ToString("N") + ".backup");
        }
        catch
        {
            if (stageCreated)
            {
                StoragePaths.CheckNoReparse(stage);
                if (File.Exists(stage)) File.Delete(stage);
            }
            throw;
        }
    }

    public async Task ApplyViaVersionUpdateAsync(ServerConfig server, Guid operationId, ViaVersionPreparedUpdate prepared,
        Func<CancellationToken, Task> verifyGuard, CancellationToken cancellationToken = default)
    {
        RequireId(operationId);
        if (!prepared.Installed || !prepared.Updated) throw new ArgumentException("No ViaVersion update is staged.");
        await gate.WaitAsync(cancellationToken);
        try
        {
            EnsureNoRecovery(server);
            var stage = ViaVersionStagePath(server, operationId);
            if (prepared.StagePath != stage || !File.Exists(stage)) throw new InvalidOperationException("ViaVersion stage is missing.");
            async Task<string> VerifyAsync()
            {
                var installed = FindViaVersion(server) ?? throw new FileConflictException("ViaVersion installation changed.");
                var target = installed.Path;
                if (installed.Relative != prepared.TargetRelativePath || prepared.BackupRelativePath != installed.Relative + ".control-" + operationId.ToString("N") + ".backup" ||
                    await Sha256Async(target, cancellationToken) != prepared.PreviousSha256 ||
                    await Sha256Async(stage, cancellationToken) != prepared.Sha256) throw new FileConflictException("ViaVersion jar changed during update.");
                return target;
            }
            _ = await VerifyAsync();
            await Guard(verifyGuard, cancellationToken);
            var target = await VerifyAsync();
            var backup = target + ".control-" + operationId.ToString("N") + ".backup";
            StoragePaths.CheckNoReparse(backup);
            if (File.Exists(backup) || Directory.Exists(backup)) throw new IOException("ViaVersion backup already exists.");
            File.Replace(stage, target, backup);
        }
        finally { gate.Release(); }
    }

    public void DiscardViaVersionStage(ServerConfig server, Guid operationId)
    {
        RequireId(operationId);
        var stage = ViaVersionStagePath(server, operationId);
        if (File.Exists(stage)) File.Delete(stage);
    }

    private string ViaVersionDirectory(ServerConfig server)
    {
        if (server.Kind != ServerKind.Velocity) throw new ArgumentException("ViaVersion updates require Velocity.");
        return paths.Under(paths.Server(server), "plugins");
    }

    private string ViaVersionStagePath(ServerConfig server, Guid operationId) =>
        paths.Under(ViaVersionDirectory(server), ".control-viaversion-" + operationId.ToString("N") + ".tmp");

    private (string Path, string Relative, ViaVersionIdentity Identity)? FindViaVersion(ServerConfig server)
    {
        var directory = ViaVersionDirectory(server);
        if (!Directory.Exists(directory)) return null;
        var jars = Directory.EnumerateFileSystemEntries(directory).Where(x => Path.GetExtension(x).Equals(".jar", StringComparison.OrdinalIgnoreCase)).Take(257).ToArray();
        if (jars.Length > 256) throw new InvalidDataException("Too many Velocity plugins.");
        (string Path, string Relative, ViaVersionIdentity Identity)? found = null;
        foreach (var jar in jars)
        {
            StoragePaths.CheckNoReparse(jar);
            var identity = ReadViaVersionIdentity(jar);
            if (!identity.IsViaVersion) continue;
            if (found is not null) throw new InvalidDataException("Multiple ViaVersion plugins are installed.");
            found = (jar, "plugins/" + Path.GetFileName(jar), identity);
        }
        return found;
    }

    private sealed record ViaVersionIdentity(bool IsViaVersion, Version? Version);

    private static ViaVersionIdentity ReadViaVersionIdentity(string path)
    {
        StoragePaths.CheckNoReparse(path);
        if (new FileInfo(path).Length is < 1 or > MaxViaVersionJarBytes) throw new InvalidDataException("Invalid Velocity plugin jar size.");
        using var archive = ZipFile.OpenRead(path);
        if (archive.Entries.Count > 10000) throw new InvalidDataException("Too many Velocity plugin jar entries.");
        var descriptors = archive.Entries.Where(x => x.FullName.Equals("velocity-plugin.json", StringComparison.OrdinalIgnoreCase)).ToArray();
        var namedCandidate = Path.GetFileName(path).StartsWith("ViaVersion", StringComparison.OrdinalIgnoreCase);
        if (descriptors.Length == 0 && !namedCandidate) return new(false, null);
        if (descriptors.Length != 1 || descriptors[0].FullName != "velocity-plugin.json" || descriptors[0].Length is < 1 or > 64 * 1024)
            throw new InvalidDataException("A single velocity-plugin.json is required for ViaVersion.");
        using var descriptorStream = descriptors[0].Open();
        var descriptorBytes = new byte[64 * 1024 + 1];
        var descriptorLength = descriptorStream.ReadAtLeast(descriptorBytes, descriptorBytes.Length, throwOnEndOfStream: false);
        if (descriptorLength > 64 * 1024) throw new InvalidDataException("Velocity plugin metadata exceeds limit.");
        var text = new UTF8Encoding(false, true).GetString(descriptorBytes, 0, descriptorLength);
        ControlJson.ValidateJson(text, 64 * 1024);
        using var descriptor = JsonDocument.Parse(text);
        var root = descriptor.RootElement;
        var id = root.GetProperty("id").GetString();
        var main = root.GetProperty("main").GetString();
        var related = namedCandidate || string.Equals(id, "viaversion", StringComparison.OrdinalIgnoreCase) || main == ViaVersionMain;
        if (!related) return new(false, null);
        if (id != "viaversion" || main != ViaVersionMain) throw new InvalidDataException("Ambiguous ViaVersion identity.");
        return new(true, ViaVersionVersion(root.GetProperty("version").GetString() ?? ""));
    }

    private static Version ViaVersionVersion(string value)
    {
        if (value.Length > 32 || !Regex.IsMatch(value, @"\A(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\z", RegexOptions.CultureInvariant) || !Version.TryParse(value, out var version))
            throw new InvalidDataException("Unrecognized ViaVersion version.");
        return version;
    }

    private static int ReadViaVersionMinimumJava(string path)
    {
        StoragePaths.CheckNoReparse(path);
        using var archive = ZipFile.OpenRead(path);
        if (archive.Entries.Count > 10000 || archive.Entries.Count(x => x.FullName == ViaVersionMain.Replace('.', '/') + ".class") != 1)
            throw new InvalidDataException("ViaVersion main class is missing or ambiguous.");
        var minimum = 1;
        var names = new HashSet<string>(StringComparer.Ordinal);
        Span<byte> header = stackalloc byte[8];
        foreach (var entry in archive.Entries.Where(x => x.FullName.EndsWith(".class", StringComparison.Ordinal) && !x.FullName.StartsWith("META-INF/versions/", StringComparison.Ordinal)))
        {
            if (!names.Add(entry.FullName)) throw new InvalidDataException("Duplicate ViaVersion class.");
            using var input = entry.Open();
            input.ReadExactly(header);
            var major = (header[6] << 8) | header[7];
            if (!header[..4].SequenceEqual(new byte[] { 0xca, 0xfe, 0xba, 0xbe }) || major is < 45 or > 144 || header[4] != 0 || header[5] != 0)
                throw new InvalidDataException("Unsupported ViaVersion class format.");
            minimum = Math.Max(minimum, major - 44);
        }
        return minimum;
    }

    private async Task VerifyViaVersionJavaAsync(ServerConfig server, int minimumJava, CancellationToken cancellationToken)
    {
        if (await javaVersionProbe(paths.JavaExecutable(server), cancellationToken) < minimumJava)
            throw new InvalidDataException("Registered Java runtime is too old for ViaVersion.");
    }

    private async Task<HttpResponseMessage> GetViaVersionResponseAsync(Uri uri, string? assetUrl, CancellationToken cancellationToken)
    {
        for (var redirects = 0; redirects <= 3; redirects++)
        {
            var metadata = assetUrl is null;
            var validPath = metadata ? uri.AbsoluteUri == ViaVersionReleaseUrl : uri.AbsoluteUri == assetUrl ||
                uri.Host == "release-assets.githubusercontent.com" && Regex.IsMatch(uri.AbsolutePath, @"\A/github-production-release-asset/[0-9]+/[A-Za-z0-9-]+\z", RegexOptions.CultureInvariant);
            if (!validPath || uri.Scheme != "https" || uri.Port != 443 || !string.IsNullOrEmpty(uri.UserInfo) || !string.IsNullOrEmpty(uri.Fragment))
                throw new InvalidDataException("Unexpected ViaVersion download destination.");
            if (geyserHttpClient.DefaultRequestHeaders.Authorization is not null) throw new InvalidOperationException("Authenticated ViaVersion requests are not allowed.");
            using var request = new HttpRequestMessage(HttpMethod.Get, uri);
            request.Headers.UserAgent.ParseAdd("AstralRecordControl/1.0 (+https://astralrecord.com)");
            HttpResponseMessage response;
            try { response = await geyserHttpClient.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancellationToken); }
            catch (HttpRequestException) { throw new HttpRequestException("Official ViaVersion request failed."); }
            if (response.RequestMessage?.RequestUri is Uri actual && actual != uri)
            {
                response.Dispose();
                throw new InvalidDataException("Automatic ViaVersion redirects are not allowed.");
            }
            if (response.StatusCode is HttpStatusCode.Found or HttpStatusCode.MovedPermanently or HttpStatusCode.SeeOther or HttpStatusCode.TemporaryRedirect or HttpStatusCode.PermanentRedirect)
            {
                var location = response.Headers.Location;
                response.Dispose();
                if (metadata || location is null) throw new InvalidDataException("Unexpected ViaVersion redirect.");
                uri = new Uri(uri, location);
                continue;
            }
            if (!response.IsSuccessStatusCode)
            {
                response.Dispose();
                throw new HttpRequestException("Official ViaVersion request failed.");
            }
            return response;
        }
        throw new InvalidDataException("Too many ViaVersion redirects.");
    }
}
