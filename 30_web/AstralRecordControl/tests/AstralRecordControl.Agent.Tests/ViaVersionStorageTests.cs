using System.IO.Compression;
using System.Net;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using AstralRecordControl.Agent.Storage;
using AstralRecordControl.Contracts;
using Xunit;

namespace AstralRecordControl.Agent.Tests;

public sealed class ViaVersionStorageTests
{
    private const string AssetUrl = "https://github.com/ViaVersion/ViaVersion/releases/download/5.12.2/ViaVersion-5.12.2.jar";
    private const string MainClass = "com.viaversion.viaversion.VelocityPlugin";

    [Fact]
    public async Task VerifiedPluginKeepsOriginalNameAndConfigAndPackAndCreatesNonJarBackup()
    {
        using var fixture = new Fixture();
        File.WriteAllText(Path.Combine(fixture.Directory, "config.yml"), "preserve config");
        File.WriteAllText(Path.Combine(fixture.Directory, "pack.mcpack"), "preserve pack");
        var previous = File.ReadAllBytes(fixture.Target);
        var operation = Guid.NewGuid();
        var prepared = await fixture.Storage.PrepareViaVersionUpdateAsync(fixture.Server, operation);
        Assert.True(prepared.Installed);
        Assert.True(prepared.Updated);
        Assert.Equal("5.12.2", prepared.Version);
        Assert.Equal(17, prepared.MinimumJava);
        Assert.Equal("plugins/custom-renamed.jar", prepared.TargetRelativePath);
        Assert.Equal(previous, File.ReadAllBytes(fixture.Target));
        var guardCalls = 0;
        await fixture.Storage.ApplyViaVersionUpdateAsync(fixture.Server, operation, prepared, _ => { guardCalls++; return Task.CompletedTask; });
        Assert.Equal(1, guardCalls);
        Assert.Equal(fixture.Client.Offered, File.ReadAllBytes(fixture.Target));
        Assert.False(File.Exists(prepared.StagePath));
        Assert.Equal(previous, File.ReadAllBytes(fixture.Target + ".control-" + operation.ToString("N") + ".backup"));
        Assert.Equal("preserve config", File.ReadAllText(Path.Combine(fixture.Directory, "config.yml")));
        Assert.Equal("preserve pack", File.ReadAllText(Path.Combine(fixture.Directory, "pack.mcpack")));
        Assert.Single(Directory.GetFiles(fixture.Directory, "*.jar"));
        Assert.All(fixture.Client.UserAgents, value => Assert.Contains("AstralRecordControl/", value));
        fixture.Storage.DiscardViaVersionStage(fixture.Server, operation);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task MissingPluginIsSkippedWithoutNetworkOrCreatingDirectories(bool removeDirectory)
    {
        using var fixture = new Fixture();
        File.Delete(fixture.Target);
        if (removeDirectory) System.IO.Directory.Delete(fixture.Directory);
        var operation = Guid.NewGuid();
        var prepared = await fixture.Storage.PrepareViaVersionUpdateAsync(fixture.Server, operation);
        fixture.Storage.DiscardViaVersionStage(fixture.Server, operation);
        Assert.False(prepared.Installed);
        Assert.False(prepared.Updated);
        Assert.Empty(fixture.Client.Requests);
        if (removeDirectory) Assert.False(System.IO.Directory.Exists(fixture.Directory));
    }

    [Fact]
    public async Task SameVersionAndHashChecksJavaButDoesNotDownloadOrStage()
    {
        using var fixture = new Fixture(installedVersion: "5.12.2");
        var prepared = await fixture.Storage.PrepareViaVersionUpdateAsync(fixture.Server, Guid.NewGuid());
        Assert.True(prepared.Installed);
        Assert.False(prepared.Updated);
        Assert.Equal(17, prepared.MinimumJava);
        Assert.Single(fixture.Client.Requests);
        Assert.Equal(1, fixture.JavaProbes);
        Assert.Empty(Directory.GetFiles(fixture.Directory, ".control-*"));
    }

    [Fact]
    public async Task SourcesAndJavadocAssetsDoNotChangeBinarySelection()
    {
        using var fixture = new Fixture();
        fixture.Client.Metadata["assets"]!.AsArray().Add(new JsonObject { ["name"] = "ViaVersion-5.12.2-sources.jar" });
        fixture.Client.Metadata["assets"]!.AsArray().Add(new JsonObject { ["name"] = "ViaVersion-5.12.2-javadoc.jar" });
        var operation = Guid.NewGuid();
        var prepared = await fixture.Storage.PrepareViaVersionUpdateAsync(fixture.Server, operation);
        Assert.True(prepared.Updated);
        fixture.Storage.DiscardViaVersionStage(fixture.Server, operation);
    }

    [Fact]
    public async Task TraversalAndReparseDirectoriesCannotBeUsedAsAPluginSource()
    {
        using var fixture = new Fixture();
        await Assert.ThrowsAsync<InvalidOperationException>(() => fixture.Storage.PrepareViaVersionUpdateAsync(
            fixture.Server with { ServerDirectory = "server/../outside" }, Guid.NewGuid()));
        var outside = fixture.Directory + "-outside";
        Directory.CreateDirectory(outside);
        File.WriteAllBytes(Path.Combine(outside, "ViaVersion.jar"), PluginJar("5.11.0"));
        File.Delete(fixture.Target);
        Directory.Delete(fixture.Directory);
        try
        {
            Directory.CreateSymbolicLink(fixture.Directory, outside);
            await Assert.ThrowsAsync<InvalidOperationException>(() => fixture.Storage.PrepareViaVersionUpdateAsync(fixture.Server, Guid.NewGuid()));
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
            case "identity": File.WriteAllBytes(fixture.Target, BuildJar("5.11.0", id: "wrong-id")); break;
            case "version": File.WriteAllBytes(fixture.Target, BuildJar("5.11.0-SNAPSHOT")); break;
            case "invalidJar": File.WriteAllText(fixture.Target, "not a jar"); break;
            case "descriptor": File.WriteAllBytes(fixture.Target, BuildJar("5.11.0", duplicateDescriptor: true)); break;
        }
        await Assert.ThrowsAsync<InvalidDataException>(() => fixture.Storage.PrepareViaVersionUpdateAsync(fixture.Server, Guid.NewGuid()));
        Assert.Empty(fixture.Client.Requests);
    }

    [Fact]
    public async Task UnrelatedPluginDoNotClaimAnInstallation()
    {
        using var fixture = new Fixture();
        File.Delete(fixture.Target);
        File.WriteAllBytes(Path.Combine(fixture.Directory, "Unrelated.jar"), BuildJar("5.11.0", "other", "Other Extension", "example.Other"));
        var prepared = await fixture.Storage.PrepareViaVersionUpdateAsync(fixture.Server, Guid.NewGuid());
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
    [InlineData("mismatchedAsset")]
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
            case "tag": metadata["tag_name"] = "latest"; break;
            case "digest": asset["digest"] = null; break;
            case "upperHash": asset["digest"] = "sha256:" + Hash(fixture.Client.Offered).ToUpperInvariant(); break;
            case "size": asset["size"] = 129L * 1024 * 1024; break;
            case "url": asset["browser_download_url"] = "https://evil.example/extension.jar"; break;
            case "fabricOnly": asset["name"] = "ViaVersion-fabric-5.12.2.jar"; break;
            case "duplicateAsset": metadata["assets"]!.AsArray().Add(asset.DeepClone()); break;
            case "mismatchedAsset": asset["name"] = "ViaVersion-5.12.1.jar"; break;
            case "duplicateProperty": fixture.Client.RawMetadata = metadata.ToJsonString().Replace("\"draft\":false", "\"draft\":true,\"draft\":false", StringComparison.Ordinal); break;
        }
        await Assert.ThrowsAnyAsync<Exception>(() => fixture.Storage.PrepareViaVersionUpdateAsync(fixture.Server, Guid.NewGuid()));
        Assert.Single(fixture.Client.Requests);
        Assert.Empty(Directory.GetFiles(fixture.Directory, ".control-*"));
    }

