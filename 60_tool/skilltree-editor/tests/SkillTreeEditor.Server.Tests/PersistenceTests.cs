using System.Text;
using System.Text.Json.Nodes;
using SkillTreeEditor.Server.Models;
using SkillTreeEditor.Server.Services;

namespace SkillTreeEditor.Server.Tests;

public sealed class PersistenceTests : IDisposable
{
    private readonly string _workspace = Path.Combine(Path.GetTempPath(), $"skilltree-editor-tests-{Guid.NewGuid():N}");

    [Fact]
    public async Task SaveNodeCreatesTimestampedBackupAndStableLfJson()
    {
        CreateWorkspace();
        var paths = new WorkspacePaths(_workspace);
        var repository = new FilebaseRepository(paths, new BackupService(paths));
        var original = Node("1000", "Original");
        await repository.CreateNodeAsync(original, CancellationToken.None);

        var changed = Node("1000", "Changed");
        await repository.SaveNodeAsync("1000", changed, CancellationToken.None);

        var savedPath = Path.Combine(paths.Nodes, "1000.json");
        var saved = await File.ReadAllTextAsync(savedPath, Encoding.UTF8);
        Assert.DoesNotContain("\r\n", saved);
        Assert.EndsWith("\n", saved);
        Assert.Contains("\"name\": \"Changed\"", saved);
        var backups = Directory.GetFiles(Path.Combine(paths.Backups, "nodes"), "1000.json.*.bak");
        Assert.Single(backups);
        Assert.Contains("\"name\": \"Original\"", await File.ReadAllTextAsync(backups[0], Encoding.UTF8));
    }

    [Fact]
    public async Task StableJsonUsesCanonicalOrderAndNoOpSaveDoesNotCreateBackup()
    {
        CreateWorkspace();
        var paths = new WorkspacePaths(_workspace);
        var repository = new FilebaseRepository(paths, new BackupService(paths));
        var node = Node("1000", "Canonical");
        await repository.CreateNodeAsync(node, CancellationToken.None);

        await repository.SaveNodeAsync("1000", node, CancellationToken.None);

        var saved = await File.ReadAllTextAsync(Path.Combine(paths.Nodes, "1000.json"), Encoding.UTF8);
        Assert.True(saved.IndexOf("\"$schema\"", StringComparison.Ordinal) < saved.IndexOf("\"schemaVersion\"", StringComparison.Ordinal));
        Assert.True(saved.IndexOf("\"schemaVersion\"", StringComparison.Ordinal) < saved.IndexOf("\"nodeId\"", StringComparison.Ordinal));
        Assert.False(Directory.Exists(Path.Combine(paths.Backups, "nodes")));
        Assert.Equal(saved, StableJson.Serialize(JsonNode.Parse(saved)!));
    }

    [Fact]
    public async Task DeletedHighestNodeIdIsNotReusedBecauseSequenceRemainsAdvanced()
    {
        CreateWorkspace();
        var paths = new WorkspacePaths(_workspace);
        var repository = new FilebaseRepository(paths, new BackupService(paths));
        await repository.CreateNodeAsync(Node("1000", "First"), CancellationToken.None);

        await repository.DeleteNodeAsync("1000", CancellationToken.None);

        Assert.Equal("1001", await repository.GetNextNodeIdAsync(CancellationToken.None));
        var second = await repository.CreateNodeAsync(Node("", "Second"), CancellationToken.None);
        Assert.Equal("1001", JsonValueReader.String(second.Content["nodeId"]));
        Assert.Equal("1001", JsonValueReader.String(
            JsonNode.Parse(await File.ReadAllTextAsync(paths.NodeIdSequence))?["lastIssuedNodeId"]));
        Assert.Equal(2, Directory.GetFiles(Path.Combine(paths.Backups, "node-id-sequence"), "node-id-sequence.json.*.bak").Length);
    }

    [Fact]
    public async Task NodeIdAllocationSupportsTheFullHundredDigitContract()
    {
        CreateWorkspace();
        var paths = new WorkspacePaths(_workspace);
        Directory.CreateDirectory(paths.SkillTreeRoot);
        var issued = new string('9', 99);
        await File.WriteAllTextAsync(paths.NodeIdSequence, $$"""
            { "$schema": "./schemas/node-id-sequence.v1.schema.json", "schemaVersion": 1, "lastIssuedNodeId": "{{issued}}" }
            """);
        var repository = new FilebaseRepository(paths, new BackupService(paths));

        Assert.Equal("1" + new string('0', 99), await repository.GetNextNodeIdAsync(CancellationToken.None));
    }

