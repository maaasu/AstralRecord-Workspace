using System.Net;
using System.Net.Http.Json;
using System.Text.Json.Nodes;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Hosting.Server;
using Microsoft.AspNetCore.Hosting.Server.Features;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using SkillTreeEditor.Server.Endpoints;
using SkillTreeEditor.Server.Services;

namespace SkillTreeEditor.Server.Tests;

public sealed class EditorEndpointMutationTests : IDisposable
{
    private readonly string _workspace = Path.Combine(
        Path.GetTempPath(),
        $"skilltree-endpoint-tests-{Guid.NewGuid():N}");

    [Fact]
    public async Task StaleNodeRevisionCannotOverwriteOrDeleteChangedNode()
    {
        await using var host = await StartAsync();
        using var original = await host.Client.GetAsync("/api/nodes/1000");
        var revision = original.Headers.ETag?.ToString() ?? "\"missing\"";
        await File.WriteAllTextAsync(Path.Combine(host.Paths.Nodes, "1000.json"),
            StableJson.Serialize(Node("1000", "External")));
        using var save = new HttpRequestMessage(HttpMethod.Put, "/api/nodes/1000")
        { Content = JsonContent.Create(Node("1000", "Stale")) };
        save.Headers.TryAddWithoutValidation("If-Match", revision);
        using var saved = await host.Client.SendAsync(save);
        using var delete = new HttpRequestMessage(HttpMethod.Delete, "/api/nodes/1000");
        delete.Headers.TryAddWithoutValidation("If-Match", revision);
        using var deleted = await host.Client.SendAsync(delete);
        Assert.Equal(HttpStatusCode.Conflict, saved.StatusCode);
        Assert.Equal(HttpStatusCode.Conflict, deleted.StatusCode);
        Assert.Contains("External", await File.ReadAllTextAsync(Path.Combine(host.Paths.Nodes, "1000.json")));
    }

    [Fact]
    public async Task MissingNodeRevisionCannotSave()
    {
        await using var host = await StartAsync();
        using var response = await host.Client.PutAsJsonAsync("/api/nodes/1000", Node("1000", "Changed"));
        Assert.Equal(HttpStatusCode.Conflict, response.StatusCode);
    }

    [Fact]
    public async Task TwoNodeSavesFromSameRevisionHaveOneWinnerAndReturnNewRevision()
    {
        await using var host = await StartAsync();
        using var original = await host.Client.GetAsync("/api/nodes/1000");
        var revision = original.Headers.ETag!.ToString();
        var responses = await Task.WhenAll(Enumerable.Range(0, 2).Select(async index =>
        {
            using var request = new HttpRequestMessage(HttpMethod.Put, "/api/nodes/1000")
            { Content = JsonContent.Create(Node("1000", "Writer" + index)) };
            request.Headers.TryAddWithoutValidation("If-Match", revision);
            return await host.Client.SendAsync(request);
        }));
        try
        {
            var winner = Assert.Single(responses, response => response.StatusCode == HttpStatusCode.OK);
            Assert.Single(responses, response => response.StatusCode == HttpStatusCode.Conflict);
            Assert.NotEqual(revision, winner.Headers.ETag!.ToString());
            using var current = await host.Client.GetAsync("/api/nodes/1000");
            Assert.Equal(current.Headers.ETag, winner.Headers.ETag);
        }
        finally { foreach (var response in responses) response.Dispose(); }
    }

