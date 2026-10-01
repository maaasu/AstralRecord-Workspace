using System.Text.Json.Nodes;
using SkillTreeEditor.Server.Models;
using SkillTreeEditor.Server.Services;

namespace SkillTreeEditor.Server.Tests;

/// <summary>55-pet編集契約。固定fixtureだけで単一保存・遺伝制約・種類/素材/施設のbindingを確認する。</summary>
public sealed class PetMasterEditorTests : IDisposable
{
    private const string PetPath = "55.features.pet/v1.pets.yml";
    private readonly string _root = Path.Combine(Path.GetTempPath(), "pet-editor-tests-" + Guid.NewGuid().ToString("N"));
    private readonly MasterDataCodec _codec = new();
    private readonly MasterDataCatalog _catalog;
    private readonly MasterDataValidation _validation;
    private readonly MasterDataService _service;
    private readonly MasterDataPaths _paths;
    private readonly JsonObject _master = CreateMaster();

    public PetMasterEditorTests()
    {
        Directory.CreateDirectory(Path.Combine(_root, "40_filebase"));
        Directory.CreateDirectory(Path.Combine(_root, "10_plugin"));
        Directory.CreateDirectory(Path.Combine(_root, "60_tool"));
        var workspace = new WorkspacePaths(_root); _paths = new(workspace);
        var backups = new BackupService(workspace);
        var treeValidation = new ValidationService(workspace, new SchemaCatalog(workspace), new PluginConfigService(workspace, backups), new MasterTagCatalog(workspace));
        _catalog = new(_paths); _validation = new(_paths, _codec, _catalog, treeValidation);
        _service = new(_paths, workspace, _codec, _validation, new WorkspaceMutationGate(workspace), backups);
        Write("config.yml", "databases:\n  - name: pet\n    path: 55.features.pet\nreferenceResolver:\n  - prefix: 'item:'\n    database: item\n  - prefix: 'mob:'\n    database: mob\n");
        Write("55.features.pet/schemas/pet-master.v1.schema.json", """
            {"title":"ペットマスター","type":"object","additionalProperties":false,
             "required":["schemaVersion","id","rules","experience","species"],"properties":{
             "schemaVersion":{"const":1},"id":{"const":"pets"},
             "rules":{"type":"object","title":"ペット共通ルール","properties":{
                 "maxLevel":{"type":"integer","title":"最大成長度"},
                 "reviveOrbItemId":{"type":"string","title":"復活オーブ","pattern":"^item:[0-9]{2}[a-z][0-9]{5}$"}}},
             "experience":{"type":"object"},"species":{"type":"array","items":{"type":"object","properties":{
                 "eggItemId":{"type":"string","title":"卵のアイテム参照","pattern":"^item:[0-9]{2}[a-z][0-9]{5}$"},
                 "skills":{"type":"array","items":{"type":"object","properties":{"trigger":{"enum":["OWNER_HIT","OWNER_DODGE","PERIODIC"],"title":"発動の起点"}}}}}}}}}
            """);
        for (var index = 1; index <= 3; index++)
        {
            WriteItem($"10.features.item/80.pet/v1.80a0000{index}.pet.yml", $"80a0000{index}", "pet", 1);
            WriteItem($"10.features.item/81.pet_egg/v1.81a0000{index}.egg.yml", $"81a0000{index}", "pet_egg", 1);
        }
        WriteItem("10.features.item/10.material/v1.10a00001.flower.yml", "10a00001", "material", 64);
        WriteItem("10.features.item/40.orb/v1.40a00020.revive.yml", "40a00020", "orb", 64, "orb:\n  effect:\n    type: PET_REVIVE\n");
        Write("40.features.mob/npc/v1.pet_center.yml", "schemaVersion: 1\nid: pet_center\ncategory: NPC\ninteractions:\n  rightClick:\n    - id: gui\n      params:\n        type: PET_CENTER\n");
        Write(PetPath, "# design: fixed fixture\n" + Raw(_master));
    }

