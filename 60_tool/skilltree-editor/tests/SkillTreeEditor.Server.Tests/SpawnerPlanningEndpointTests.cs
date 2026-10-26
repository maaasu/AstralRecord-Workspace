using System.Net;
using System.Text.Json;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Hosting.Server;
using Microsoft.AspNetCore.Hosting.Server.Features;
using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Options;
using SkillTreeEditor.Server.Endpoints;
using SkillTreeEditor.Server.Options;
using SkillTreeEditor.Server.Services;

namespace SkillTreeEditor.Server.Tests;

public sealed class SpawnerPlanningEndpointTests : IDisposable
{
    private readonly string _workspace = Path.Combine(Path.GetTempPath(), $"spawner-api-{Guid.NewGuid():N}");
    private readonly string _revision = new('a', 64);
    private readonly string _fingerprint = new('b', 64);

    [Fact]
    public async Task MissingExportHasActionableResponseAndDoesNotCreateOutput()
    {
        await using var host = await StartAsync();
        using var response = await host.Client.GetAsync("/api/spawner-planning/manifest");
        Assert.Equal(HttpStatusCode.NotFound, response.StatusCode);
        Assert.Contains("地形解析と配置案の生成", await response.Content.ReadAsStringAsync());
        Assert.False(Directory.Exists(host.DataDirectory));
    }

    [Fact]
    public async Task ForeignHostCannotReadPreview()
    {
        await using var host = await StartAsync();
        WriteExport(host.DataDirectory);
        using var request = new HttpRequestMessage(HttpMethod.Get, "/api/spawner-planning/manifest");
        request.Headers.Host = "foreign.example:5274";
        using var response = await host.Client.SendAsync(request);
        Assert.Equal(HttpStatusCode.Forbidden, response.StatusCode);
    }

    [Fact]
    public async Task PublishedExportRequiresMatchingRevisionAndStreamsReadOnlyFiles()
    {
        await using var host = await StartAsync();
        WriteExport(host.DataDirectory);
        var before = Directory.GetFiles(host.DataDirectory).ToDictionary(path => path, File.GetLastWriteTimeUtc);

        using var manifest = await host.Client.GetAsync("/api/spawner-planning/manifest");
        Assert.Equal(HttpStatusCode.OK, manifest.StatusCode);
        Assert.Equal($"\"{_revision}\"", manifest.Headers.ETag?.ToString());
        using var missingRevision = await host.Client.GetAsync("/api/spawner-planning/cells");
        Assert.Equal(HttpStatusCode.BadRequest, missingRevision.StatusCode);
        using var stale = await host.Client.GetAsync("/api/spawner-planning/regions?revision=old");
        Assert.Equal(HttpStatusCode.Conflict, stale.StatusCode);
        using var invalidRadius = await host.Client.GetAsync($"/api/spawner-planning/proposals/128?revision={_revision}");
        Assert.Equal(HttpStatusCode.BadRequest, invalidRadius.StatusCode);

        foreach (var path in new[] { "cells", "regions", "proposals/32", "proposals/48", "proposals/64" })
        {
            using var response = await host.Client.GetAsync($"/api/spawner-planning/{path}?revision={_revision}");
            Assert.Equal(HttpStatusCode.OK, response.StatusCode);
            Assert.Equal($"\"{_revision}\"", response.Headers.ETag?.ToString());
            Assert.Equal("no-store", response.Headers.CacheControl?.ToString());
            using var json = JsonDocument.Parse(await response.Content.ReadAsStringAsync());
            Assert.Equal(_revision, json.RootElement.GetProperty("exportRevision").GetString());
        }
        using var post = await host.Client.PostAsync("/api/spawner-planning/manifest", null);
        Assert.NotEqual(HttpStatusCode.OK, post.StatusCode);
        Assert.Equal(before, Directory.GetFiles(host.DataDirectory).ToDictionary(path => path, File.GetLastWriteTimeUtc));
    }

    [Fact]
    public async Task SelectedRadiusOnlyExportIsReadableAndMissingRadiusIsNotFound()
    {
        await using var host = await StartAsync();
        WriteExport(host.DataDirectory);
        var manifestPath = Path.Combine(host.DataDirectory, "manifest.json");
        var manifest = System.Text.Json.Nodes.JsonNode.Parse(File.ReadAllText(manifestPath))!;
        manifest["radiusOptions"] = new System.Text.Json.Nodes.JsonArray(64);
        manifest["files"]!["proposals"]!.AsObject().Remove("32");
        manifest["files"]!["proposals"]!.AsObject().Remove("48");
        File.WriteAllText(manifestPath, manifest.ToJsonString());
        using var response = await host.Client.GetAsync("/api/spawner-planning/manifest");
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        using var selected = await host.Client.GetAsync($"/api/spawner-planning/proposals/64?revision={_revision}");
        Assert.Equal(HttpStatusCode.OK, selected.StatusCode);
        using var absent = await host.Client.GetAsync($"/api/spawner-planning/proposals/32?revision={_revision}");
        Assert.Equal(HttpStatusCode.NotFound, absent.StatusCode);
        manifest["radiusOptions"] = new System.Text.Json.Nodes.JsonArray(64, 64);
        File.WriteAllText(manifestPath, manifest.ToJsonString());
        using var duplicate = await host.Client.GetAsync("/api/spawner-planning/manifest");
        Assert.Equal(HttpStatusCode.Conflict, duplicate.StatusCode);
    }