    [Fact]
    public async Task StructureAndSettingsRejectStaleRevisions()
    {
        await using var host = await StartAsync();
        var structure = Structure("main", "1000");
        using var created = await host.Client.PostAsJsonAsync("/api/structures", structure);
        Assert.Equal(HttpStatusCode.Created, created.StatusCode);
        var originalStructureRevision = created.Headers.ETag!.ToString();
        structure["name"] = "Changed";
        using var currentSave = await PutCurrentAsync(host.Client, "/api/structures/main", structure);
        Assert.Equal(HttpStatusCode.OK, currentSave.StatusCode);
        using var staleStructure = new HttpRequestMessage(HttpMethod.Put, "/api/structures/main")
        { Content = JsonContent.Create(Structure("main", "1000")) };
        staleStructure.Headers.TryAddWithoutValidation("If-Match", originalStructureRevision);
        using var structureConflict = await host.Client.SendAsync(staleStructure);
        Assert.Equal(HttpStatusCode.Conflict, structureConflict.StatusCode);

        using var originalSettings = await host.Client.GetAsync("/api/settings");
        var originalSettingsRevision = originalSettings.Headers.ETag!.ToString();
        var settings = new SkillTreeEditor.Server.Models.PluginSkillTreeSettings("world", "main", 10, 20, 30);
        using var firstRequest = new HttpRequestMessage(HttpMethod.Put, "/api/settings")
        { Content = JsonContent.Create(settings) };
        firstRequest.Headers.TryAddWithoutValidation("If-Match", originalSettingsRevision);
        using var settingsSaved = await host.Client.SendAsync(firstRequest);
        Assert.Equal(HttpStatusCode.OK, settingsSaved.StatusCode);
        using var secondRequest = new HttpRequestMessage(HttpMethod.Put, "/api/settings")
        { Content = JsonContent.Create(settings with { CenterX = 99 }) };
        secondRequest.Headers.TryAddWithoutValidation("If-Match", originalSettingsRevision);
        using var settingsConflict = await host.Client.SendAsync(secondRequest);
        Assert.Equal(HttpStatusCode.Conflict, settingsConflict.StatusCode);
        Assert.Contains("x: 10", await File.ReadAllTextAsync(host.Paths.PluginConfig));
    }

    [Fact]
    public async Task StructureCreateAndNodeDeleteCannotPersistDanglingReference()
    {
        await using var host = await StartAsync();
        var structure = Structure("main", "1000");
        var mutation = await host.Gate.EnterAsync(CancellationToken.None);
        Task<HttpResponseMessage> createTask;
        Task<HttpResponseMessage> deleteTask;
        try
        {
            createTask = host.Client.PostAsJsonAsync("/api/structures", structure);
            deleteTask = DeleteCurrentAsync(host.Client, "/api/nodes/1000");
            await Task.Delay(100);
            Assert.False(createTask.IsCompleted);
            Assert.False(deleteTask.IsCompleted);
        }
        finally
        {
            await mutation.DisposeAsync();
        }

        using var createResponse = await createTask;
        using var deleteResponse = await deleteTask;
        Assert.Contains(createResponse.StatusCode,
            new[] { HttpStatusCode.Created, HttpStatusCode.UnprocessableEntity });
        Assert.Contains(deleteResponse.StatusCode,
            new[] { HttpStatusCode.NoContent, HttpStatusCode.Conflict });

        var structureExists = File.Exists(Path.Combine(host.Paths.Structures, "main.json"));
        var nodeExists = File.Exists(Path.Combine(host.Paths.Nodes, "1000.json"));
        Assert.False(structureExists && !nodeExists);
    }

    [Fact]
    public async Task ConcurrentNodeSaveAndDeleteCannotLoseSuccessfulSaveOrRecreateDeletedNode()
    {
        await using var host = await StartAsync();
        var changed = Node("1000", "Changed");
        var mutation = await host.Gate.EnterAsync(CancellationToken.None);
        Task<HttpResponseMessage> saveTask;
        Task<HttpResponseMessage> deleteTask;
        try
        {
            saveTask = PutCurrentAsync(host.Client, "/api/nodes/1000", changed);
            deleteTask = DeleteCurrentAsync(host.Client, "/api/nodes/1000");
            await Task.Delay(100);
            Assert.False(saveTask.IsCompleted);
            Assert.False(deleteTask.IsCompleted);
        }
        finally
        {
            await mutation.DisposeAsync();
        }

        using var saveResponse = await saveTask;
        using var deleteResponse = await deleteTask;
        Assert.Contains(saveResponse.StatusCode, new[] { HttpStatusCode.OK, HttpStatusCode.NotFound });
        Assert.Contains(deleteResponse.StatusCode, new[] { HttpStatusCode.NoContent, HttpStatusCode.Conflict });
        var nodePath = Path.Combine(host.Paths.Nodes, "1000.json");
        if (deleteResponse.StatusCode == HttpStatusCode.NoContent) Assert.False(File.Exists(nodePath));
        else
        {
            Assert.Equal(HttpStatusCode.OK, saveResponse.StatusCode);
            Assert.Contains("Changed", await File.ReadAllTextAsync(nodePath));
        }
    }

