using System.Text.Json.Nodes;
using SkillTreeEditor.Server.Models;
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

    [Fact]
    public async Task BossRecommendedPlayerLevelCanBeSavedSeparatelyFromCombatLevel()
    {
        var category = await _catalog.GetAsync("40.features.mob/boss", CancellationToken.None);
        Assert.Contains(category.Fields, field => field.Path == "/challenge/recommendedPlayerLevel"
            && field.Label == "推奨プレイヤーレベル" && field.Type == "Integer" && !field.Required);

        var original = await _service.ReadAsync(BossPath, CancellationToken.None);
        var content = original.Content!.DeepClone();
        var challenge = (JsonObject)content["challenge"]!;
        challenge.Remove("recommendedPlayerLevel");
        Assert.True((await _validation.ValidateAsync(BossPath, content.ToJsonString(), CancellationToken.None)).IsValid);

        challenge["recommendedPlayerLevel"] = 0;
        var invalid = await _validation.ValidateAsync(BossPath, content.ToJsonString(), CancellationToken.None);
        Assert.Contains(invalid.Issues, issue => issue.Code == "BOSS_RECOMMENDED_PLAYER_LEVEL"
            && issue.Path == "/challenge/recommendedPlayerLevel");

        challenge["recommendedPlayerLevel"] = 28;
        var rendered = _codec.Render(new(BossPath, content, original.Raw));
        var saved = await _service.SaveAsync(new(BossPath, rendered.Raw, original.Revision), CancellationToken.None);
        Assert.Equal(28, JsonValueReader.Number(saved.Content!["challenge"]!["recommendedPlayerLevel"]));
        Assert.True(JsonNode.DeepEquals(original.Content["level"], saved.Content["level"]));
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
    public async Task MapEditRejectsAnUnavailableBossEntryWorldWithoutOverwritingTheMaster()
    {
        var original = await _service.ReadAsync(BossPath, CancellationToken.None);
        var content = original.Content!.DeepClone();
        var entry = content["challenge"]!["entryLocation"]!;
        entry["worldId"] = "selected_world";
        var edited = _codec.Render(new(BossPath, content, original.Raw));
        var worldRoot = Path.Combine(_paths.Root, "60.features.world");
        Directory.CreateDirectory(worldRoot);
        var error = await Assert.ThrowsAsync<MasterDataValidationException>(() =>
            _service.SaveAsync(new(BossPath, edited.Raw, original.Revision, RequireBossEntryWorldMaster: true), CancellationToken.None));
        Assert.Contains(error.Report.Issues, issue => issue.Path == "/challenge/entryLocation/worldId");
        Assert.Equal(original.Revision, (await _service.ReadAsync(BossPath, CancellationToken.None)).Revision);

        File.WriteAllText(Path.Combine(worldRoot, "v1.selected_world.yml"), "id: selected_world\ndisplayName: 選択したワールド\n");
        var saved = await _service.SaveAsync(new(BossPath, edited.Raw, original.Revision, RequireBossEntryWorldMaster: true), CancellationToken.None);
        Assert.Equal("selected_world", saved.Content!["challenge"]!["entryLocation"]!["worldId"]!.GetValue<string>());
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
        Assert.Contains(category.Fields, field => field.Path == "/entry/worldRef" && field.Reference == "world");
        Assert.Contains(category.Fields, field => field.Path == "/encounter/bossMobId" && field.Reference == "mob");
        Assert.Contains(category.Fields, field => field.Path == "/encounter/normalMobPool/*/mobId" && field.Reference == "mob");
        Assert.Contains(category.Fields, field => field.Path == "/clearRewards/lootTable" && field.Reference == "loot_table");
        Assert.DoesNotContain(category.Fields, field => field.Path.Contains("~1", StringComparison.Ordinal));
    }

    [Fact]
    public async Task ChallengeEntriesReadSavedBossAndDungeonCoordinatesForOneLogicalWorld()
    {
        const string worldId = "eriva_supercontinent";
        await WriteMasterAsync("60.features.world/v1.eriva.yml", $"id: {worldId}\nbaseWorldPath: ' ./worlds//legacy_eriva '\n");
        await WriteMasterAsync("60.features.world/v1.other.yml", "id: other\nbaseWorldPath: worlds/other\n");
        await WriteMasterAsync("40.features.mob/boss/v1.legacy.yml", """
            id: legacy
            challenge:
              entryLocation: { worldId: legacy_eriva, x: 1.5, y: -4, z: 0 }
            """);
        await WriteMasterAsync("40.features.mob/boss/v1.path.yml", """
            id: path
            challenge:
              entryLocation: { worldId: 'worlds\legacy_eriva', x: -10, y: 2, z: 3 }
            """);
        await WriteMasterAsync("40.features.mob/boss/v1.malformed.yml", """
            id: malformed
            challenge:
              entryLocation: { worldId: eriva_supercontinent, x: "1", y: 2, z: 3 }
            """);
        await WriteMasterAsync("40.features.mob/boss/v1.unparseable.yml", "challenge: [");
        await WriteMasterAsync("65.features.dungeon/v1.dungeon.yml", """
            id: dungeon
            name: 大樹の遺跡
            entry: { worldRef: "world:eriva_supercontinent", x: -6.5, y: 5, z: 4 }
            """);
        await WriteMasterAsync("65.features.dungeon/v1.short_prefix.yml", """
            id: short_prefix
            entry: { worldRef: "wd:eriva_supercontinent", x: -7, y: 8, z: 9 }
            """);
        await WriteMasterAsync("65.features.dungeon/v1.wrong_prefix.yml", """
            id: wrong_prefix
            entry: { worldRef: "mob:eriva_supercontinent", x: 1, y: 2, z: 3 }
            """);
        await WriteMasterAsync("65.features.dungeon/v1.other.yml", """
            id: other_dungeon
            entry: { worldRef: "world:other", x: 1, y: 2, z: 3 }
            """);

        var entries = await _service.ListChallengeEntriesAsync(worldId, CancellationToken.None);

        Assert.Equal(new ChallengeEntryMarker[]
        {
            new("40.features.mob/boss/v1.legacy.yml", "boss", "legacy", "legacy_eriva", 1.5, -4, 0),
            new("40.features.mob/boss/v1.path.yml", "boss", "path", @"worlds\legacy_eriva", -10, 2, 3),
            new(BossPath, "boss", "&5&l月蝕の綴り魔女 セレネア", worldId, -1047.5, 118, 807.5),
            new("65.features.dungeon/v1.dungeon.yml", "dungeon", "大樹の遺跡", "world:eriva_supercontinent", -6.5, 5, 4),
            new("65.features.dungeon/v1.short_prefix.yml", "dungeon", "short_prefix", "wd:eriva_supercontinent", -7, 8, 9)
        }, entries);
        Assert.Equal("other_dungeon", Assert.Single(await _service.ListChallengeEntriesAsync("other", CancellationToken.None)).Name);
        Assert.Empty(await _service.ListChallengeEntriesAsync("legacy_eriva", CancellationToken.None));
    }

    [Fact]
    public async Task AmbiguousBossWorldAliasesAreIgnoredAndLogicalIdsTakePrecedence()
    {
        await WriteMasterAsync("60.features.world/v1.first.yml", "id: first\nbaseWorldPath: worlds/shared\n");
        await WriteMasterAsync("60.features.world/v1.second.yml", "id: second\nbaseWorldPath: test/shared\n");
        await WriteMasterAsync("60.features.world/v1.third.yml", "id: third\nbaseWorldPath: worlds/first\n");
        await WriteMasterAsync("40.features.mob/boss/v1.ambiguous.yml", """
            id: ambiguous
            challenge:
              entryLocation: { worldId: shared, x: 1, y: 2, z: 3 }
            """);
        await WriteMasterAsync("40.features.mob/boss/v1.logical.yml", """
            id: logical
            challenge:
              entryLocation: { worldId: first, x: 1, y: 2, z: 3 }
            """);

        Assert.Equal("logical", Assert.Single(await _service.ListChallengeEntriesAsync("first", CancellationToken.None)).Name);
        Assert.Empty(await _service.ListChallengeEntriesAsync("second", CancellationToken.None));
        Assert.Empty(await _service.ListChallengeEntriesAsync("third", CancellationToken.None));
    }

    private Task WriteMasterAsync(string path, string raw)
    {
        var destination = Path.Combine(_paths.Root, path);
        Directory.CreateDirectory(Path.GetDirectoryName(destination)!);
        return File.WriteAllTextAsync(destination, raw);
    }

    public void Dispose() => Directory.Delete(_root, recursive: true);
}
