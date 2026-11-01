using System.Diagnostics;
using System.Globalization;
using System.IO.Compression;
using System.Text.Json;
using System.Text.RegularExpressions;
using AstralRecordControl.Contracts;

namespace AstralRecordControl.Agent.Storage;

public sealed record VelocityPreparedUpdate(string Version, int Build, string Channel, int MinimumJava, string TargetRelativePath,
    string Sha256, string PreviousSha256, string? StagePath, string? BackupRelativePath)
{
    public bool Updated => StagePath is not null;
    public object Summary => new { version = Version, build = Build, channel = Channel, minimumJava = MinimumJava,
        targetRelativePath = TargetRelativePath, sha256 = Sha256, previousSha256 = PreviousSha256,
        updated = Updated, backupRelativePath = BackupRelativePath };
}

public sealed partial class P2StorageService
{
    private const long MaxVelocityJarBytes = 128L * 1024 * 1024;
    private const string VelocityProjectUrl = "https://fill.papermc.io/v3/projects/velocity";

    public async Task<VelocityPreparedUpdate> PrepareVelocityUpdateAsync(ServerConfig server, Guid operationId, CancellationToken cancellationToken = default)
    {
        RequireId(operationId);
        EnsureNoRecovery(server);
        var (target, relative) = VelocityJarPath(server);
        if (!File.Exists(target)) throw new FileNotFoundException("Velocity jar is not installed.");
        var previousSha = await Sha256Async(target, cancellationToken);
        var installed = ReadVelocityIdentity(target);
        using var metadataTimeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        metadataTimeout.CancelAfter(TimeSpan.FromSeconds(30));
        using var project = await ReadVelocityMetadataAsync(new Uri(VelocityProjectUrl), metadataTimeout.Token);
        if (project.RootElement.GetProperty("project").GetProperty("id").GetString() != "velocity")
            throw new InvalidDataException("Unexpected Velocity project.");
        var versions = project.RootElement.GetProperty("versions").EnumerateObject().SelectMany(x => x.Value.EnumerateArray())
            .Select(x => x.GetString() ?? throw new InvalidDataException("Missing Velocity version.")).Distinct().ToArray();
        if (versions.Length is < 1 or > 128 || versions.Any(x => !ValidVelocityVersion(x)))
            throw new InvalidDataException("Invalid Velocity versions.");
        foreach (var version in versions.OrderByDescending(VelocityBaseVersion).ThenBy(x => x.Contains('-')))
        {
            using var builds = await ReadVelocityMetadataAsync(new Uri($"{VelocityProjectUrl}/versions/{version}/builds"), metadataTimeout.Token);
            var candidates = builds.RootElement.EnumerateArray().Where(x => x.GetProperty("channel").GetString() is "STABLE" or "RECOMMENDED")
                .OrderByDescending(x => x.GetProperty("id").GetInt32()).ToArray();
            if (candidates.Length == 0) continue;
            using var versionMetadata = await ReadVelocityMetadataAsync(new Uri($"{VelocityProjectUrl}/versions/{version}"), metadataTimeout.Token);
            var info = versionMetadata.RootElement.GetProperty("version");
            if (info.GetProperty("id").GetString() != version) throw new InvalidDataException("Unexpected Velocity version.");
            if (info.GetProperty("support").GetProperty("status").GetString() != "SUPPORTED") continue;
            var minimumJava = info.GetProperty("java").GetProperty("version").GetProperty("minimum").GetInt32();
            var selected = candidates[0];
            var build = selected.GetProperty("id").GetInt32();
            var channel = selected.GetProperty("channel").GetString()!;
            var download = selected.GetProperty("downloads").GetProperty("server:default");
            var name = download.GetProperty("name").GetString();
            var sha = download.GetProperty("checksums").GetProperty("sha256").GetString();
            var size = download.GetProperty("size").GetInt64();
            if (build < 1 || minimumJava is < 21 or > 100 || name != $"velocity-{version}-{build}.jar" ||
                !ControlProtocol.IsHash(sha) || size is < 1 or > MaxVelocityJarBytes)
                throw new InvalidDataException("Invalid Velocity build metadata.");
            var expectedUrl = $"https://fill-data.papermc.io/v1/objects/{sha}/{name}";
            if (download.GetProperty("url").GetString() != expectedUrl) throw new InvalidDataException("Unexpected Velocity download URL.");
            var javaVersion = await javaVersionProbe(paths.JavaExecutable(server), cancellationToken);
            if (javaVersion < minimumJava) throw new InvalidDataException("Registered Java runtime is too old for the selected Velocity build.");
            if (sha == previousSha) return new(version, build, channel, minimumJava, relative, sha!, previousSha, null, null);
            var baseVersion = VelocityBaseVersion(version);
            var installedBuild = installed.Build;
            if (baseVersion == installed.Version && installedBuild is null)
                installedBuild = builds.RootElement.EnumerateArray().Where(x =>
                    x.TryGetProperty("downloads", out var oldDownloads) && oldDownloads.TryGetProperty("server:default", out var oldDownload) &&
                    oldDownload.GetProperty("checksums").GetProperty("sha256").GetString() == previousSha)
                    .Select(x => (int?)x.GetProperty("id").GetInt32()).SingleOrDefault();
            if (baseVersion < installed.Version || baseVersion == installed.Version && (installedBuild is null || build < installedBuild))
                throw new InvalidDataException("Automatic Velocity downgrade is not allowed.");
            EnsureFreeSpace(target, MaxVelocityJarBytes * 2);
            var stage = VelocityStagePath(server, operationId);
            if (File.Exists(stage)) throw new IOException("Velocity update stage already exists.");
            try
            {
                using var downloadTimeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
                downloadTimeout.CancelAfter(TimeSpan.FromMinutes(3));
                using var response = await GetVelocityResponseAsync(new Uri(expectedUrl), metadata: false, downloadTimeout.Token);
                if (response.Content.Headers.ContentLength is long responseSize && responseSize != size) throw new InvalidDataException("Velocity jar size mismatch.");
                await using (var input = await response.Content.ReadAsStreamAsync(downloadTimeout.Token))
                await using (var output = new FileStream(stage, FileMode.CreateNew, FileAccess.Write, FileShare.None, 128 * 1024, FileOptions.WriteThrough | FileOptions.Asynchronous))
                {
                    var buffer = new byte[128 * 1024];
                    long length = 0;
                    while (true)
                    {
                        var read = await input.ReadAsync(buffer, downloadTimeout.Token);
                        if (read == 0) break;
                        length = checked(length + read);
                        if (length > size) throw new InvalidDataException("Velocity jar exceeds expected size.");
                        await output.WriteAsync(buffer.AsMemory(0, read), downloadTimeout.Token);
                    }
                    output.Flush(true);
                    if (length != size) throw new InvalidDataException("Velocity jar size mismatch.");
                }
                if (await Sha256Async(stage, downloadTimeout.Token) != sha) throw new InvalidDataException("Velocity jar checksum mismatch.");
                var offered = ReadVelocityIdentity(stage);
                if (offered.Version != baseVersion || offered.Build.HasValue && offered.Build != build) throw new InvalidDataException("Velocity jar identity mismatch.");
                return new(version, build, channel, minimumJava, relative, sha!, previousSha, stage,
                    relative + ".control-" + operationId.ToString("N") + ".backup");
            }
            catch
            {
                if (File.Exists(stage)) File.Delete(stage);
                throw;
            }
        }
        throw new InvalidDataException("No supported stable Velocity build is available.");
    }