    [Theory]
    [InlineData("5.12.3", false)]
    [InlineData("5.12.2", true)]
    public async Task DowngradeAndSameVersionDifferentHashAreRejected(string installedVersion, bool changeBytes)
    {
        using var fixture = new Fixture(installedVersion: installedVersion);
        if (changeBytes) File.WriteAllBytes(fixture.Target, BuildJar(installedVersion, extraClass: true));
        await Assert.ThrowsAsync<InvalidDataException>(() => fixture.Storage.PrepareViaVersionUpdateAsync(fixture.Server, Guid.NewGuid()));
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
        using var fixture = new Fixture(java: failure == "java" ? 16 : 21);
        switch (failure)
        {
            case "checksum": fixture.Client.Download = fixture.Client.Offered.Select(x => (byte)(x ^ 1)).ToArray(); break;
            case "size": fixture.Client.Download = [1, 2, 3]; break;
            case "version": fixture.Client.SetOffered(BuildJar("5.12.1")); break;
            case "identity": fixture.Client.SetOffered(BuildJar("5.12.2", id: "unexpected")); break;
            case "class": fixture.Client.SetOffered(BuildJar("5.12.2", omitMain: true)); break;
            case "preview": fixture.Client.SetOffered(BuildJar("5.12.2", preview: true)); break;
        }
        var original = File.ReadAllBytes(fixture.Target);
        await Assert.ThrowsAnyAsync<Exception>(() => fixture.Storage.PrepareViaVersionUpdateAsync(fixture.Server, Guid.NewGuid()));
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
        await Assert.ThrowsAsync<InvalidDataException>(() => fixture.Storage.PrepareViaVersionUpdateAsync(fixture.Server, Guid.NewGuid()));
        Assert.Equal(2, fixture.Client.Requests.Count);
        Assert.Empty(Directory.GetFiles(fixture.Directory, ".control-*"));
    }