    [Fact]
    public async Task UnrelatedBrokenSchemaDoesNotBlockSaveAndReferencedBrokenSchemaReturns422()
    {
        await using var host = await StartAsync();
        await File.WriteAllTextAsync(Path.Combine(host.Paths.Schemas, "unrelated.schema.json"), "{");

        using var validResponse = await PutCurrentAsync(host.Client,
            "/api/nodes/1000",
            Node("1000", "Still valid"));
        Assert.Equal(HttpStatusCode.OK, validResponse.StatusCode);

        using var validationResponse = await host.Client.GetAsync("/api/validation");
        Assert.Equal(HttpStatusCode.OK, validationResponse.StatusCode);
        var validationBody = await validationResponse.Content.ReadAsStringAsync();
        Assert.Contains("SCHEMA_INVALID", validationBody);
        Assert.Contains("unrelated.schema.json", validationBody);

        await File.WriteAllTextAsync(Path.Combine(host.Paths.Schemas, "node.broken.schema.json"), "{");
        var invalid = Node("1000", "Invalid schema");
        invalid["$schema"] = "../schemas/node.broken.schema.json";
        using var invalidResponse = await PutCurrentAsync(host.Client, "/api/nodes/1000", invalid);

        Assert.Equal(HttpStatusCode.UnprocessableEntity, invalidResponse.StatusCode);
        Assert.Contains("SCHEMA_INVALID", await invalidResponse.Content.ReadAsStringAsync());
    }

    [Fact]
    public async Task ExistingStructureValidationExcludesTheUpdatedFileFromDuplicateCheck()
    {
        await using var host = await StartAsync();
        var structure = Structure("starter", "1000");
        using var createResponse = await host.Client.PostAsJsonAsync("/api/structures", structure);
        Assert.Equal(HttpStatusCode.Created, createResponse.StatusCode);

        using var updateValidationResponse = await host.Client.PostAsJsonAsync(
            "/api/validation/structure?existingStructureId=starter",
            structure);
        Assert.Equal(HttpStatusCode.OK, updateValidationResponse.StatusCode);
        var updateValidation = JsonNode.Parse(await updateValidationResponse.Content.ReadAsStringAsync())!;
        Assert.True(updateValidation["isValid"]!.GetValue<bool>());
        Assert.DoesNotContain(
            updateValidation["issues"]!.AsArray(),
            issue => issue!["code"]!.GetValue<string>() == "DUPLICATE_STRUCTURE_ID");

        using var createValidationResponse = await host.Client.PostAsJsonAsync(
            "/api/validation/structure",
            structure);
        Assert.Equal(HttpStatusCode.OK, createValidationResponse.StatusCode);
        var createValidation = JsonNode.Parse(await createValidationResponse.Content.ReadAsStringAsync())!;
        Assert.False(createValidation["isValid"]!.GetValue<bool>());
        Assert.Contains(
            createValidation["issues"]!.AsArray(),
            issue => issue!["code"]!.GetValue<string>() == "DUPLICATE_STRUCTURE_ID");
    }

