using System.Net;
using System.Text;
using System.Text.Json.Nodes;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Hosting.Server;
using Microsoft.AspNetCore.Hosting.Server.Features;
using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using SkillTreeEditor.Server.Endpoints;
using SkillTreeEditor.Server.Services;

namespace SkillTreeEditor.Server.Tests;

public sealed class LocalEditorRequestBoundaryTests : IDisposable
{
    private readonly string _workspace = Path.Combine(Path.GetTempPath(), $"skilltree-request-boundary-{Guid.NewGuid():N}");

    [Theory]
    [InlineData("nodes", "text/plain")]
    [InlineData("structures", "text/plain")]
    [InlineData("nodes", "application/json")]
    [InlineData("structures", "application/json")]
    public async Task ForeignHostCannotCreateDocumentsEvenWithSameOriginFetchMetadata(string endpoint, string contentType)
    {
        await using var host = await StartAsync();
        using var request = CreateRequest(endpoint, contentType);
        request.Headers.Host = "rebound.example:5274";
        request.Headers.Add("Origin", "http://rebound.example:5274");
        request.Headers.Add("Sec-Fetch-Site", "same-origin");

        using var response = await host.Client.SendAsync(request);

        await AssertRejectedWithoutMutation(host, response);
    }

    [Theory]
    [InlineData("https://external.example", "same-site")]
    [InlineData("null", "same-origin")]
    [InlineData("http://127.0.0.1:5173/path", "same-origin")]
    [InlineData("http://127.0.0.1:5173?query=1", "same-origin")]
    [InlineData("http://127.0.0.1:5173,http://evil.example", "same-origin")]
    [InlineData(null, "cross-site")]
    [InlineData(null, "unknown")]
    public async Task ForeignOrMalformedBrowserMetadataCannotCreateDocuments(string? origin, string fetchSite)
    {
        await using var host = await StartAsync();
        using var request = CreateRequest("nodes", "text/plain");
        if (origin is not null) request.Headers.TryAddWithoutValidation("Origin", origin);
        request.Headers.Add("Sec-Fetch-Site", fetchSite);

        using var response = await host.Client.SendAsync(request);

        await AssertRejectedWithoutMutation(host, response);
    }

    [Theory]
    [InlineData("127.0.0.1:5274", null)]
    [InlineData("localhost:5274", null)]
    [InlineData("[::1]:5274", null)]
    [InlineData("127.0.0.1:5274", "http://127.0.0.1:5173")]
    [InlineData("127.0.0.1:5274", "http://localhost:5173")]
    [InlineData("localhost:5274", "http://127.0.0.1:5310")]
    [InlineData("[::1]:5274", "http://[::1]:5310")]
    public async Task LoopbackCliAndBrowserRequestsStillCreateDocuments(string requestHost, string? origin)
    {
        await using var host = await StartAsync();
        using var request = CreateRequest("nodes");
        request.Headers.Host = requestHost;
        if (origin is not null)
        {
            request.Headers.Add("Origin", origin);
            request.Headers.Add("Sec-Fetch-Site", "same-origin");
        }

        using var response = await host.Client.SendAsync(request);

        Assert.Equal(HttpStatusCode.Created, response.StatusCode);
        Assert.True(File.Exists(Path.Combine(host.Paths.Nodes, "1001.json")));
    }

    private static async Task AssertRejectedWithoutMutation(RunningEditor host, HttpResponseMessage response)
    {
        Assert.Equal(HttpStatusCode.Forbidden, response.StatusCode);
        var problem = JsonNode.Parse(await response.Content.ReadAsStringAsync())!;
        Assert.Equal("ローカル専用エディタへのアクセスを拒否しました。", problem["title"]!.GetValue<string>());
        Assert.False(File.Exists(Path.Combine(host.Paths.Nodes, "1001.json")));
        Assert.False(File.Exists(Path.Combine(host.Paths.Structures, "created.json")));
        Assert.Equal("1000", JsonNode.Parse(await File.ReadAllTextAsync(host.Paths.NodeIdSequence))!["lastIssuedNodeId"]!.GetValue<string>());
        Assert.False(Directory.Exists(host.Paths.Backups));
    }

    [Theory]
    [InlineData("GET", "/", "document", null, HttpStatusCode.OK)]
    [InlineData("HEAD", "/", "document", null, HttpStatusCode.OK)]
    [InlineData("GET", "/", "iframe", null, HttpStatusCode.Forbidden)]
    [InlineData("GET", "/", "empty", null, HttpStatusCode.Forbidden)]
    [InlineData("GET", "/api/metadata", "document", null, HttpStatusCode.Forbidden)]
    [InlineData("GET", "/API/metadata", "document", null, HttpStatusCode.Forbidden)]
    [InlineData("POST", "/api/nodes", "document", null, HttpStatusCode.Forbidden)]
    [InlineData("GET", "/", "document", "https://external.example", HttpStatusCode.Forbidden)]
    public async Task CrossSiteLinksAllowOnlyTopLevelScreenNavigation(
        string method, string path, string destination, string? origin, HttpStatusCode expected)
    {
        await using var host = await StartAsync();
        using var request = new HttpRequestMessage(new HttpMethod(method), path);
        request.Headers.Add("Sec-Fetch-Site", "cross-site");
        request.Headers.Add("Sec-Fetch-Mode", "navigate");
        request.Headers.Add("Sec-Fetch-Dest", destination);
        if (origin is not null) request.Headers.Add("Origin", origin);

        using var response = await host.Client.SendAsync(request);

        Assert.Equal(expected, response.StatusCode);
        Assert.False(File.Exists(Path.Combine(host.Paths.Nodes, "1001.json")));
    }