    [Fact]
    public async Task OfficialAssetRedirectIsAllowedAndSignedRequestFailureDoesNotExposeUrl()
    {
        using var fixture = new Fixture();
        fixture.Client.Redirect = "https://release-assets.githubusercontent.com/github-production-release-asset/123/abcdef-123?secret=signed-value";
        var operation = Guid.NewGuid();
        var prepared = await fixture.Storage.PrepareViaVersionUpdateAsync(fixture.Server, operation);
        Assert.True(prepared.Updated);
        Assert.Equal(3, fixture.Client.Requests.Count);
        fixture.Storage.DiscardViaVersionStage(fixture.Server, operation);
        fixture.Client.ThrowSignedRequestFailure = true;
        var error = await Assert.ThrowsAsync<HttpRequestException>(() => fixture.Storage.PrepareViaVersionUpdateAsync(fixture.Server, Guid.NewGuid()));
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
        var prepared = await fixture.Storage.PrepareViaVersionUpdateAsync(fixture.Server, operation);
        void Change()
        {
            if (changed == "duplicate") File.Copy(fixture.Target, Path.Combine(fixture.Directory, "duplicate.jar"));
            else File.WriteAllBytes(changed == "target" ? fixture.Target : prepared.StagePath!, PluginJar("5.12.1"));
        }
        if (!insideGuard) Change();
        await Assert.ThrowsAnyAsync<Exception>(() => fixture.Storage.ApplyViaVersionUpdateAsync(fixture.Server, operation, prepared, _ =>
        { if (insideGuard) Change(); return Task.CompletedTask; }));
        Assert.False(File.Exists(fixture.Target + ".control-" + operation.ToString("N") + ".backup"));
        fixture.Storage.DiscardViaVersionStage(fixture.Server, operation);
        Assert.False(File.Exists(prepared.StagePath));
    }

    [Fact]
    public async Task GuardFailureAndExistingBackupCannotReplaceJarAndCleanupDoesNotParseBrokenInstallation()
    {
        using var fixture = new Fixture();
        var operation = Guid.NewGuid();
        var prepared = await fixture.Storage.PrepareViaVersionUpdateAsync(fixture.Server, operation);
        var original = File.ReadAllBytes(fixture.Target);
        await Assert.ThrowsAsync<InvalidOperationException>(() => fixture.Storage.ApplyViaVersionUpdateAsync(fixture.Server, operation, prepared,
            _ => throw new InvalidOperationException("guard rejected")));
        File.WriteAllText(fixture.Target + ".control-" + operation.ToString("N") + ".backup", "existing backup");
        await Assert.ThrowsAsync<IOException>(() => fixture.Storage.ApplyViaVersionUpdateAsync(fixture.Server, operation, prepared, _ => Task.CompletedTask));
        Assert.Equal(original, File.ReadAllBytes(fixture.Target));
        File.WriteAllText(fixture.Target, "broken jar");
        fixture.Storage.DiscardViaVersionStage(fixture.Server, operation);
        Assert.False(File.Exists(prepared.StagePath));
        Assert.Equal("existing backup", File.ReadAllText(fixture.Target + ".control-" + operation.ToString("N") + ".backup"));
    }