    [Fact]
    public async Task CommittedPetMasterPassesEditorValidation()
    {
        var root = WorkspacePaths.ResolveWorkspaceRoot(null, AppContext.BaseDirectory);
        var workspace = new WorkspacePaths(root);
        var paths = new MasterDataPaths(workspace);
        var backups = new BackupService(workspace);
        var treeValidation = new ValidationService(workspace, new SchemaCatalog(workspace),
            new PluginConfigService(workspace, backups), new MasterTagCatalog(workspace));
        var codec = new MasterDataCodec();
        var validation = new MasterDataValidation(paths, codec, new MasterDataCatalog(paths), treeValidation);
        var raw = await File.ReadAllTextAsync(paths.Resolve(PetPath));

        var report = await validation.ValidateAsync(PetPath, raw, CancellationToken.None);

        Assert.True(report.IsValid, string.Join(Environment.NewLine,
            report.Issues.Select(issue => $"{issue.Code}: {issue.Path} {issue.Message}")));
    }

    [Fact]
    public async Task CatalogDynamicallyLoadsSingletonJapaneseSchemaFieldsAndReferenceCandidates()
    {
        var category = await _catalog.GetAsync("55.features.pet", CancellationToken.None);
        Assert.Equal("ペット", category.Label);
        Assert.Single(category.JsonSchemas); Assert.Single(category.Templates);
        Assert.Contains(category.Fields, field => field.Path == "/rules/maxLevel" && field.Label == "最大成長度" && field.Type == "integer");
        Assert.Contains(category.Fields, field => field.Path == "/species/*/eggItemId" && field.Reference == "item");
        Assert.Contains(category.Fields, field => field.Path == "/species/*/skills/*/trigger" && field.Enum?.Count == 3);
        Assert.DoesNotContain(category.Fields, field => field.Path == "/maxLevel");
        Assert.Equal("pet", MasterDataService.Kind(PetPath));
        Assert.Contains(await _service.CandidatesAsync(CancellationToken.None), reference => reference.Kind == "pet" && reference.Value == "pets");
    }

    [Theory]
    [InlineData("80.pet", "pet", "80")]
    [InlineData("81.pet_egg", "pet_egg", "81")]
    public async Task PetItemCreationUsesCategoryCodeAndRejectsMisclassification(string folder, string category, string code)
    {
        Assert.Equal(code, MasterDataCatalog.ItemCode("10.features.item/" + folder));
        var document = await _service.CreateAsync(new("10.features.item/" + folder,
            "schemaVersion: 1\nid: placeholder\ncategory: placeholder\nname: 新しい個体\nmaxStack: 1\n", AutoItemId: true, Slug: "new_pet"), CancellationToken.None);
        Assert.Equal(code + "a00004", document.Content!["id"]!.ToString());
        Assert.Equal(category, document.Content["category"]!.ToString());
        var invalid = await _validation.ValidateAsync(document.Path, document.Raw.Replace(category, "material", StringComparison.Ordinal), CancellationToken.None);
        Assert.Contains(invalid.Issues, issue => issue.Code == "ITEM_CATEGORY");
    }

    [Fact]
    public async Task FormRenderAndExplicitSavePreserveCommentsAndKeepSingleIdentity()
    {
        var original = await _service.ReadAsync(PetPath, CancellationToken.None);
        var edited = original.Content!.DeepClone(); edited["species"]![0]!["names"]![0] = "コハク";
        var rendered = _codec.Render(new(PetPath, edited, original.Raw));
        Assert.True(rendered.CommentsPreserved); Assert.Contains("# design: fixed fixture", rendered.Raw);
        var saved = await _service.SaveAsync(new(PetPath, rendered.Raw, original.Revision), CancellationToken.None);
        Assert.Equal("pets", saved.Content!["id"]!.ToString()); Assert.Equal("コハク", saved.Content["species"]![0]!["names"]![0]!.ToString());
        var source = await _service.ReadAsync(PetPath, CancellationToken.None);
        await Assert.ThrowsAsync<MasterDataValidationException>(() => _service.CopyAsync(new(PetPath, source.Revision, "55.features.pet/v1.second.yml"), CancellationToken.None));
        await Assert.ThrowsAsync<UnauthorizedAccessException>(() => _service.DeleteAsync(PetPath, source.Revision, CancellationToken.None));
    }