    [Fact]
    public async Task NodeSaveRejectsUnknownAndWrongTargetMasterTags()
    {
        await using var host = await StartAsync();
        var unknown = Node("1000", "Unknown tag");
        unknown["tags"] = new JsonArray("not_defined");

        using var unknownResponse = await PutCurrentAsync(host.Client, "/api/nodes/1000", unknown);
        Assert.Equal(HttpStatusCode.UnprocessableEntity, unknownResponse.StatusCode);
        Assert.Contains("UNKNOWN_MASTER_TAG", await unknownResponse.Content.ReadAsStringAsync());

        var wrongTarget = Node("1000", "Wrong target");
        wrongTarget["tags"] = new JsonArray("active");
        using var wrongTargetResponse = await PutCurrentAsync(host.Client, "/api/nodes/1000", wrongTarget);
        Assert.Equal(HttpStatusCode.UnprocessableEntity, wrongTargetResponse.StatusCode);
        Assert.Contains("MASTER_TAG_TARGET_INVALID", await wrongTargetResponse.Content.ReadAsStringAsync());
    }

    [Fact]
    public async Task NodeSaveWithoutLoreDoesNotIntroduceIt()
    {
        await using var host = await StartAsync();
        var node = Node("1000", "Without lore");

        using var response = await PutCurrentAsync(host.Client, "/api/nodes/1000", node);

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        var saved = JsonNode.Parse(await response.Content.ReadAsStringAsync())!.AsObject();
        Assert.False(saved.ContainsKey("lore"));
        var persisted = JsonNode.Parse(
            await File.ReadAllTextAsync(Path.Combine(host.Paths.Nodes, "1000.json"))
        )!.AsObject();
        Assert.False(persisted.ContainsKey("lore"));
    }

    [Fact]
    public async Task NodeSaveRejectsDeprecatedLore()
    {
        await using var host = await StartAsync();
        var node = Node("1000", "Deprecated lore");
        node["lore"] = new JsonArray("Old node description");

        using var response = await PutCurrentAsync(host.Client, "/api/nodes/1000", node);

        Assert.Equal(HttpStatusCode.UnprocessableEntity, response.StatusCode);
    }

    private static async Task<HttpResponseMessage> PutCurrentAsync(HttpClient client, string url, JsonObject content)
    {
        using var original = await client.GetAsync(url);
        using var request = new HttpRequestMessage(HttpMethod.Put, url) { Content = JsonContent.Create(content) };
        request.Headers.TryAddWithoutValidation("If-Match", original.Headers.ETag!.ToString());
        return await client.SendAsync(request);
    }

    private static async Task<HttpResponseMessage> DeleteCurrentAsync(HttpClient client, string url)
    {
        using var original = await client.GetAsync(url);
        using var request = new HttpRequestMessage(HttpMethod.Delete, url);
        request.Headers.TryAddWithoutValidation("If-Match", original.Headers.ETag!.ToString());
        return await client.SendAsync(request);
    }

    private async Task<RunningEditor> StartAsync()
    {
        PrepareWorkspace();
        var paths = new WorkspacePaths(_workspace);
        var backups = new BackupService(paths);
        var repository = new FilebaseRepository(paths, backups);
        var schemas = new SchemaCatalog(paths);
        var pluginConfig = new PluginConfigService(paths, backups);
        var masterTags = new MasterTagCatalog(paths);
        var validation = new ValidationService(paths, schemas, pluginConfig, masterTags);
        var gate = new WorkspaceMutationGate(paths);

        var builder = WebApplication.CreateBuilder(new WebApplicationOptions
        {
            ContentRootPath = _workspace,
            EnvironmentName = Environments.Development
        });
        builder.WebHost.UseUrls("http://127.0.0.1:0");
        builder.Services.AddSingleton(paths);
        builder.Services.AddSingleton(backups);
        builder.Services.AddSingleton(repository);
        builder.Services.AddSingleton(schemas);
        builder.Services.AddSingleton(pluginConfig);
        builder.Services.AddSingleton(validation);
        builder.Services.AddSingleton(gate);
        builder.Services.AddSingleton(new SkillMasterCatalog(paths));
        builder.Services.AddSingleton(masterTags);

        var app = builder.Build();
        app.MapEditorEndpoints();
        await app.StartAsync();
        var address = app.Services.GetRequiredService<IServer>()
            .Features.Get<IServerAddressesFeature>()!
            .Addresses.Single();
        return new RunningEditor(app, new HttpClient { BaseAddress = new Uri(address) }, paths, gate);
    }

