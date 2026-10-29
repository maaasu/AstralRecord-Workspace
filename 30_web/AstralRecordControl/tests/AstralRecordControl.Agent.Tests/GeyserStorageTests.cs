using System.Net;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using AstralRecordControl.Agent.Storage;
using AstralRecordControl.Contracts;
using Xunit;

namespace AstralRecordControl.Agent.Tests;

public sealed class GeyserStorageTests
{
    [Fact]
    public async Task VerifiedJarIsStagedBeforeStopAndReplacesOnlyExpectedVelocityPlugin()
    {
        using var fixture = new Fixture("old", "new");
        var operationId = Guid.NewGuid();
        var prepared = await fixture.Storage.PrepareGeyserUpdateAsync(fixture.Server, operationId);
        Assert.True(prepared.Updated);
        Assert.Equal("old", File.ReadAllText(fixture.PluginPath));
        Assert.True(File.Exists(prepared.StagePath));
        var guardCalled = false;
        await fixture.Storage.ApplyGeyserUpdateAsync(fixture.Server, operationId, prepared, _ => { guardCalled = true; return Task.CompletedTask; });
        Assert.True(guardCalled);
        Assert.Equal("new", File.ReadAllText(fixture.PluginPath));
        Assert.Equal("old", File.ReadAllText(fixture.PluginPath + ".control-" + operationId.ToString("N") + ".backup"));
        Assert.All(fixture.Client.Requested, uri => Assert.Equal("download.geysermc.org", uri.Host));
        Assert.Contains(fixture.Client.Requested, uri => uri.AbsolutePath.EndsWith("/builds/17/downloads/velocity", StringComparison.Ordinal));
    }

    [Fact]
    public async Task SameChecksumAndBadDownloadNeverChangeInstalledJar()
    {
        using (var same = new Fixture("same", "same"))
        {
            var prepared = await same.Storage.PrepareGeyserUpdateAsync(same.Server, Guid.NewGuid());
            Assert.False(prepared.Updated);
            Assert.Null(prepared.StagePath);
            Assert.Single(same.Client.Requested);
            Assert.Equal("same", File.ReadAllText(same.PluginPath));
        }
        using var bad = new Fixture("old", "new", corruptDownload: true);
        await Assert.ThrowsAsync<InvalidDataException>(() => bad.Storage.PrepareGeyserUpdateAsync(bad.Server, Guid.NewGuid()));
        Assert.Equal("old", File.ReadAllText(bad.PluginPath));
        Assert.Empty(Directory.GetFiles(Path.GetDirectoryName(bad.PluginPath)!, ".control-geyser-*"));
    }

    [Fact]
    public async Task MissingPluginAndChangedOriginalCannotInstallJar()
    {
        using var fixture = new Fixture("old", "new");
        File.Delete(fixture.PluginPath);
        await Assert.ThrowsAsync<FileNotFoundException>(() => fixture.Storage.PrepareGeyserUpdateAsync(fixture.Server, Guid.NewGuid()));
        File.WriteAllText(fixture.PluginPath, "old");
        var operationId = Guid.NewGuid();
        var prepared = await fixture.Storage.PrepareGeyserUpdateAsync(fixture.Server, operationId);
        File.WriteAllText(fixture.PluginPath, "modified");
        await Assert.ThrowsAsync<FileConflictException>(() => fixture.Storage.ApplyGeyserUpdateAsync(fixture.Server, operationId, prepared, _ => Task.CompletedTask));
        Assert.Equal("modified", File.ReadAllText(fixture.PluginPath));
        Assert.False(File.Exists(fixture.PluginPath + ".control-" + operationId.ToString("N") + ".backup"));
    }

    private sealed class Fixture : IDisposable
    {
        private readonly string root = Path.Combine(Path.GetTempPath(), "control-geyser-storage-" + Guid.NewGuid().ToString("N"));
        public string PluginPath => Path.Combine(root, "managed", "server", "plugins", "Geyser-Velocity.jar");
        public ServerConfig Server { get; }
        public P2StorageService Storage { get; }
        public FakeHandler Client { get; }
        public Fixture(string installed, string offered, bool corruptDownload = false)
        {
            Directory.CreateDirectory(Path.GetDirectoryName(PluginPath)!);
            File.WriteAllText(PluginPath, installed);
            var rootId = Guid.NewGuid();
            Server = new ServerConfig { HostId = Guid.NewGuid(), ServerId = Guid.NewGuid(), Revision = 1,
                Kind = ServerKind.Velocity, ManagedRootId = rootId, ServerDirectory = "server" };
            Client = new FakeHandler(offered, corruptDownload);
            Storage = new P2StorageService(Path.Combine(root, "agent"),
                new HostResources { Roots = [new ManagedRoot { Id = rootId, Path = Path.Combine(root, "managed") }] },
                availableBytesProbe: _ => long.MaxValue, geyserHttpClient: new HttpClient(Client));
        }
        public void Dispose() => Directory.Delete(root, true);
    }

    private sealed class FakeHandler(string offered, bool corruptDownload) : HttpMessageHandler
    {
        public List<Uri> Requested { get; } = [];
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            Requested.Add(request.RequestUri!);
            var content = request.RequestUri!.AbsolutePath.EndsWith("/downloads/velocity", StringComparison.Ordinal)
                ? corruptDownload ? "corrupt" : offered
                : JsonSerializer.Serialize(new { project_id = "geyser", version = "2.12.1", build = 17,
                    downloads = new { velocity = new { name = "Geyser-Velocity.jar", sha256 = Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(offered))) } } });
            return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent(content) });
        }
    }
}