    [Theory]
    [InlineData("tier_cap", "PET_TIER_BOUNDS")]
    [InlineData("slots", "PET_GROWTH_LEVELS")]
    [InlineData("cooldown", "PET_BREED_COOLDOWN")]
    [InlineData("potential", "PET_POTENTIAL")]
    [InlineData("stat_tier", "PET_STAT_TIERS")]
    [InlineData("stat_range", "PET_STAT_TIERS")]
    [InlineData("skill_distribution", "PET_SKILL_TIERS")]
    [InlineData("skill_duplicate", "PET_SKILL_TIERS")]
    [InlineData("scaling", "PET_STAT_SCALING")]
    public async Task RuntimeInvariantsRejectInvalidDraftBeforeSaving(string change, string code)
    {
        var master = _master.DeepClone();
        var rules = master["rules"]!; var stat = master["species"]![0]!["stats"]!["POWER"]!; var skills = master["species"]![0]!["skills"]!;
        switch (change)
        {
            case "tier_cap": rules["wildMaxTier"] = 10; break;
            case "slots": rules["skillSlotLevels"] = new JsonArray(1, 50, 20); break;
            case "cooldown": rules["breedCooldownHours"] = 1.5; break;
            case "potential": rules["potentialCountWeights"] = new JsonArray(0, 0, 0); break;
            case "stat_tier": stat["tiers"]![1]!["tier"] = 0; break;
            case "stat_range": stat["tiers"]![1]!["min"] = 1; break;
            case "skill_distribution": skills[0]!["tier"] = 1; break;
            case "skill_duplicate": skills[1]!["id"] = skills[0]!["id"]!.DeepClone(); break;
            case "scaling": stat["inheritanceMin"] = .9; break;
        }
        var original = await _service.ReadAsync(PetPath, CancellationToken.None);
        var exception = await Assert.ThrowsAsync<MasterDataValidationException>(() => _service.SaveAsync(new(PetPath, Raw(master), original.Revision), CancellationToken.None));
        Assert.Contains(exception.Report.Issues, issue => issue.Code == code);
        Assert.Equal(original.Raw, (await _service.ReadAsync(PetPath, CancellationToken.None)).Raw);
    }

    [Fact]
    public async Task MasterReferencesEnforcePetEggMaterialOrbAndNpcBindings()
    {
        Assert.True((await _validation.ValidateAsync(PetPath, Raw(_master), CancellationToken.None)).IsValid);
        var wrongEgg = _master.DeepClone(); wrongEgg["species"]![0]!["eggItemId"] = "item:10a00001";
        Assert.Contains((await _validation.ValidateAsync(PetPath, Raw(wrongEgg), CancellationToken.None)).Issues,
            issue => issue.Code == "PET_ITEM_BINDING" && issue.Path.EndsWith("eggItemId", StringComparison.Ordinal));
        var missing = _master.DeepClone(); missing["rules"]!["reviveOrbItemId"] = "item:40a00099";
        Assert.Contains((await _validation.ValidateAsync(PetPath, Raw(missing), CancellationToken.None)).Issues, issue => issue.Code == "REFERENCE_NOT_FOUND");
        var facility = _master.DeepClone(); facility["rules"]!["facilityId"] = "unrelated_npc";
        Assert.Contains((await _validation.ValidateAsync(PetPath, Raw(facility), CancellationToken.None)).Issues, issue => issue.Code == "PET_FACILITY_BINDING");
    }

    [Theory]
    [InlineData("10.features.item/40.orb/v1.40a00020.revive.yml", "PET_REVIVE", "ENHANCE", "PET_ITEM_BINDING")]
    [InlineData("40.features.mob/npc/v1.pet_center.yml", "PET_CENTER", "SHOP", "PET_FACILITY_BINDING")]
    [InlineData("40.features.mob/npc/v1.pet_center.yml", "rightClick:", "unusedClick:", "PET_FACILITY_BINDING")]
    [InlineData("10.features.item/80.pet/v1.80a00001.pet.yml", "maxStack: 1", "maxStack: 64", "PET_ITEM_BINDING")]
    public async Task ReferencedFileEditsCannotSilentlyBreakPetBindings(string path, string before, string after, string code)
    {
        var original = await _service.ReadAsync(path, CancellationToken.None);
        var exception = await Assert.ThrowsAsync<MasterDataValidationException>(() => _service.SaveAsync(new(path,
            original.Raw.Replace(before, after, StringComparison.Ordinal), original.Revision), CancellationToken.None));
        Assert.Contains(exception.Report.Issues, issue => issue.Code == code);
        Assert.Equal(original.Raw, (await _service.ReadAsync(path, CancellationToken.None)).Raw);
    }