    [Fact]
    public async Task MixedGenerationAndIdentityAreRejected()
    {
        await using var host = await StartAsync();
        WriteExport(host.DataDirectory);
        File.WriteAllText(Path.Combine(host.DataDirectory, "cells.json"), Data("cells", new string('c', 64)));
        using var mixed = await host.Client.GetAsync($"/api/spawner-planning/cells?revision={_revision}");
        Assert.Equal(HttpStatusCode.Conflict, mixed.StatusCode);

        File.WriteAllText(Path.Combine(host.DataDirectory, "cells.json"), Data("cells", _revision, worldId: "other"));
        using var wrongWorld = await host.Client.GetAsync($"/api/spawner-planning/cells?revision={_revision}");
        Assert.Equal(HttpStatusCode.Conflict, wrongWorld.StatusCode);

        File.WriteAllText(Path.Combine(host.DataDirectory, "proposals-32.json"), Data("proposals", _revision, worldId: "other"));
        using var wrongProposalWorld = await host.Client.GetAsync($"/api/spawner-planning/proposals/32?revision={_revision}");
        Assert.Equal(HttpStatusCode.Conflict, wrongProposalWorld.StatusCode);

        File.Delete(Path.Combine(host.DataDirectory, "regions.json"));
        using var missingFile = await host.Client.GetAsync($"/api/spawner-planning/regions?revision={_revision}");
        Assert.Equal(HttpStatusCode.Conflict, missingFile.StatusCode);
    }

    [Fact]
    public async Task DetailImageIsOptionalAndRequiresMatchingIdentityAndDimensions()
    {
        await using var host = await StartAsync();
        WriteExport(host.DataDirectory);
        using var missing = await host.Client.GetAsync($"/api/spawner-planning/terrain-detail?revision={_revision}");
        Assert.Equal(HttpStatusCode.NotFound, missing.StatusCode);
        using var missingRevision = await host.Client.GetAsync("/api/spawner-planning/terrain-detail/image");
        Assert.Equal(HttpStatusCode.BadRequest, missingRevision.StatusCode);

        WriteDetail(host.DataDirectory);
        foreach (var route in new[] { "terrain-detail", "terrain-detail/image" })
        {
            using var response = await host.Client.GetAsync($"/api/spawner-planning/{route}?revision={_revision}");
            Assert.Equal(HttpStatusCode.OK, response.StatusCode);
            Assert.Equal($"\"{_revision}\"", response.Headers.ETag?.ToString());
            Assert.Equal("no-store", response.Headers.CacheControl?.ToString());
        }
        using var stale = await host.Client.GetAsync("/api/spawner-planning/terrain-detail?revision=old");
        Assert.Equal(HttpStatusCode.Conflict, stale.StatusCode);
        File.WriteAllText(Path.Combine(host.DataDirectory, "terrain-detail.json"),
            JsonSerializer.Serialize(new { schemaVersion = 1, terrainFingerprint = new string('c', 64), minX = -8, minZ = 16,
                width = 32, height = 48, blocksPerPixel = 1, image = "terrain-detail.png", imageSha256 = new string('d', 64) }));
        using var wrongFingerprint = await host.Client.GetAsync($"/api/spawner-planning/terrain-detail?revision={_revision}");
        Assert.Equal(HttpStatusCode.Conflict, wrongFingerprint.StatusCode);
        WriteDetail(host.DataDirectory);
        var png = File.ReadAllBytes(Path.Combine(host.DataDirectory, "terrain-detail.png"));
        png[8] = 1;
        File.WriteAllBytes(Path.Combine(host.DataDirectory, "terrain-detail.png"), png);
        using var changedImage = await host.Client.GetAsync($"/api/spawner-planning/terrain-detail/image?revision={_revision}");
        Assert.Equal(HttpStatusCode.Conflict, changedImage.StatusCode);
        WriteDetail(host.DataDirectory);
        png = File.ReadAllBytes(Path.Combine(host.DataDirectory, "terrain-detail.png"));
        png[23] = 49;
        File.WriteAllBytes(Path.Combine(host.DataDirectory, "terrain-detail.png"), png);
        WriteDetailMetadata(host.DataDirectory, png);
        using var wrongDimensions = await host.Client.GetAsync($"/api/spawner-planning/terrain-detail/image?revision={_revision}");
        Assert.Equal(HttpStatusCode.Conflict, wrongDimensions.StatusCode);
    }

