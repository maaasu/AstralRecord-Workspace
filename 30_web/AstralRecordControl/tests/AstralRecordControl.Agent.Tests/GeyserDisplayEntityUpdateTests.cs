using System.IO.Compression;
using System.Net;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Nodes;
using AstralRecordControl.Agent.Storage;
using AstralRecordControl.Contracts;
using Xunit;

namespace AstralRecordControl.Agent.Tests;

public sealed class GeyserDisplayEntityUpdateTests
{
    private const string AssetUrl = "https://github.com/GeyserExtensionists/GeyserDisplayEntity/releases/download/latest/GeyserDisplayEntity-1.0.14.jar";
    private const string MainClass = "me.geyserextensionists.geyserdisplayentity.GeyserDisplayEntity";

    [Fact]
    public async Task VerifiedExtensionKeepsOriginalNameAndConfigAndPackAndCreatesNonJarBackup()
    {
        using var fixture = new Fixture();
        File.WriteAllText(Path.Combine(fixture.Directory, "config.yml"), "preserve config");
        File.WriteAllText(Path.Combine(fixture.Directory, "pack.mcpack"), "preserve pack");
        var previous = File.ReadAllBytes(fixture.Target);
        var operation = Guid.NewGuid();
        var prepared = await fixture.Storage.PrepareGeyserDisplayEntityUpdateAsync(fixture.Server, operation);
        Assert.True(prepared.Installed);
        Assert.True(prepared.Updated);
        Assert.Equal("1.0.14", prepared.Version);
        Assert.Equal(21, prepared.MinimumJava);
        Assert.Equal("plugins/Geyser-Velocity/extensions/custom-renamed.jar", prepared.TargetRelativePath);
        Assert.Equal(previous, File.ReadAllBytes(fixture.Target));
        var guardCalls = 0;
        await fixture.Storage.ApplyGeyserDisplayEntityUpdateAsync(fixture.Server, operation, prepared, _ => { guardCalls++; return Task.CompletedTask; });
        Assert.Equal(1, guardCalls);
        Assert.Equal(fixture.Client.Offered, File.ReadAllBytes(fixture.Target));
        Assert.False(File.Exists(prepared.StagePath));
        Assert.Equal(previous, File.ReadAllBytes(fixture.Target + ".control-" + operation.ToString("N") + ".backup"));
        Assert.Equal("preserve config", File.ReadAllText(Path.Combine(fixture.Directory, "config.yml")));
        Assert.Equal("preserve pack", File.ReadAllText(Path.Combine(fixture.Directory, "pack.mcpack")));
        Assert.Single(Directory.GetFiles(fixture.Directory, "*.jar"));
        Assert.All(fixture.Client.UserAgents, value => Assert.Contains("AstralRecordControl/", value));
        fixture.Storage.DiscardGeyserDisplayEntityStage(fixture.Server, operation);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task MissingExtensionIsSkippedWithoutNetworkOrCreatingDirectories(bool removeDirectory)
    {
        using var fixture = new Fixture();
        File.Delete(fixture.Target);
        if (removeDirectory) System.IO.Directory.Delete(fixture.Directory);
        var operation = Guid.NewGuid();
        var prepared = await fixture.Storage.PrepareGeyserDisplayEntityUpdateAsync(fixture.Server, operation);
        fixture.Storage.DiscardGeyserDisplayEntityStage(fixture.Server, operation);
        Assert.False(prepared.Installed);
        Assert.False(prepared.Updated);
        Assert.Empty(fixture.Client.Requests);
        if (removeDirectory) Assert.False(System.IO.Directory.Exists(fixture.Directory));
    }

    [Fact]
    public async Task SameVersionAndHashChecksJavaButDoesNotDownloadOrStage()
    {
        using var fixture = new Fixture(installedVersion: "1.0.14");
        var prepared = await fixture.Storage.PrepareGeyserDisplayEntityUpdateAsync(fixture.Server, Guid.NewGuid());
        Assert.True(prepared.Installed);
        Assert.False(prepared.Updated);
        Assert.Equal(21, prepared.MinimumJava);
        Assert.Single(fixture.Client.Requests);
        Assert.Equal(1, fixture.JavaProbes);
        Assert.Empty(Directory.GetFiles(fixture.Directory, ".control-*"));
    }

    [Fact]
    public async Task FabricAndPackAssetsAreIgnoredWhenSelectingTheNormalExtension()
    {
        using var fixture = new Fixture();
        fixture.Client.Metadata["assets"]!.AsArray().Add(new JsonObject { ["name"] = "GeyserDisplayEntity-fabric-1.0.13.jar" });
        fixture.Client.Metadata["assets"]!.AsArray().Add(new JsonObject { ["name"] = "GeyserDisplayEntityPack.mcpack" });
        var operation = Guid.NewGuid();
        var prepared = await fixture.Storage.PrepareGeyserDisplayEntityUpdateAsync(fixture.Server, operation);
        Assert.True(prepared.Updated);
        Assert.Equal(AssetUrl, fixture.Client.Requests[1].AbsoluteUri);
        fixture.Storage.DiscardGeyserDisplayEntityStage(fixture.Server, operation);
    }

    [Fact]
    public async Task TraversalAndReparseDirectoriesCannotBeUsedAsAnExtensionSource()
    {
        using var fixture = new Fixture();
        await Assert.ThrowsAsync<InvalidOperationException>(() => fixture.Storage.PrepareGeyserDisplayEntityUpdateAsync(
            fixture.Server with { ServerDirectory = "server/../outside" }, Guid.NewGuid()));
        var outside = fixture.Directory + "-outside";
        Directory.CreateDirectory(outside);
        File.WriteAllBytes(Path.Combine(outside, "GeyserDisplayEntity.jar"), ExtensionJar("1.0.12"));
        File.Delete(fixture.Target);
        Directory.Delete(fixture.Directory);
        try
        {
            Directory.CreateSymbolicLink(fixture.Directory, outside);
            await Assert.ThrowsAsync<InvalidOperationException>(() => fixture.Storage.PrepareGeyserDisplayEntityUpdateAsync(fixture.Server, Guid.NewGuid()));
            Assert.Empty(fixture.Client.Requests);
        }
        catch (UnauthorizedAccessException) { /* Windows without symlink privilege, as in existing storage tests. */ }
        finally { if (Directory.Exists(fixture.Directory)) Directory.Delete(fixture.Directory); }
    }

    [Theory]
    [InlineData("duplicate")]
    [InlineData("identity")]
    [InlineData("version")]
    [InlineData("invalidJar")]
    [InlineData("descriptor")]
    public async Task AmbiguousOrMalformedInstallationFailsBeforeNetwork(string failure)
    {
        using var fixture = new Fixture();
        switch (failure)
        {
            case "duplicate": File.Copy(fixture.Target, Path.Combine(fixture.Directory, "other.jar")); break;
            case "identity": File.WriteAllBytes(fixture.Target, BuildJar("1.0.12", id: "wrong-id")); break;
            case "version": File.WriteAllBytes(fixture.Target, BuildJar("1.0.12-SNAPSHOT")); break;
            case "invalidJar": File.WriteAllText(fixture.Target, "not a jar"); break;
            case "descriptor": File.WriteAllBytes(fixture.Target, BuildJar("1.0.12", duplicateDescriptor: true)); break;
        }
        await Assert.ThrowsAsync<InvalidDataException>(() => fixture.Storage.PrepareGeyserDisplayEntityUpdateAsync(fixture.Server, Guid.NewGuid()));
        Assert.Empty(fixture.Client.Requests);
    }

    [Fact]
    public async Task UnrelatedExtensionAndFilenameDoNotClaimAnInstallation()
    {
        using var fixture = new Fixture();
        File.Delete(fixture.Target);
        File.WriteAllBytes(Path.Combine(fixture.Directory, "GeyserDisplayEntity.jar"), BuildJar("1.0.12", "other", "Other Extension", "example.Other"));
        var prepared = await fixture.Storage.PrepareGeyserDisplayEntityUpdateAsync(fixture.Server, Guid.NewGuid());
        Assert.False(prepared.Installed);
        Assert.Empty(fixture.Client.Requests);
    }

    [Theory]
    [InlineData("draft")]
    [InlineData("prerelease")]
    [InlineData("tag")]
    [InlineData("digest")]
    [InlineData("upperHash")]
    [InlineData("size")]
    [InlineData("url")]
    [InlineData("fabricOnly")]
    [InlineData("duplicateAsset")]
    [InlineData("unknownAsset")]
    [InlineData("duplicateProperty")]
    public async Task UnsafeMetadataIsRejectedBeforeDownload(string failure)
    {
        using var fixture = new Fixture();
        var metadata = fixture.Client.Metadata;
        var asset = metadata["assets"]![0]!;
        switch (failure)
        {
            case "draft": metadata["draft"] = true; break;
            case "prerelease": metadata["prerelease"] = true; break;
            case "tag": metadata["tag_name"] = "1.0.14"; break;
            case "digest": asset["digest"] = null; break;
            case "upperHash": asset["digest"] = "sha256:" + Hash(fixture.Client.Offered).ToUpperInvariant(); break;
            case "size": asset["size"] = 129L * 1024 * 1024; break;
            case "url": asset["browser_download_url"] = "https://evil.example/extension.jar"; break;
            case "fabricOnly": asset["name"] = "GeyserDisplayEntity-fabric-1.0.14.jar"; break;
            case "duplicateAsset": metadata["assets"]!.AsArray().Add(asset.DeepClone()); break;
            case "unknownAsset": metadata["assets"]!.AsArray().Add(new JsonObject { ["name"] = "GeyserDisplayEntity-unknown.jar" }); break;
            case "duplicateProperty": fixture.Client.RawMetadata = metadata.ToJsonString().Replace("\"draft\":false", "\"draft\":true,\"draft\":false", StringComparison.Ordinal); break;
        }
        await Assert.ThrowsAnyAsync<Exception>(() => fixture.Storage.PrepareGeyserDisplayEntityUpdateAsync(fixture.Server, Guid.NewGuid()));
        Assert.Single(fixture.Client.Requests);
        Assert.Empty(Directory.GetFiles(fixture.Directory, ".control-*"));
    }

    [Theory]
    [InlineData("1.0.15", false)]
    [InlineData("1.0.14", true)]
    public async Task DowngradeAndSameVersionDifferentHashAreRejected(string installedVersion, bool changeBytes)
    {
        using var fixture = new Fixture(installedVersion: installedVersion);
        if (changeBytes) File.WriteAllBytes(fixture.Target, BuildJar(installedVersion, extraClass: true));
        await Assert.ThrowsAsync<InvalidDataException>(() => fixture.Storage.PrepareGeyserDisplayEntityUpdateAsync(fixture.Server, Guid.NewGuid()));
        Assert.Single(fixture.Client.Requests);
    }

    [Theory]
    [InlineData("checksum")]
    [InlineData("size")]
    [InlineData("version")]
    [InlineData("identity")]
    [InlineData("class")]
    [InlineData("preview")]
    [InlineData("java")]
    public async Task BadDownloadedJarAndUnsupportedJavaAreRejectedAndStageRemoved(string failure)
    {
        using var fixture = new Fixture(java: failure == "java" ? 17 : 21);
        switch (failure)
        {
            case "checksum": fixture.Client.Download = fixture.Client.Offered.Select(x => (byte)(x ^ 1)).ToArray(); break;
            case "size": fixture.Client.Download = [1, 2, 3]; break;
            case "version": fixture.Client.SetOffered(BuildJar("1.0.13")); break;
            case "identity": fixture.Client.SetOffered(BuildJar("1.0.14", id: "unexpected")); break;
            case "class": fixture.Client.SetOffered(BuildJar("1.0.14", omitMain: true)); break;
            case "preview": fixture.Client.SetOffered(BuildJar("1.0.14", preview: true)); break;
        }
        var original = File.ReadAllBytes(fixture.Target);
        await Assert.ThrowsAnyAsync<Exception>(() => fixture.Storage.PrepareGeyserDisplayEntityUpdateAsync(fixture.Server, Guid.NewGuid()));
        Assert.Equal(original, File.ReadAllBytes(fixture.Target));
        Assert.Empty(Directory.GetFiles(fixture.Directory, ".control-*"));
    }

    [Theory]
    [InlineData("https://evil.example/file.jar")]
    [InlineData("http://release-assets.githubusercontent.com/github-production-release-asset/123/abc")]
    [InlineData("https://release-assets.githubusercontent.com/wrong/path?secret=signed")]
    [InlineData("https://github.com/Other/Repository/releases/download/latest/file.jar")]
    public async Task UnsafeRedirectIsRejectedWithoutFollowingIt(string url)
    {
        using var fixture = new Fixture();
        fixture.Client.Redirect = url;
        await Assert.ThrowsAsync<InvalidDataException>(() => fixture.Storage.PrepareGeyserDisplayEntityUpdateAsync(fixture.Server, Guid.NewGuid()));
        Assert.Equal(2, fixture.Client.Requests.Count);
        Assert.Empty(Directory.GetFiles(fixture.Directory, ".control-*"));
    }

    [Fact]
    public async Task OfficialAssetRedirectIsAllowedAndSignedRequestFailureDoesNotExposeUrl()
    {
        using var fixture = new Fixture();
        fixture.Client.Redirect = "https://release-assets.githubusercontent.com/github-production-release-asset/123/abcdef-123?secret=signed-value";
        var operation = Guid.NewGuid();
        var prepared = await fixture.Storage.PrepareGeyserDisplayEntityUpdateAsync(fixture.Server, operation);
        Assert.True(prepared.Updated);
        Assert.Equal(3, fixture.Client.Requests.Count);
        fixture.Storage.DiscardGeyserDisplayEntityStage(fixture.Server, operation);
        fixture.Client.ThrowSignedRequestFailure = true;
        var error = await Assert.ThrowsAsync<HttpRequestException>(() => fixture.Storage.PrepareGeyserDisplayEntityUpdateAsync(fixture.Server, Guid.NewGuid()));
        Assert.DoesNotContain("signed-value", error.ToString());
    }

    [Theory]
    [InlineData("target", false)]
    [InlineData("stage", false)]
    [InlineData("target", true)]
    [InlineData("stage", true)]
    [InlineData("duplicate", true)]
    public async Task DriftBeforeOrInsideGuardCannotReplaceInstalledJar(string changed, bool insideGuard)
    {
        using var fixture = new Fixture();
        var operation = Guid.NewGuid();
        var prepared = await fixture.Storage.PrepareGeyserDisplayEntityUpdateAsync(fixture.Server, operation);
        void Change()
        {
            if (changed == "duplicate") File.Copy(fixture.Target, Path.Combine(fixture.Directory, "duplicate.jar"));
            else File.WriteAllBytes(changed == "target" ? fixture.Target : prepared.StagePath!, ExtensionJar("1.0.13"));
        }
        if (!insideGuard) Change();
        await Assert.ThrowsAnyAsync<Exception>(() => fixture.Storage.ApplyGeyserDisplayEntityUpdateAsync(fixture.Server, operation, prepared, _ =>
        { if (insideGuard) Change(); return Task.CompletedTask; }));
        Assert.False(File.Exists(fixture.Target + ".control-" + operation.ToString("N") + ".backup"));
        fixture.Storage.DiscardGeyserDisplayEntityStage(fixture.Server, operation);
        Assert.False(File.Exists(prepared.StagePath));
    }

    [Fact]
    public async Task GuardFailureAndExistingBackupCannotReplaceJarAndCleanupDoesNotParseBrokenInstallation()
    {
        using var fixture = new Fixture();
        var operation = Guid.NewGuid();
        var prepared = await fixture.Storage.PrepareGeyserDisplayEntityUpdateAsync(fixture.Server, operation);
        var original = File.ReadAllBytes(fixture.Target);
        await Assert.ThrowsAsync<InvalidOperationException>(() => fixture.Storage.ApplyGeyserDisplayEntityUpdateAsync(fixture.Server, operation, prepared,
            _ => throw new InvalidOperationException("guard rejected")));
        File.WriteAllText(fixture.Target + ".control-" + operation.ToString("N") + ".backup", "existing backup");
        await Assert.ThrowsAsync<IOException>(() => fixture.Storage.ApplyGeyserDisplayEntityUpdateAsync(fixture.Server, operation, prepared, _ => Task.CompletedTask));
        Assert.Equal(original, File.ReadAllBytes(fixture.Target));
        File.WriteAllText(fixture.Target, "broken jar");
        fixture.Storage.DiscardGeyserDisplayEntityStage(fixture.Server, operation);
        Assert.False(File.Exists(prepared.StagePath));
        Assert.Equal("existing backup", File.ReadAllText(fixture.Target + ".control-" + operation.ToString("N") + ".backup"));
    }

    public static byte[] ExtensionJar(string version) => BuildJar(version);

    private static byte[] BuildJar(string version, string id = "geyserdisplayentity", string name = "GeyserDisplayEntity", string main = MainClass,
        bool duplicateDescriptor = false, bool omitMain = false, bool extraClass = false, bool preview = false)
    {
        using var bytes = new MemoryStream();
        using (var zip = new ZipArchive(bytes, ZipArchiveMode.Create, leaveOpen: true))
        {
            void Add(string path, byte[] content)
            {
                var entry = zip.CreateEntry(path, CompressionLevel.NoCompression);
                entry.LastWriteTime = new DateTimeOffset(2024, 1, 1, 0, 0, 0, TimeSpan.Zero);
                using var output = entry.Open();
                output.Write(content);
            }
            var descriptor = Encoding.UTF8.GetBytes($"name: {name}\nid: {id}\nmain: {main}\napi: 2.7.0\nversion: \"{version}\"\nauthors:\n  - test\n");
            Add("extension.yml", descriptor);
            if (duplicateDescriptor) Add("extension.yml", descriptor);
            byte[] classHeader = [0xca, 0xfe, 0xba, 0xbe, preview ? (byte)0xff : (byte)0, preview ? (byte)0xff : (byte)0, 0, 65];
            if (!omitMain) Add(main.Replace('.', '/') + ".class", classHeader);
            if (extraClass) Add("example/Additional.class", classHeader);
        }
        return bytes.ToArray();
    }

    private static string Hash(byte[] value) => Convert.ToHexStringLower(SHA256.HashData(value));

    private sealed class Fixture : IDisposable
    {
        private readonly string root = Path.Combine(Path.GetTempPath(), "control-displayentity-" + Guid.NewGuid().ToString("N"));
        public string Directory => Path.Combine(root, "managed", "server", "plugins", "Geyser-Velocity", "extensions");
        public string Target => Path.Combine(Directory, "custom-renamed.jar");
        public ServerConfig Server { get; }
        public FakeHandler Client { get; } = new();
        public P2StorageService Storage { get; }
        public int JavaProbes { get; private set; }
        public Fixture(string installedVersion = "1.0.12", int java = 21)
        {
            System.IO.Directory.CreateDirectory(Directory);
            File.WriteAllBytes(Target, ExtensionJar(installedVersion));
            var executable = Path.Combine(root, "java.exe");
            File.WriteAllText(executable, "fixture only");
            var rootId = Guid.NewGuid(); var executableId = Guid.NewGuid();
            Server = new ServerConfig { HostId = Guid.NewGuid(), ServerId = Guid.NewGuid(), Revision = 1, Kind = ServerKind.Velocity,
                ManagedRootId = rootId, ServerDirectory = "server", ExecutableId = executableId };
            Storage = new P2StorageService(Path.Combine(root, "agent"), new HostResources
            {
                Roots = [new ManagedRoot { Id = rootId, Path = Path.Combine(root, "managed") }],
                Executables = [new ManagedExecutable { Id = executableId, Kind = ServerKind.Java, Path = executable }]
            }, availableBytesProbe: _ => long.MaxValue, geyserHttpClient: new HttpClient(Client),
                javaVersionProbe: (_, _) => { JavaProbes++; return Task.FromResult(java); });
        }
        public void Dispose() => System.IO.Directory.Delete(root, true);
    }

    private sealed class FakeHandler : HttpMessageHandler
    {
        public byte[] Offered { get; private set; } = ExtensionJar("1.0.14");
        public byte[]? Download { get; set; }
        public JsonObject Metadata { get; private set; } = null!;
        public string? RawMetadata { get; set; }
        public string? Redirect { get; set; }
        public bool ThrowSignedRequestFailure { get; set; }
        public List<Uri> Requests { get; } = [];
        public List<string> UserAgents { get; } = [];
        public FakeHandler() => SetOffered(Offered);
        public void SetOffered(byte[] offered)
        {
            Offered = offered;
            Metadata = new JsonObject { ["draft"] = false, ["prerelease"] = false, ["tag_name"] = "latest", ["assets"] = new JsonArray
            {
                new JsonObject { ["name"] = "GeyserDisplayEntity-1.0.14.jar", ["size"] = offered.Length, ["digest"] = "sha256:" + Hash(offered), ["browser_download_url"] = AssetUrl }
            } };
        }
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            Requests.Add(request.RequestUri!);
            UserAgents.Add(request.Headers.UserAgent.ToString());
            if (request.RequestUri!.Host == "api.github.com")
                return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent(RawMetadata ?? Metadata.ToJsonString()) });
            if (request.RequestUri.Host == "github.com" && Redirect is not null)
            {
                var response = new HttpResponseMessage(HttpStatusCode.Found);
                response.Headers.Location = new Uri(Redirect);
                return Task.FromResult(response);
            }
            if (ThrowSignedRequestFailure) throw new HttpRequestException("failure on " + request.RequestUri);
            return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK) { Content = new ByteArrayContent(Download ?? Offered) });
        }
    }
}