    public async Task ApplyVelocityUpdateAsync(ServerConfig server, Guid operationId, VelocityPreparedUpdate prepared,
        Func<CancellationToken, Task> verifyGuard, CancellationToken cancellationToken = default)
    {
        RequireId(operationId);
        if (!prepared.Updated) throw new ArgumentException("No Velocity update is staged.");
        await gate.WaitAsync(cancellationToken);
        try
        {
            EnsureNoRecovery(server);
            var (target, relative) = VelocityJarPath(server);
            var stage = VelocityStagePath(server, operationId);
            if (relative != prepared.TargetRelativePath || stage != prepared.StagePath || !File.Exists(target) || !File.Exists(stage))
                throw new InvalidOperationException("Velocity update stage or target is missing.");
            if (await Sha256Async(target, cancellationToken) != prepared.PreviousSha256 || await Sha256Async(stage, cancellationToken) != prepared.Sha256)
                throw new FileConflictException("Velocity jar changed during update.");
            await Guard(verifyGuard, cancellationToken);
            _ = VelocityJarPath(server);
            StoragePaths.CheckNoReparse(stage);
            if (await Sha256Async(target, cancellationToken) != prepared.PreviousSha256 || await Sha256Async(stage, cancellationToken) != prepared.Sha256)
                throw new FileConflictException("Velocity jar changed during update.");
            var backup = target + ".control-" + operationId.ToString("N") + ".backup";
            StoragePaths.CheckNoReparse(backup);
            if (File.Exists(backup)) throw new IOException("Velocity backup already exists.");
            File.Replace(stage, target, backup);
        }
        finally { gate.Release(); }
    }