    [Fact]
    public async Task ConcurrentNodeCreatesReserveDistinctIdsInOrder()
    {
        CreateWorkspace();
        var paths = new WorkspacePaths(_workspace);
        var repository = new FilebaseRepository(paths, new BackupService(paths));

        var created = await Task.WhenAll(Enumerable.Range(0, 10)
            .Select(index => repository.CreateNodeAsync(Node("", $"Node {index}"), CancellationToken.None)));
        var ids = created
            .Select(document => JsonValueReader.String(document.Content["nodeId"]))
            .OrderBy(value => value, StringComparer.Ordinal)
            .ToArray();

        Assert.Equal(Enumerable.Range(1000, 10).Select(value => value.ToString()).ToArray(), ids);
        var sequence = JsonNode.Parse(await File.ReadAllTextAsync(paths.NodeIdSequence))!.AsObject();
        Assert.Equal("1009", JsonValueReader.String(sequence["lastIssuedNodeId"]));
    }

    [Fact]
    public async Task ConcurrentRepositoryInstancesCannotRegressNodeIdHighWater()
    {
        CreateWorkspace();
        var paths = new WorkspacePaths(_workspace);
        var repositories = Enumerable.Range(0, 12)
            .Select(_ => new FilebaseRepository(paths, new BackupService(paths)))
            .ToArray();

        var created = await Task.WhenAll(repositories.Select((repository, index) =>
            repository.CreateNodeAsync(Node("", $"Node {index}"), CancellationToken.None)));

        var ids = created
            .Select(document => int.Parse(JsonValueReader.String(document.Content["nodeId"])!))
            .OrderBy(value => value)
            .ToArray();
        Assert.Equal(Enumerable.Range(1000, 12).ToArray(), ids);
        Assert.Equal("1011", JsonValueReader.String(
            JsonNode.Parse(await File.ReadAllTextAsync(paths.NodeIdSequence))?["lastIssuedNodeId"]));
    }

    [Fact]
    public async Task MissingOrBehindSequenceRefusesAllocation()
    {
        CreateWorkspace();
        var paths = new WorkspacePaths(_workspace);
        var repository = new FilebaseRepository(paths, new BackupService(paths));
        await repository.CreateNodeAsync(Node("", "First"), CancellationToken.None);
        await File.WriteAllTextAsync(paths.NodeIdSequence, """
            { "$schema": "./schemas/node-id-sequence.v1.schema.json", "schemaVersion": 1, "lastIssuedNodeId": "999" }
            """);

        await Assert.ThrowsAsync<InvalidDataException>(() => repository.GetNextNodeIdAsync(CancellationToken.None));
        File.Delete(paths.NodeIdSequence);
        await Assert.ThrowsAsync<InvalidDataException>(() => repository.CreateNodeAsync(Node("", "Second"), CancellationToken.None));
    }

    [Fact]
    public async Task PluginConfigSaveOnlyReplacesSkillTreeBlock()
    {
        CreateWorkspace();
        var paths = new WorkspacePaths(_workspace);
        Directory.CreateDirectory(Path.GetDirectoryName(paths.PluginConfig)!);
        await File.WriteAllTextAsync(paths.PluginConfig, "plugin:\n  debugMode: false\n\nskilltree:\n  worldName: \"old\"\n  structureId: \"old\"\n  center:\n    x: 1\n    y: 2\n    z: 3\n\nlogging:\n  enabled: true\n");
        var service = new PluginConfigService(paths, new BackupService(paths));

        await service.SaveAsync(new PluginSkillTreeSettings("world", "main", 10, 20, 30), CancellationToken.None);

        var updated = await File.ReadAllTextAsync(paths.PluginConfig);
        Assert.Contains("plugin:\n  debugMode: false", updated);
        Assert.Contains("logging:\n  enabled: true", updated);
        Assert.Contains("  worldName: \"world\"", updated);
        Assert.Contains("  structureId: \"main\"", updated);
        Assert.Contains("    z: 30", updated);
        Assert.Single(Directory.GetFiles(Path.Combine(paths.Backups, "plugin-config"), "config.yml.*.bak"));
    }