    private void PrepareWorkspace()
    {
        var paths = new WorkspacePaths(_workspace);
        Directory.CreateDirectory(paths.Nodes);
        Directory.CreateDirectory(paths.Structures);
        Directory.CreateDirectory(paths.Schemas);
        Directory.CreateDirectory(Path.GetDirectoryName(paths.PluginConfig)!);
        Directory.CreateDirectory(Path.GetDirectoryName(paths.TagCatalog)!);
        var sourceRoot = WorkspacePaths.ResolveWorkspaceRoot(null, AppContext.BaseDirectory);
        var sourceSchemas = Path.Combine(sourceRoot, "40_filebase", "35.features.skilltree", "schemas");
        File.Copy(
            Path.Combine(sourceSchemas, "node.v1.schema.json"),
            Path.Combine(paths.Schemas, "node.v1.schema.json"));
        File.Copy(
            Path.Combine(sourceSchemas, "structure.v1.schema.json"),
            Path.Combine(paths.Schemas, "structure.v1.schema.json"));
        File.Copy(
            Path.Combine(sourceSchemas, "node-id-sequence.v1.schema.json"),
            Path.Combine(paths.Schemas, "node-id-sequence.v1.schema.json"));
        File.Copy(
            Path.Combine(sourceRoot, "40_filebase", "76.shared.tag", "v1.tags.yml"),
            paths.TagCatalog);
        File.WriteAllText(Path.Combine(paths.Nodes, "1000.json"), StableJson.Serialize(Node("1000", "Node")));
        File.WriteAllText(paths.NodeIdSequence, StableJson.Serialize(new JsonObject
        {
            ["$schema"] = "./schemas/node-id-sequence.v1.schema.json",
            ["schemaVersion"] = 1,
            ["lastIssuedNodeId"] = "1000"
        }));
        File.WriteAllText(paths.PluginConfig,
            "skilltree:\n  worldName: \"world\"\n  structureId: \"other\"\n  center:\n    x: 0\n    y: 0\n    z: 0\n");
    }

    private static JsonObject Node(string nodeId, string name) => new()
    {
        ["$schema"] = "../schemas/node.v1.schema.json",
        ["schemaVersion"] = 1,
        ["nodeId"] = nodeId,
        ["name"] = name,
        ["icon"] = "STONE",
        ["tags"] = new JsonArray(),
        ["pointType"] = "CP",
        ["pointCost"] = 1,
        ["effects"] = new JsonArray()
    };

    private static JsonObject Structure(string structureId, string nodeId) => new()
    {
        ["$schema"] = "../schemas/structure.v1.schema.json",
        ["schemaVersion"] = 1,
        ["structureId"] = structureId,
        ["name"] = "Main",
        ["rootNodeId"] = nodeId,
        ["nodes"] = new JsonArray(new JsonObject
        {
            ["nodeId"] = nodeId,
            ["x"] = 0,
            ["y"] = 0,
            ["z"] = 0
        }),
        ["edges"] = new JsonArray()
    };

    public void Dispose()
    {
        if (Directory.Exists(_workspace))
            Directory.Delete(_workspace, recursive: true);
    }

    private sealed class RunningEditor(
        WebApplication app,
        HttpClient client,
        WorkspacePaths paths,
        WorkspaceMutationGate gate) : IAsyncDisposable
    {
        public HttpClient Client { get; } = client;
        public WorkspacePaths Paths { get; } = paths;
        public WorkspaceMutationGate Gate { get; } = gate;

        public async ValueTask DisposeAsync()
        {
            Client.Dispose();
            await app.StopAsync();
            await app.DisposeAsync();
        }
    }
}