    [Fact]
    public async Task UnknownPetFieldsRemainVisibleAndAreRejectedWithoutDiscardingTheDraft()
    {
        var original = await _service.ReadAsync(PetPath, CancellationToken.None);
        var unknown = original.Content!.DeepClone(); unknown["AlienField"] = new JsonObject { ["custom"] = 42 };
        var rendered = _codec.Render(new(PetPath, unknown, original.Raw));
        Assert.Equal("42", _codec.Parse(PetPath, rendered.Raw).Content!["AlienField"]!["custom"]!.ToString());
        var report = await _validation.ValidateAsync(PetPath, rendered.Raw, CancellationToken.None);
        Assert.Contains(report.Issues, issue => issue.Code == "JSON_SCHEMA");
        Assert.Equal(original.Raw, (await _service.ReadAsync(PetPath, CancellationToken.None)).Raw);
    }

    private static JsonObject CreateMaster()
    {
        JsonArray Materials() => [new JsonObject { ["itemId"] = "item:10a00001", ["quantity"] = 256 }];
        var species = new JsonArray();
        var ids = new[] { "wolf", "cat", "chicken" }; var entities = new[] { "WOLF", "CAT", "CHICKEN" };
        for (var index = 0; index < 3; index++)
        {
            var stats = new JsonObject();
            foreach (var stat in new[] { "VITALITY", "POWER", "DEFENSE", "EVASION", "SUPPORT" })
                stats[stat] = new JsonObject
                {
                    ["tiers"] = new JsonArray(Enumerable.Range(0, 11).Select(tier => (JsonNode)new JsonObject
                    { ["tier"] = tier, ["min"] = tier * 3 + 1, ["max"] = tier * 3 + 3, ["weight"] = tier <= 4 ? 100 : 0 }).ToArray()),
                    ["valueWeightPower"] = 2, ["growthPerLevel"] = .2, ["inheritanceMin"] = .1, ["inheritanceMax"] = .5, ["scale"] = 40,
                };
            species.Add(new JsonObject
            {
                ["id"] = ids[index], ["entityType"] = entities[index], ["displayName"] = "ペット",
                ["eggItemId"] = "item:81a0000" + (index + 1), ["petItemId"] = "item:80a0000" + (index + 1),
                ["names"] = new JsonArray(Enumerable.Range(0, 50).Select(i => (JsonNode)JsonValue.Create("名前" + i)!).ToArray()),
                ["sizeMin"] = .8, ["sizeMax"] = 1.2, ["dropWeight"] = 1, ["hatchMaterials"] = Materials(), ["stats"] = stats,
                ["skills"] = new JsonArray(Enumerable.Range(0, 8).Select(skill => (JsonNode)new JsonObject
                { ["id"] = ids[index] + "_skill_" + skill, ["name"] = "スキル", ["tier"] = skill / 2,
                    ["trigger"] = "PERIODIC", ["effect"] = "HEAL_HP", ["weight"] = 1, ["cooldownSeconds"] = 20,
                    ["value"] = .05, ["chance"] = 1, ["durationSeconds"] = 0, ["hitCount"] = 1, ["attackCount"] = 1 }).ToArray()),
            });
        }
        return new JsonObject
        {
            ["schemaVersion"] = 1, ["id"] = "pets", ["rules"] = new JsonObject
            { ["maxLevel"] = 100, ["wildMaxTier"] = 4, ["breedMaxTier"] = 10, ["skillSlotLevels"] = new JsonArray(1, 20, 50),
                ["breedMinLevel"] = 20, ["breedCooldownHours"] = 24, ["potentialGrowthMultiplier"] = 2,
                ["potentialCountWeights"] = new JsonArray(20, 75, 5), ["reviveOrbItemId"] = "item:40a00020", ["facilityId"] = "pet_center",
                ["breedMaterials"] = Materials(), ["reviveMaterials"] = Materials() },
            ["experience"] = new JsonObject { ["activityRate"] = 1 }, ["species"] = species,
        };
    }
    private void WriteItem(string path, string id, string category, int maxStack, string suffix = "")
        => Write(path, $"schemaVersion: 1\nid: {id}\ncategory: {category}\nname: 定義\nmaxStack: {maxStack}\nunTradeable: false\n" + suffix);
    private string Raw(JsonNode content) => _codec.Render(new(PetPath, content, null)).Raw;
    private void Write(string path, string raw)
    { var full = _paths.Resolve(path); Directory.CreateDirectory(Path.GetDirectoryName(full)!); File.WriteAllText(full, raw); }
    public void Dispose() { if (Directory.Exists(_root)) Directory.Delete(_root, recursive: true); }
}