    public void DiscardVelocityStage(ServerConfig server, Guid operationId)
    {
        var stage = VelocityStagePath(server, operationId);
        if (File.Exists(stage)) File.Delete(stage);
    }

    private (string Path, string Relative) VelocityJarPath(ServerConfig server)
    {
        if (server.Kind != ServerKind.Velocity) throw new ArgumentException("Velocity updates require a Velocity server.");
        var jarArguments = server.ArgumentList.Select((value, index) => (value, index)).Where(x => x.value == "-jar").ToArray();
        if (jarArguments.Length != 1 || jarArguments[0].index + 1 >= server.ArgumentList.Length) throw new InvalidDataException("A single Velocity -jar argument is required.");
        var name = server.ArgumentList[jarArguments[0].index + 1];
        if (!Regex.IsMatch(name, @"\Avelocity(?:-[A-Za-z0-9.\-]+)?\.jar\z", RegexOptions.CultureInvariant))
            throw new InvalidDataException("Velocity jar must be an existing velocity*.jar file directly under the server root.");
        return (paths.Under(paths.Server(server), name), name);
    }

    private string VelocityStagePath(ServerConfig server, Guid operationId) => paths.Under(paths.Server(server), ".control-velocity-" + operationId.ToString("N") + ".tmp");

    private async Task<JsonDocument> ReadVelocityMetadataAsync(Uri uri, CancellationToken cancellationToken)
    {
        using var response = await GetVelocityResponseAsync(uri, metadata: true, cancellationToken);
        return JsonDocument.Parse(await ReadBoundedAsync(response, 1024 * 1024, cancellationToken));
    }