    private static HttpRequestMessage CreateRequest(string endpoint, string contentType = "application/json")
        => new(HttpMethod.Post, $"/api/{endpoint}")
        {
            Content = new StringContent((endpoint == "nodes" ? Node("1001") : Structure()).ToJsonString(), Encoding.UTF8, contentType)
        };

    private async Task<RunningEditor> StartAsync(string environment = "Development")
    {
        PrepareWorkspace();
        var paths = new WorkspacePaths(_workspace);
        var backups = new BackupService(paths);
        var schemas = new SchemaCatalog(paths);
        var config = new PluginConfigService(paths, backups);
        var tags = new MasterTagCatalog(paths);
        var builder = WebApplication.CreateBuilder(new WebApplicationOptions { ContentRootPath = _workspace, EnvironmentName = environment });
        builder.WebHost.UseUrls("http://127.0.0.1:0");
        builder.Services.AddSingleton(paths);
        builder.Services.AddSingleton(backups);
        builder.Services.AddSingleton(new FilebaseRepository(paths, backups));
        builder.Services.AddSingleton(schemas);
        builder.Services.AddSingleton(config);
        builder.Services.AddSingleton(tags);
        builder.Services.AddSingleton(new ValidationService(paths, schemas, config, tags));
        builder.Services.AddSingleton(new WorkspaceMutationGate(paths));
        builder.Services.AddSingleton(new SkillMasterCatalog(paths));
        var app = builder.Build();
        app.UseMiddleware<LocalEditorRequestBoundaryMiddleware>();
        app.MapMethods("/", ["GET", "HEAD"], () => Results.Text("editor"));
        app.MapEditorEndpoints();
        await app.StartAsync();
        var address = app.Services.GetRequiredService<IServer>().Features.Get<IServerAddressesFeature>()!.Addresses.Single();
        return new RunningEditor(app, new HttpClient { BaseAddress = new Uri(address) }, paths);
    }

    private void PrepareWorkspace()
    {
        var paths = new WorkspacePaths(_workspace);
        foreach (var directory in new[] { paths.Nodes, paths.Structures, paths.Schemas, Path.GetDirectoryName(paths.PluginConfig)!, Path.GetDirectoryName(paths.TagCatalog)! })
            Directory.CreateDirectory(directory);
        var sourceRoot = WorkspacePaths.ResolveWorkspaceRoot(null, AppContext.BaseDirectory);
        var sourcePaths = new WorkspacePaths(sourceRoot);
        foreach (var name in new[] { "node.v1.schema.json", "structure.v1.schema.json", "node-id-sequence.v1.schema.json" })
            File.Copy(Path.Combine(sourcePaths.Schemas, name), Path.Combine(paths.Schemas, name));
        File.Copy(sourcePaths.TagCatalog, paths.TagCatalog);
        File.WriteAllText(Path.Combine(paths.Nodes, "1000.json"), StableJson.Serialize(Node("1000")));
        File.WriteAllText(paths.NodeIdSequence, "{\"$schema\":\"./schemas/node-id-sequence.v1.schema.json\",\"schemaVersion\":1,\"lastIssuedNodeId\":\"1000\"}");
        File.WriteAllText(paths.PluginConfig, "skilltree:\n  worldName: world\n  structureId: other\n  center:\n    x: 0\n    y: 0\n    z: 0\n");
    }

    private static JsonObject Node(string nodeId) => new()
    {
        ["$schema"] = "../schemas/node.v1.schema.json", ["schemaVersion"] = 1, ["nodeId"] = nodeId,
        ["name"] = "テスト", ["icon"] = "STONE", ["tags"] = new JsonArray(),
        ["pointType"] = "CP", ["pointCost"] = 1, ["effects"] = new JsonArray()
    };

    private static JsonObject Structure() => new()
    {
        ["$schema"] = "../schemas/structure.v1.schema.json", ["schemaVersion"] = 1,
        ["structureId"] = "created", ["name"] = "テスト", ["rootNodeId"] = "1000",
        ["nodes"] = new JsonArray(new JsonObject { ["nodeId"] = "1000", ["x"] = 0, ["y"] = 0, ["z"] = 0 }),
        ["edges"] = new JsonArray()
    };

    public void Dispose()
    {
        if (Directory.Exists(_workspace)) Directory.Delete(_workspace, recursive: true);
    }

    private sealed class RunningEditor(WebApplication app, HttpClient client, WorkspacePaths paths) : IAsyncDisposable
    {
        public HttpClient Client { get; } = client;
        public WorkspacePaths Paths { get; } = paths;
        public async ValueTask DisposeAsync()
        {
            Client.Dispose();
            await app.StopAsync();
            await app.DisposeAsync();
        }
    }
}