    [Fact]
    public async Task PluginConfigPreservesQuotedHashCommentsCrlfAndFollowingScalarKey()
    {
        CreateWorkspace();
        var paths = new WorkspacePaths(_workspace);
        Directory.CreateDirectory(Path.GetDirectoryName(paths.PluginConfig)!);
        var original = string.Join("\r\n",
        [
            "plugin:",
            "  debugMode: false",
            string.Empty,
            "skilltree: # keep header comment",
            "  # keep skilltree comment",
            "  worldName: \"world#alpha\"  # keep world comment",
            "  structureId: \"starter\"",
            "  center:",
            "    # keep center comment",
            "    x: 1",
            "    y: 2",
            "    z: 3",
            string.Empty,
            "featureFlag: true # must not be consumed by skilltree",
            "boss:",
            "  enabled: true",
            string.Empty
        ]);
        await File.WriteAllTextAsync(paths.PluginConfig, original, new UTF8Encoding(false));
        var service = new PluginConfigService(paths, new BackupService(paths));

        var loaded = await service.ReadAsync(CancellationToken.None);
        Assert.Equal("world#alpha", loaded.WorldName);

        var settings = new PluginSkillTreeSettings("world#beta", "starter", 10, 20, 30);
        await service.SaveAsync(settings, CancellationToken.None);
        await service.SaveAsync(settings, CancellationToken.None);

        var updated = await File.ReadAllTextAsync(paths.PluginConfig, Encoding.UTF8);
        Assert.Contains("skilltree: # keep header comment\r\n", updated);
        Assert.Contains("  # keep skilltree comment\r\n", updated);
        Assert.Contains("  worldName: \"world#beta\"  # keep world comment\r\n", updated);
        Assert.Contains("    # keep center comment\r\n", updated);
        Assert.Contains("featureFlag: true # must not be consumed by skilltree\r\n", updated);
        Assert.Contains("boss:\r\n  enabled: true\r\n", updated);
        Assert.DoesNotContain("\n", updated.Replace("\r\n", string.Empty, StringComparison.Ordinal));
        Assert.Single(Directory.GetFiles(Path.Combine(paths.Backups, "plugin-config"), "config.yml.*.bak"));
    }

    [Theory]
    [MemberData(nameof(YamlForbiddenWorldNames))]
    public async Task PluginConfigRejectsYamlForbiddenWorldNameCharacters(string worldName)
    {
        CreateWorkspace();
        var paths = new WorkspacePaths(_workspace);
        Directory.CreateDirectory(Path.GetDirectoryName(paths.PluginConfig)!);
        const string original = "skilltree:\n  worldName: \"world\"\n  structureId: \"starter\"\n  center:\n    x: 0\n    y: 0\n    z: 0\n";
        await File.WriteAllTextAsync(paths.PluginConfig, original);
        var service = new PluginConfigService(paths, new BackupService(paths));

        await Assert.ThrowsAsync<ArgumentException>(() => service.SaveAsync(
            new PluginSkillTreeSettings(worldName, "starter", 0, 0, 0),
            CancellationToken.None));

        Assert.Equal(original, await File.ReadAllTextAsync(paths.PluginConfig));
        Assert.False(Directory.Exists(Path.Combine(paths.Backups, "plugin-config")));
    }

    public static TheoryData<string> YamlForbiddenWorldNames => new()
    {
        "world\0name",
        "world\u0085name",
        "world\u2028name"
    };

    public void Dispose()
    {
        if (Directory.Exists(_workspace))
            Directory.Delete(_workspace, recursive: true);
    }

    private void CreateWorkspace()
    {
        Directory.CreateDirectory(Path.Combine(_workspace, "40_filebase"));
        Directory.CreateDirectory(Path.Combine(_workspace, "10_plugin"));
        Directory.CreateDirectory(Path.Combine(_workspace, "60_tool"));
        var paths = new WorkspacePaths(_workspace);
        Directory.CreateDirectory(paths.SkillTreeRoot);
        File.WriteAllText(paths.NodeIdSequence, """
            {
              "$schema": "./schemas/node-id-sequence.v1.schema.json",
              "schemaVersion": 1,
              "lastIssuedNodeId": "999"
            }
            """);
    }

    private static JsonObject Node(string nodeId, string name) => new()
    {
        ["$schema"] = "../schemas/node.v1.schema.json",
        ["schemaVersion"] = 1,
        ["nodeId"] = nodeId,
        ["name"] = name,
        ["icon"] = new JsonObject(),
        ["lore"] = new JsonArray(),
        ["tags"] = new JsonArray(),
        ["pointType"] = "skill",
        ["pointCost"] = 1,
        ["effects"] = new JsonArray()
    };
}
