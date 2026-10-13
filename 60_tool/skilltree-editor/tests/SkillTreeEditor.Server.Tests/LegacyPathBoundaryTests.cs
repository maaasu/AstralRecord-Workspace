using System.Diagnostics;
using System.Net;
using System.Text.Json.Nodes;
using SkillTreeEditor.Server.Services;

namespace SkillTreeEditor.Server.Tests;

public sealed class LegacyPathBoundaryTests : IDisposable
{
    private readonly string _root = Path.Combine(Path.GetTempPath(), $"skilltree-boundary-{Guid.NewGuid():N}");
    private readonly List<string> _links = [];
    private readonly WorkspacePaths _paths;
    private readonly string _outside;

    public LegacyPathBoundaryTests()
    {
        _paths = new WorkspacePaths(Path.Combine(_root, "workspace"));
        _outside = Path.Combine(_root, "outside");
        Directory.CreateDirectory(_paths.WorkspaceRoot);
        Directory.CreateDirectory(_outside);
    }

    [Fact]
    public void MissingJunctionTargetDoesNotBypassPathProtection()
    {
        LinkDirectory(_paths.Nodes, _outside);
        Directory.Delete(_outside);

        Assert.Throws<UnauthorizedAccessException>(() => new MasterDataPaths(_paths).Resolve("35.features.skilltree/nodes/1000.json"));
    }

    [Fact]
    public async Task SchemaReadListAndValidationRejectLinkedSchemaDirectory()
    {
        await File.WriteAllTextAsync(Path.Combine(_outside, "node.v1.schema.json"), "{\"title\":\"outside secret\"}");
        LinkDirectory(_paths.Schemas, _outside);
        var catalog = new SchemaCatalog(_paths);
        var document = new JsonObject { ["$schema"] = "node.v1.schema.json" };

        await Assert.ThrowsAsync<UnauthorizedAccessException>(() => catalog.ListAsync(CancellationToken.None));
        await Assert.ThrowsAsync<UnauthorizedAccessException>(() => catalog.GetAsync("node.v1.schema.json", CancellationToken.None));
        await Assert.ThrowsAsync<UnauthorizedAccessException>(() => catalog.ValidateAsync("node", document, "1000.json", CancellationToken.None));
        await Assert.ThrowsAsync<UnauthorizedAccessException>(() => catalog.ValidateCatalogAsync(CancellationToken.None));
    }

    [Theory]
    [InlineData("class")]
    [InlineData("skill")]
    [InlineData("tag")]
    public async Task MasterCatalogsRejectLinkedSourceDirectories(string kind)
    {
        await File.WriteAllTextAsync(Path.Combine(_outside, "v1.external.yml"), "id: external\nname: outside secret");
        var directory = kind switch
        {
            "class" => _paths.Classes,
            "skill" => _paths.Skills,
            _ => Path.GetDirectoryName(_paths.TagCatalog)!
        };
        LinkDirectory(directory, _outside);
        Func<Task> read = kind switch
        {
            "class" => async () => { await new ClassMasterCatalog(_paths).ReadAllAsync(CancellationToken.None); },
            "skill" => async () => { await new SkillMasterCatalog(_paths).ReadAllAsync(CancellationToken.None); },
            _ => async () => { await new MasterTagCatalog(_paths).ReadAllAsync(CancellationToken.None); }
        };

        await Assert.ThrowsAsync<UnauthorizedAccessException>(read);
    }

    [Fact]
    public async Task RecursiveSkillCatalogDoesNotEnterDiscoveredJunctions()
    {
        Directory.CreateDirectory(_paths.Skills);
        await File.WriteAllTextAsync(Path.Combine(_paths.Skills, "v1.local.yml"), "id: local\nname: ローカル");
        await File.WriteAllTextAsync(Path.Combine(_outside, "v1.external.yml"), "id: external\nname: outside secret");
        LinkDirectory(Path.Combine(_paths.Skills, "nested"), _outside);

        var summaries = await new SkillMasterCatalog(_paths).ReadAllAsync(CancellationToken.None);

        Assert.Equal("local", Assert.Single(summaries).Id);
    }

    [Fact]
    public async Task ValidationRejectsLinkedNodeDirectoryBeforeReadingDocuments()
    {
        await File.WriteAllTextAsync(Path.Combine(_outside, "1000.json"), "{\"nodeId\":\"outside secret\"}");
        LinkDirectory(_paths.Nodes, _outside);
        var validation = new ValidationService(_paths, new SchemaCatalog(_paths),
            new PluginConfigService(_paths, new BackupService(_paths)), new MasterTagCatalog(_paths));

        await Assert.ThrowsAsync<UnauthorizedAccessException>(() => validation.ValidateAllAsync(CancellationToken.None));
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task IconCacheRejectsLinksBeforeReadingOrDownloading(bool refresh)
    {
        var externalIcon = Path.Combine(_outside, "stone.png");
        await File.WriteAllBytesAsync(externalIcon, [1, 2, 3]);
        LinkDirectory(_paths.MinecraftIconCache, _outside);
        var handler = new CountingHandler();
        using var client = new HttpClient(handler) { BaseAddress = new Uri("https://example.invalid/") };

        await Assert.ThrowsAsync<UnauthorizedAccessException>(() =>
            new MinecraftIconService(client, _paths).GetIconPathAsync("stone", refresh, CancellationToken.None));

        Assert.Equal(0, handler.RequestCount);
        Assert.Equal(new byte[] { 1, 2, 3 }, await File.ReadAllBytesAsync(externalIcon));
        Assert.Single(Directory.EnumerateFileSystemEntries(_outside));
    }

    private void LinkDirectory(string link, string target)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(link)!);
        if (OperatingSystem.IsWindows())
        {
            var start = new ProcessStartInfo("cmd.exe")
            {
                CreateNoWindow = true, UseShellExecute = false,
                RedirectStandardOutput = true, RedirectStandardError = true
            };
            foreach (var argument in new[] { "/c", "mklink", "/J", link, target })
                start.ArgumentList.Add(argument);
            using var process = Process.Start(start)!;
            process.WaitForExit();
            Assert.Equal(0, process.ExitCode);
        }
        else Directory.CreateSymbolicLink(link, target);
        _links.Add(link);
    }

    public void Dispose()
    {
        foreach (var link in _links)
            Directory.Delete(link);
        Directory.Delete(_root, recursive: true);
    }

    private sealed class CountingHandler : HttpMessageHandler
    {
        public int RequestCount { get; private set; }
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            RequestCount++;
            return Task.FromResult(new HttpResponseMessage(HttpStatusCode.NotFound));
        }
    }
}