    [Fact]
    public async Task SameHashStillRejectsUnsupportedJavaBeforeStaging()
    {
        using var fixture = new Fixture(installedVersion: "5.12.2", java: 16);
        await Assert.ThrowsAsync<InvalidDataException>(() => fixture.Storage.PrepareViaVersionUpdateAsync(fixture.Server, Guid.NewGuid()));
        Assert.Single(fixture.Client.Requests);
        Assert.Equal(1, fixture.JavaProbes);
        Assert.Empty(Directory.GetFiles(fixture.Directory, ".control-*"));
    }

    [Theory]
    [InlineData("v5.12.2")]
    [InlineData("05.12.2")]
    [InlineData("5.12.2-SNAPSHOT")]
    [InlineData("5.12.2/extra")]
    public async Task ReleaseTagMustBeCanonicalStableVersion(string tag)
    {
        using var fixture = new Fixture();
        fixture.Client.Metadata["tag_name"] = tag;
        await Assert.ThrowsAsync<InvalidDataException>(() => fixture.Storage.PrepareViaVersionUpdateAsync(fixture.Server, Guid.NewGuid()));
        Assert.Single(fixture.Client.Requests);
    }

    [Fact]
    public async Task ViaVersionFilenameCannotSubstituteAnUnrelatedIdentity()
    {
        using var fixture = new Fixture();
        File.Delete(fixture.Target);
        File.WriteAllBytes(Path.Combine(fixture.Directory, "ViaVersion-5.11.0.jar"), BuildJar("5.11.0", id: "other", main: "example.Other"));
        await Assert.ThrowsAsync<InvalidDataException>(() => fixture.Storage.PrepareViaVersionUpdateAsync(fixture.Server, Guid.NewGuid()));
        Assert.Empty(fixture.Client.Requests);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task PreexistingOrRacingStageIsNeverRemoved(bool duringDownload)
    {
        using var fixture = new Fixture();
        var operation = Guid.NewGuid();
        var stage = Path.Combine(fixture.Directory, ".control-viaversion-" + operation.ToString("N") + ".tmp");
        void Occupy() => File.WriteAllText(stage, "another attempt owns this stage");
        if (duringDownload) fixture.Client.BeforeDownload = Occupy;
        else Occupy();
        var original = File.ReadAllBytes(fixture.Target);
        await Assert.ThrowsAsync<IOException>(() => fixture.Storage.PrepareViaVersionUpdateAsync(fixture.Server, operation));
        Assert.Equal("another attempt owns this stage", File.ReadAllText(stage));
        Assert.Equal(original, File.ReadAllBytes(fixture.Target));
    }

    [Theory]
    [InlineData("metadataRedirect")]
    [InlineData("automaticRedirect")]
    [InlineData("unauthorized")]
    public async Task MetadataRedirectAndAuthenticationFailureAreNotFollowed(string failure)
    {
        using var fixture = new Fixture();
        fixture.Client.MetadataFailure = failure;
        await Assert.ThrowsAnyAsync<Exception>(() => fixture.Storage.PrepareViaVersionUpdateAsync(fixture.Server, Guid.NewGuid()));
        Assert.Single(fixture.Client.Requests);
        Assert.Empty(Directory.GetFiles(fixture.Directory, ".control-*"));
    }

    [Theory]
    [InlineData("stage")]
    [InlineData("backup")]
    public async Task ApplyRejectsStageOrBackupReparseInsertedByGuard(string changed)
    {
        using var fixture = new Fixture();
        var operation = Guid.NewGuid();
        var prepared = await fixture.Storage.PrepareViaVersionUpdateAsync(fixture.Server, operation);
        var original = File.ReadAllBytes(fixture.Target);
        var outside = Path.Combine(fixture.Directory, "outside.bin");
        File.WriteAllBytes(outside, fixture.Client.Offered);
        var link = changed == "stage" ? prepared.StagePath! : fixture.Target + ".control-" + operation.ToString("N") + ".backup";
        try
        {
            await Assert.ThrowsAsync<InvalidOperationException>(() => fixture.Storage.ApplyViaVersionUpdateAsync(fixture.Server, operation, prepared, _ =>
            {
                if (File.Exists(link)) File.Delete(link);
                File.CreateSymbolicLink(link, outside);
                return Task.CompletedTask;
            }));
            Assert.Equal(original, File.ReadAllBytes(fixture.Target));
            Assert.Equal(fixture.Client.Offered, File.ReadAllBytes(outside));
        }
        catch (UnauthorizedAccessException) { /* Existing storage fixtures also allow Windows without link privilege. */ }
        finally { if (File.Exists(link)) File.Delete(link); }
    }

    public static byte[] PluginJar(string version, int java = 17) => BuildJar(version, java: java);

    private static byte[] BuildJar(string version, string id = "viaversion", string name = "ViaVersion", string main = MainClass,
        bool duplicateDescriptor = false, bool omitMain = false, bool extraClass = false, bool preview = false, int java = 17)
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
            var descriptor = JsonSerializer.SerializeToUtf8Bytes(new { id, name, main, version });
            Add("velocity-plugin.json", descriptor);
            if (duplicateDescriptor) Add("velocity-plugin.json", descriptor);
            byte[] classHeader = [0xca, 0xfe, 0xba, 0xbe, preview ? (byte)0xff : (byte)0, preview ? (byte)0xff : (byte)0, (byte)((java + 44) >> 8), (byte)(java + 44)];
            if (!omitMain) Add(main.Replace('.', '/') + ".class", classHeader);
            if (extraClass) Add("example/Additional.class", classHeader);
        }
        return bytes.ToArray();
    }

    private static string Hash(byte[] value) => Convert.ToHexStringLower(SHA256.HashData(value));

    private sealed class Fixture : IDisposable
    {
        private readonly string root = Path.Combine(Path.GetTempPath(), "control-viaversion-" + Guid.NewGuid().ToString("N"));
        public string Directory => Path.Combine(root, "managed", "server", "plugins");
        public string Target => Path.Combine(Directory, "custom-renamed.jar");
        public ServerConfig Server { get; }
        public FakeHandler Client { get; } = new();
        public P2StorageService Storage { get; }
        public int JavaProbes { get; private set; }
        public Fixture(string installedVersion = "5.11.0", int java = 21)
        {
            System.IO.Directory.CreateDirectory(Directory);
            File.WriteAllBytes(Target, PluginJar(installedVersion));
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
        public byte[] Offered { get; private set; } = PluginJar("5.12.2");
        public byte[]? Download { get; set; }
        public JsonObject Metadata { get; private set; } = null!;
        public string? RawMetadata { get; set; }
        public string? Redirect { get; set; }
        public bool ThrowSignedRequestFailure { get; set; }
        public string? MetadataFailure { get; set; }
        public Action? BeforeDownload { get; set; }
        public List<Uri> Requests { get; } = [];
        public List<string> UserAgents { get; } = [];
        public FakeHandler() => SetOffered(Offered);
        public void SetOffered(byte[] offered)
        {
            Offered = offered;
            Metadata = new JsonObject { ["draft"] = false, ["prerelease"] = false, ["tag_name"] = "5.12.2", ["assets"] = new JsonArray
            {
                new JsonObject { ["name"] = "ViaVersion-5.12.2.jar", ["size"] = offered.Length, ["digest"] = "sha256:" + Hash(offered), ["browser_download_url"] = AssetUrl }
            } };
        }
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            Requests.Add(request.RequestUri!);
            UserAgents.Add(request.Headers.UserAgent.ToString());
            if (request.RequestUri!.Host == "api.github.com")
            {
                var metadataResponse = new HttpResponseMessage(MetadataFailure == "unauthorized" ? HttpStatusCode.Unauthorized :
                    MetadataFailure == "metadataRedirect" ? HttpStatusCode.Found : HttpStatusCode.OK)
                { Content = new StringContent(RawMetadata ?? Metadata.ToJsonString()) };
                if (MetadataFailure == "metadataRedirect") metadataResponse.Headers.Location = new Uri("https://github.com/login?secret=credential");
                if (MetadataFailure == "automaticRedirect") metadataResponse.RequestMessage = new HttpRequestMessage(HttpMethod.Get, "https://evil.example/metadata");
                return Task.FromResult(metadataResponse);
            }
            if (request.RequestUri.Host == "github.com" && Redirect is not null)
            {
                var response = new HttpResponseMessage(HttpStatusCode.Found);
                response.Headers.Location = new Uri(Redirect);
                return Task.FromResult(response);
            }
            if (ThrowSignedRequestFailure) throw new HttpRequestException("failure on " + request.RequestUri);
            BeforeDownload?.Invoke();
            return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK) { Content = new ByteArrayContent(Download ?? Offered) });
        }
    }
}