    private async Task<HttpResponseMessage> GetVelocityResponseAsync(Uri uri, bool metadata, CancellationToken cancellationToken)
    {
        if (uri.Scheme != "https" || uri.Port != 443 || !string.IsNullOrEmpty(uri.UserInfo) || !string.IsNullOrEmpty(uri.Query) || !string.IsNullOrEmpty(uri.Fragment) ||
            (metadata ? uri.Host != "fill.papermc.io" || !(uri.AbsolutePath == "/v3/projects/velocity" || uri.AbsolutePath.StartsWith("/v3/projects/velocity/versions/", StringComparison.Ordinal))
                : uri.Host != "fill-data.papermc.io" || !uri.AbsolutePath.StartsWith("/v1/objects/", StringComparison.Ordinal)))
            throw new InvalidDataException("Unexpected Velocity download host.");
        using var request = new HttpRequestMessage(HttpMethod.Get, uri);
        request.Headers.UserAgent.ParseAdd("AstralRecordControl/1.0 (+https://astralrecord.com)");
        var response = await geyserHttpClient.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancellationToken);
        // Do not follow redirects or broaden the official host/path boundary.
        if (!response.IsSuccessStatusCode || response.RequestMessage?.RequestUri is Uri actual && actual != uri)
        {
            response.Dispose();
            throw new HttpRequestException("Velocity download request failed.");
        }
        return response;
    }

    private static bool ValidVelocityVersion(string version) => version.Length is > 0 and <= 64 &&
        Regex.IsMatch(version, @"\A[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.\-]+)?\z", RegexOptions.CultureInvariant) &&
        Version.TryParse(version.Split('-')[0], out _);

    private static Version VelocityBaseVersion(string version) => Version.Parse(version.Split('-')[0]);

    private static (Version Version, int? Build) ReadVelocityIdentity(string path)
    {
        StoragePaths.CheckNoReparse(path);
        using var archive = ZipFile.OpenRead(path);
        var manifest = archive.GetEntry("META-INF/MANIFEST.MF") ?? throw new InvalidDataException("Velocity manifest is missing.");
        if (manifest.Length > 64 * 1024) throw new InvalidDataException("Velocity manifest exceeds limit.");
        using var reader = new StreamReader(manifest.Open());
        var text = reader.ReadToEnd().Replace("\r\n ", "", StringComparison.Ordinal);
        if (!Regex.IsMatch(text, @"(?m)^Main-Class: com\.velocitypowered\.proxy\.Velocity\r?$", RegexOptions.CultureInvariant) ||
            !Regex.IsMatch(text, @"(?m)^Implementation-Title: Velocity\r?$", RegexOptions.CultureInvariant))
            throw new InvalidDataException("Target jar is not Velocity.");
        var identity = Regex.Match(text, @"(?m)^Implementation-Version: ([0-9]+\.[0-9]+\.[0-9]+)(?:-[A-Za-z0-9.\-]+)?(?: \(git-[A-Za-z0-9]+-b([0-9]+)\))?\r?$", RegexOptions.CultureInvariant);
        if (!identity.Success)
            throw new InvalidDataException("Velocity jar version is not recognized.");
        int? build = identity.Groups[2].Success ? int.Parse(identity.Groups[2].Value, NumberStyles.None, CultureInfo.InvariantCulture) : null;
        return (Version.Parse(identity.Groups[1].Value), build);
    }

    private async Task<int> ProbeJavaVersionAsync(string executable, CancellationToken cancellationToken)
    {
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        timeout.CancelAfter(TimeSpan.FromSeconds(10));
        using var process = new Process { StartInfo = new ProcessStartInfo(executable)
        { UseShellExecute = false, RedirectStandardOutput = true, RedirectStandardError = true, CreateNoWindow = true } };
        var temporary = Data("java-probe-temp");
        Directory.CreateDirectory(temporary);
        StoragePaths.CheckNoReparse(temporary);
        process.StartInfo.Environment.Clear();
        if (OperatingSystem.IsWindows())
        {
            var windows = Environment.GetFolderPath(Environment.SpecialFolder.Windows);
            if (string.IsNullOrWhiteSpace(windows)) throw new InvalidOperationException("Windows directory is unavailable.");
            process.StartInfo.Environment["SystemRoot"] = windows;
            process.StartInfo.Environment["windir"] = windows;
        }
        process.StartInfo.Environment["TMP"] = temporary;
        process.StartInfo.Environment["TEMP"] = temporary;
        process.StartInfo.Environment["TMPDIR"] = temporary;
        process.StartInfo.ArgumentList.Add("-XshowSettings:properties");
        process.StartInfo.ArgumentList.Add("-version");
        if (!process.Start()) throw new InvalidOperationException("Could not inspect registered Java runtime.");
        try
        {
            var output = ReadProbeOutputAsync(process.StandardOutput, timeout.Token);
            var error = ReadProbeOutputAsync(process.StandardError, timeout.Token);
            await process.WaitForExitAsync(timeout.Token);
            var text = await output + "\n" + await error;
            var match = Regex.Match(text, @"(?m)^\s*java\.specification\.version = ([0-9]+)\s*$", RegexOptions.CultureInvariant);
            if (process.ExitCode != 0 || !match.Success || !int.TryParse(match.Groups[1].Value, CultureInfo.InvariantCulture, out var version))
                throw new InvalidDataException("Could not identify registered Java version.");
            return version;
        }
        catch
        {
            if (!process.HasExited) process.Kill(entireProcessTree: true);
            throw;
        }
    }

    private static async Task<string> ReadProbeOutputAsync(StreamReader reader, CancellationToken cancellationToken)
    {
        using var result = new StringWriter(CultureInfo.InvariantCulture);
        var buffer = new char[1024];
        while (true)
        {
            var read = await reader.ReadAsync(buffer.AsMemory(), cancellationToken);
            if (read == 0) return result.ToString();
            if (result.GetStringBuilder().Length + read > 16 * 1024) throw new InvalidDataException("Java version output exceeds limit.");
            result.Write(buffer, 0, read);
        }
    }
}
