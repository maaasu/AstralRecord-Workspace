using System.IO.Compression;
using System.Net;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using AstralRecordControl.Agent.Storage;
using AstralRecordControl.Contracts;
using Xunit;

namespace AstralRecordControl.Agent.Tests;

public sealed class VelocityStorageTests
{
    [Fact]
    public async Task StableArtifactIsVerifiedAndReplacesTheConfiguredJarWithoutRenamingIt()
    {
        using var fixture = new Fixture();
        var id = Guid.NewGuid();
        var prepared = await fixture.Storage.PrepareVelocityUpdateAsync(fixture.Server, id);
        Assert.True(prepared.Updated);
        Assert.Equal("4.3.1", prepared.Version);
        Assert.Equal(45, prepared.Build);
        Assert.Equal("STABLE", prepared.Channel);
        Assert.Equal(fixture.Installed, File.ReadAllBytes(fixture.JarPath));
        Assert.Equal(fixture.Client.Offered, File.ReadAllBytes(prepared.StagePath!));
        await fixture.Storage.ApplyVelocityUpdateAsync(fixture.Server, id, prepared, _ => Task.CompletedTask);
        Assert.Equal(fixture.Client.Offered, File.ReadAllBytes(fixture.JarPath));
        Assert.Equal(fixture.Installed, File.ReadAllBytes(fixture.JarPath + ".control-" + id.ToString("N") + ".backup"));
        Assert.Equal(["-Xmx2G", "-jar", "velocity-4.1.0-SNAPSHOT-8.jar"], fixture.Server.ArgumentList);
        Assert.All(fixture.Client.Requests, x => Assert.Contains("AstralRecordControl/", x.UserAgent));
        Assert.DoesNotContain(fixture.Client.Requests, x => x.Uri.Host == "fill-data.papermc.io" && x.Uri.AbsolutePath.Contains("SNAPSHOT", StringComparison.Ordinal));
    }

    [Theory]
    [InlineData("hash")]
    [InlineData("size")]
    [InlineData("host")]
    [InlineData("redirect")]
    [InlineData("identity")]
    public async Task BadMetadataOrDownloadLeavesCurrentJarAndNoStage(string failure)
    {
        using var fixture = new Fixture(failure: failure);
        await Assert.ThrowsAnyAsync<Exception>(() => fixture.Storage.PrepareVelocityUpdateAsync(fixture.Server, Guid.NewGuid()));
        Assert.Equal(fixture.Installed, File.ReadAllBytes(fixture.JarPath));
        Assert.Empty(Directory.GetFiles(Path.GetDirectoryName(fixture.JarPath)!, ".control-velocity-*"));
        Assert.DoesNotContain(fixture.Client.Requests, x => x.Uri.Host == "example.test");
    }

    [Fact]
    public async Task NoChangeSkipsDownloadAndOldJavaIsRejectedBeforeDownload()
    {
        using (var same = new Fixture(sameHash: true))
        {
            var prepared = await same.Storage.PrepareVelocityUpdateAsync(same.Server, Guid.NewGuid());
            Assert.False(prepared.Updated);
            Assert.DoesNotContain(same.Client.Requests, x => x.Uri.Host == "fill-data.papermc.io");
        }
        using var oldJava = new Fixture(javaVersion: 21);
        await Assert.ThrowsAsync<InvalidDataException>(() => oldJava.Storage.PrepareVelocityUpdateAsync(oldJava.Server, Guid.NewGuid()));
        Assert.DoesNotContain(oldJava.Client.Requests, x => x.Uri.Host == "fill-data.papermc.io");
        using var sameWithOldJava = new Fixture(sameHash: true, javaVersion: 21);
        await Assert.ThrowsAsync<InvalidDataException>(() => sameWithOldJava.Storage.PrepareVelocityUpdateAsync(sameWithOldJava.Server, Guid.NewGuid()));
        Assert.DoesNotContain(sameWithOldJava.Client.Requests, x => x.Uri.Host == "fill-data.papermc.io");
    }

