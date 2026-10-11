using System.Text.Json.Nodes;
using SkillTreeEditor.Server.Services;

namespace SkillTreeEditor.Server.Tests;

public sealed class BossMasterEditorTests : IDisposable
{
    private const string BossPath = "40.features.mob/boss/v1.selenea_eclipse_witch.yml";
    private readonly string _root = Path.Combine(Path.GetTempPath(), "boss-master-editor-tests-" + Guid.NewGuid().ToString("N"));
    private readonly MasterDataPaths _paths;
    private readonly MasterDataCatalog _catalog;
    private readonly MasterDataValidation _validation;
    private readonly MasterDataService _service;
    private readonly MasterDataCodec _codec = new();

    public BossMasterEditorTests()
    {
        foreach (var directory in new[] { "40_filebase", "10_plugin", "60_tool" })
            Directory.CreateDirectory(Path.Combine(_root, directory));
        var workspace = new WorkspacePaths(_root);
        _paths = new(workspace);
        _catalog = new(_paths);
        var backups = new BackupService(workspace);
        var treeValidation = new ValidationService(workspace, new SchemaCatalog(workspace),
            new PluginConfigService(workspace, backups), new MasterTagCatalog(workspace));
        _validation = new(_paths, _codec, _catalog, treeValidation);
        _service = new(_paths, workspace, _codec, _validation, new WorkspaceMutationGate(workspace), backups);
        var sourceRoot = WorkspacePaths.ResolveWorkspaceRoot(null, AppContext.BaseDirectory);
        foreach (var path in new[] { BossPath, "40.features.mob/docs.mob.YAMLスキーマ定義.md",
            "40.features.mob/boss/docs.boss.YAMLスキーマ定義.md", "65.features.dungeon/docs.dungeon.YAMLスキーマ定義.md" })
        {
            var destination = Path.Combine(_paths.Root, path);
            Directory.CreateDirectory(Path.GetDirectoryName(destination)!);
            File.Copy(Path.Combine(sourceRoot, "40_filebase", path), destination);
        }
    }

    [Fact]
    public async Task BossEntryCoordinatesCanBeSavedWithoutLosingCommentsOrOtherLocations()
    {
        var original = await _service.ReadAsync(BossPath, CancellationToken.None);
        var content = original.Content!.DeepClone();
        var entry = content["challenge"]!["entryLocation"]!;
        entry["x"] = -1052.5;
        entry["y"] = 118;
        entry["z"] = 806.5;
        var edited = _codec.Render(new(BossPath, content, original.Raw));
        var report = await _validation.ValidateAsync(BossPath, edited.Raw, CancellationToken.None);

        Assert.True(report.IsValid, string.Join(Environment.NewLine,
            report.Issues.Select(issue => $"{issue.Code}: {issue.Path} {issue.Message}")));
        var saved = await _service.SaveAsync(new(BossPath, edited.Raw, original.Revision), CancellationToken.None);
        Assert.Equal(-1052.5, JsonValueReader.Number(saved.Content!["challenge"]!["entryLocation"]!["x"]));
        Assert.Equal(118, JsonValueReader.Number(saved.Content["challenge"]!["entryLocation"]!["y"]));
        Assert.Equal(806.5, JsonValueReader.Number(saved.Content["challenge"]!["entryLocation"]!["z"]));
        Assert.True(JsonNode.DeepEquals(original.Content["challenge"]!["playerSpawnLocation"], saved.Content["challenge"]!["playerSpawnLocation"]));
        Assert.True(JsonNode.DeepEquals(original.Content["challenge"]!["bossSpawnLocation"], saved.Content["challenge"]!["bossSpawnLocation"]));
        Assert.True(edited.CommentsPreserved);
        Assert.Contains("# design:", saved.Raw);
    }

    [Theory]
    [InlineData("entryLocation", "x")]
    [InlineData("entryLocation", "y")]
    [InlineData("entryLocation", "z")]
    [InlineData("playerSpawnLocation", "x")]
    [InlineData("playerSpawnLocation", "y")]
    [InlineData("playerSpawnLocation", "z")]
    [InlineData("bossSpawnLocation", "x")]
    [InlineData("bossSpawnLocation", "y")]
    [InlineData("bossSpawnLocation", "z")]
    public async Task EachBossCoordinateIsRequiredAndMustBeNumeric(string location, string axis)
    {
        var original = await _service.ReadAsync(BossPath, CancellationToken.None);
        var content = original.Content!.DeepClone();
        var coordinates = (JsonObject)content["challenge"]![location]!;
        var pointer = $"/challenge/{location}/{axis}";
        coordinates.Remove(axis);
        var missing = await _validation.ValidateAsync(BossPath, content.ToJsonString(), CancellationToken.None);
        Assert.Contains(missing.Issues, issue => issue.Code == "FIELD_REQUIRED" && issue.Path == pointer);

        coordinates[axis] = null;
        var nullValue = await _validation.ValidateAsync(BossPath, content.ToJsonString(), CancellationToken.None);
        Assert.Contains(nullValue.Issues, issue => issue.Code == "FIELD_REQUIRED" && issue.Path == pointer);

        coordinates[axis] = "118";
        var wrongType = await _validation.ValidateAsync(BossPath, content.ToJsonString(), CancellationToken.None);
        Assert.Contains(wrongType.Issues, issue => issue.Code == "FIELD_TYPE" && issue.Path == pointer);
    }

    [Theory]
    [InlineData("yaw")]
    [InlineData("pitch")]
    public async Task EntryOrientationIsOptionalAndMustBeNumericWhenPresent(string axis)
    {
        var original = await _service.ReadAsync(BossPath, CancellationToken.None);
        var content = original.Content!.DeepClone();
        var entry = (JsonObject)content["challenge"]!["entryLocation"]!;
        entry.Remove("yaw");
        entry.Remove("pitch");
        var absent = await _validation.ValidateAsync(BossPath, content.ToJsonString(), CancellationToken.None);
        Assert.True(absent.IsValid);

        entry[axis] = "0";
        var wrongType = await _validation.ValidateAsync(BossPath, content.ToJsonString(), CancellationToken.None);
        Assert.Contains(wrongType.Issues, issue => issue.Code == "FIELD_TYPE" && issue.Path == $"/challenge/entryLocation/{axis}");
    }

    [Fact]
    public async Task DungeonGroupedFieldsResolveToSiblingKeysAndKeepTheirTypes()
    {
        var category = await _catalog.GetAsync("65.features.dungeon", CancellationToken.None);
        foreach (var axis in new[] { "x", "y", "z", "yaw", "pitch" })
            Assert.Contains(category.Fields, field => field.Path == "/entry/" + axis && field.Type == "Double");
        foreach (var dimension in new[] { "width", "depth" })
            Assert.Contains(category.Fields, field => field.Path == "/generation/area/" + dimension && field.Type == "Integer");
        foreach (var surface in new[] { "floor", "wall", "ceiling" })
            Assert.Contains(category.Fields, field => field.Path == "/theme/" + surface && field.Type == "List");
        Assert.DoesNotContain(category.Fields, field => field.Path.Contains("~1", StringComparison.Ordinal));
    }

    public void Dispose() => Directory.Delete(_root, recursive: true);
}