    [Fact]
    public async Task ConfiguredRelativeTraversalIsRejected()
    {
        Directory.CreateDirectory(_workspace);
        var service = new SpawnerPlanningService(Microsoft.Extensions.Options.Options.Create(new EditorOptions
        {
            SpawnerPlanningDataDirectory = "../other"
        }), new WorkspacePaths(_workspace));
        await Assert.ThrowsAsync<ArgumentException>(() => service.ReadManifestAsync(new DefaultHttpContext(), CancellationToken.None));
    }

    private async Task<RunningHost> StartAsync()
    {
        Directory.CreateDirectory(_workspace);
        var dataDirectory = Path.Combine(_workspace, "export");
        var builder = WebApplication.CreateBuilder(new WebApplicationOptions { ContentRootPath = _workspace });
        builder.WebHost.UseUrls("http://127.0.0.1:0");
        builder.Services.AddSingleton(new WorkspacePaths(_workspace));
        builder.Services.AddSingleton(Microsoft.Extensions.Options.Options.Create(new EditorOptions { SpawnerPlanningDataDirectory = dataDirectory }));
        builder.Services.AddSingleton<SpawnerPlanningService>();
        var app = builder.Build();
        app.UseMiddleware<LocalEditorRequestBoundaryMiddleware>();
        app.MapSpawnerPlanningEndpoints();
        await app.StartAsync();
        var address = app.Services.GetRequiredService<IServer>().Features.Get<IServerAddressesFeature>()!.Addresses.Single();
        return new RunningHost(app, new HttpClient { BaseAddress = new Uri(address) }, dataDirectory);
    }

    private void WriteExport(string directory)
    {
        Directory.CreateDirectory(directory);
        var manifest = new
        {
            schemaVersion = 2, mode = "continent-redesign", previewOnly = true,
            exportRevision = _revision, worldId = "eriva", terrainFingerprint = _fingerprint,
            bounds = new { minX = -8, minZ = 16, width = 32, height = 48, cellSize = 16 },
            radiusOptions = new[] { 32, 48, 64 },
            files = new { cells = "cells.json", regions = "regions.json", proposals = new Dictionary<string, string>
            {
                ["32"] = "proposals-32.json", ["48"] = "proposals-48.json", ["64"] = "proposals-64.json"
            } }
        };
        File.WriteAllText(Path.Combine(directory, "manifest.json"), JsonSerializer.Serialize(manifest));
        File.WriteAllText(Path.Combine(directory, "cells.json"), Data("cells", _revision));
        File.WriteAllText(Path.Combine(directory, "regions.json"), Data("regions", _revision));
        foreach (var radius in new[] { 32, 48, 64 })
            File.WriteAllText(Path.Combine(directory, $"proposals-{radius}.json"), Data("proposals", _revision, radius: radius));
    }

    private void WriteDetail(string directory)
    {
        var header = new byte[] { 137, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82,
            0, 0, 0, 32, 0, 0, 0, 48 };
        File.WriteAllBytes(Path.Combine(directory, "terrain-detail.png"), header);
        WriteDetailMetadata(directory, header);
    }

    private void WriteDetailMetadata(string directory, byte[] png)
        => File.WriteAllText(Path.Combine(directory, "terrain-detail.json"),
            JsonSerializer.Serialize(new { schemaVersion = 1, terrainFingerprint = _fingerprint, minX = -8, minZ = 16,
                width = 32, height = 48, blocksPerPixel = 1, image = "terrain-detail.png",
                imageSha256 = Convert.ToHexStringLower(System.Security.Cryptography.SHA256.HashData(png)) }));

    private string Data(string kind, string revision, string worldId = "eriva", int radius = 32)
        => kind == "proposals"
            ? JsonSerializer.Serialize(new { schemaVersion = 2, exportRevision = revision, worldId, terrainFingerprint = _fingerprint,
                previewOnly = true, radiusMeters = radius, proposals = Array.Empty<object>() })
            : JsonSerializer.Serialize(new { schemaVersion = 2, exportRevision = revision, worldId,
                terrainFingerprint = _fingerprint, cells = kind == "cells" ? Array.Empty<object>() : null,
                regions = kind == "regions" ? Array.Empty<object>() : null });

    public void Dispose()
    {
        if (Directory.Exists(_workspace)) Directory.Delete(_workspace, recursive: true);
    }

    private sealed class RunningHost(WebApplication app, HttpClient client, string dataDirectory) : IAsyncDisposable
    {
        public HttpClient Client { get; } = client;
        public string DataDirectory { get; } = dataDirectory;
        public async ValueTask DisposeAsync()
        {
            Client.Dispose();
            await app.StopAsync();
            await app.DisposeAsync();
        }
    }
}