    [Theory]
    [InlineData("../velocity.jar")]
    [InlineData("plugins/velocity.jar")]
    [InlineData("other.jar")]
    [InlineData("C:\\velocity.jar")]
    public async Task TargetIsRestrictedToTheRegisteredVelocityJar(string name)
    {
        using var fixture = new Fixture();
        var server = fixture.Server with { ArgumentList = ["-jar", name] };
        await Assert.ThrowsAsync<InvalidDataException>(() => fixture.Storage.PrepareVelocityUpdateAsync(server, Guid.NewGuid()));
        Assert.Empty(fixture.Client.Requests);
    }

    [Fact]
    public async Task NewerInstalledVersionCannotBeDowngradedToStable()
    {
        using var fixture = new Fixture();
        File.WriteAllBytes(fixture.JarPath, VelocityJar("4.4.0-SNAPSHOT", 50));
        await Assert.ThrowsAsync<InvalidDataException>(() => fixture.Storage.PrepareVelocityUpdateAsync(fixture.Server, Guid.NewGuid()));
        Assert.DoesNotContain(fixture.Client.Requests, x => x.Uri.Host == "fill-data.papermc.io");
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task StableManifestWithoutBuildNumberUsesInstalledChecksumToPreventDowngrade(bool newer)
    {
        using var fixture = new Fixture();
        var installed = VelocityJar("4.3.1", newer ? 46 : 44);
        File.WriteAllBytes(fixture.JarPath, installed);
        fixture.Client.InstalledBuild = newer ? 46 : 44;
        fixture.Client.InstalledHash = Convert.ToHexStringLower(SHA256.HashData(installed));
        if (newer)
            await Assert.ThrowsAsync<InvalidDataException>(() => fixture.Storage.PrepareVelocityUpdateAsync(fixture.Server, Guid.NewGuid()));
        else Assert.True((await fixture.Storage.PrepareVelocityUpdateAsync(fixture.Server, Guid.NewGuid())).Updated);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task GuardTimeModificationOfTargetOrStageCannotBeInstalled(bool stage)
    {
        using var fixture = new Fixture();
        var id = Guid.NewGuid();
        var prepared = await fixture.Storage.PrepareVelocityUpdateAsync(fixture.Server, id);
        await Assert.ThrowsAsync<FileConflictException>(() => fixture.Storage.ApplyVelocityUpdateAsync(fixture.Server, id, prepared, _ =>
        {
            File.WriteAllText(stage ? prepared.StagePath! : fixture.JarPath, "changed");
            return Task.CompletedTask;
        }));
        Assert.False(File.Exists(fixture.JarPath + ".control-" + id.ToString("N") + ".backup"));
    }

    internal static byte[] VelocityJar(string version, int build)
    {
        using var output = new MemoryStream();
        using (var archive = new ZipArchive(output, ZipArchiveMode.Create, true))
        {
            var manifest = archive.CreateEntry("META-INF/MANIFEST.MF");
            manifest.LastWriteTime = new DateTimeOffset(2026, 1, 1, 0, 0, 0, TimeSpan.Zero);
            using var writer = new StreamWriter(manifest.Open(), new UTF8Encoding(false));
            writer.Write($"Manifest-Version: 1.0\r\nImplementation-Version: {version}{(version.Contains('-') ? $" (git-abcdef-b{build})" : "")}\r\nImplementation-Title: Velocity\r\nMain-Class: com.velocitypowered.proxy.Velocity\r\n\r\n");
            writer.Dispose();
            var marker = archive.CreateEntry("build-fixture.txt");
            marker.LastWriteTime = manifest.LastWriteTime;
            using var markerWriter = new StreamWriter(marker.Open());
            markerWriter.Write(build);
        }
        return output.ToArray();
    }

    private sealed class Fixture : IDisposable
    {
        private readonly string root = Path.Combine(Path.GetTempPath(), "control-velocity-storage-" + Guid.NewGuid().ToString("N"));
        public string JarPath => Path.Combine(root, "managed", "server", "velocity-4.1.0-SNAPSHOT-8.jar");
        public ServerConfig Server { get; }
        public P2StorageService Storage { get; }
        public VelocityHandler Client { get; }
        public byte[] Installed { get; }
        public Fixture(string? failure = null, bool sameHash = false, int javaVersion = 25)
        {
            Directory.CreateDirectory(Path.GetDirectoryName(JarPath)!);
            Client = new VelocityHandler(failure);
            Installed = sameHash ? Client.Offered : VelocityJar("4.1.0-SNAPSHOT", 8);
            File.WriteAllBytes(JarPath, Installed);
            var java = Path.Combine(root, "java.exe"); File.WriteAllText(java, "fixture");
            var rootId = Guid.NewGuid(); var executableId = Guid.NewGuid();
            Server = new() { HostId = Guid.NewGuid(), ServerId = Guid.NewGuid(), Revision = 1, Kind = ServerKind.Velocity,
                ManagedRootId = rootId, ExecutableId = executableId, ServerDirectory = "server", ArgumentList = ["-Xmx2G", "-jar", Path.GetFileName(JarPath)] };
            Storage = new P2StorageService(Path.Combine(root, "agent"), new HostResources
                { Roots = [new ManagedRoot { Id = rootId, Path = Path.Combine(root, "managed") }], Executables = [new ManagedExecutable { Id = executableId, Path = java, Kind = ServerKind.Java }] },
                availableBytesProbe: _ => long.MaxValue, geyserHttpClient: new HttpClient(Client), javaVersionProbe: (_, _) => Task.FromResult(javaVersion));
        }
        public void Dispose() => Directory.Delete(root, true);
    }

    internal sealed class VelocityHandler(string? failure = null) : HttpMessageHandler
    {
        public byte[] Offered { get; } = VelocityJar(failure == "identity" ? "4.3.0" : "4.3.1", 45);
        public int InstalledBuild { get; set; } = 44;
        public string InstalledHash { get; set; } = new('0', 64);
        public List<(Uri Uri, string UserAgent)> Requests { get; } = [];
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            Requests.Add((request.RequestUri!, request.Headers.UserAgent.ToString()));
            var path = request.RequestUri!.AbsolutePath;
            var sha = Convert.ToHexStringLower(SHA256.HashData(Offered));
            object? metadata = null;
            if (request.RequestUri.Host == "fill-data.papermc.io")
            {
                if (failure == "redirect") return Task.FromResult(new HttpResponseMessage(HttpStatusCode.Found) { Headers = { Location = new Uri("https://example.test/velocity.jar") } });
                return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK) { Content = new ByteArrayContent(failure == "hash" ? Offered.Select(b => (byte)(b ^ 1)).ToArray() : Offered) });
            }
            if (path == "/v3/projects/velocity") metadata = new { project = new { id = "velocity" }, versions = new Dictionary<string, string[]> { ["4.0.0"] = ["4.3.2-SNAPSHOT", "4.3.1"] } };
            else if (path.EndsWith("4.3.2-SNAPSHOT/builds", StringComparison.Ordinal)) metadata = new[] { new { id = 46, channel = "BETA" } };
            else if (path.EndsWith("/builds", StringComparison.Ordinal)) metadata = new[] { new { id = 45, channel = "STABLE", downloads = new Dictionary<string, object>
                { ["server:default"] = new { name = "velocity-4.3.1-45.jar", checksums = new { sha256 = sha }, size = Offered.Length + (failure == "size" ? 1 : 0),
                    url = failure == "host" ? "https://example.test/velocity.jar" : $"https://fill-data.papermc.io/v1/objects/{sha}/velocity-4.3.1-45.jar" } } },
                new { id = InstalledBuild, channel = "BETA", downloads = new Dictionary<string, object> { ["server:default"] = new { checksums = new { sha256 = InstalledHash } } } } };
            else metadata = new { version = new { id = "4.3.1", support = new { status = "SUPPORTED" }, java = new { version = new { minimum = 25 } } } };
            return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent(JsonSerializer.Serialize(metadata)) });
        }
    }
}
