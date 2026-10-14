using System.Text.Json.Nodes;
using SkillTreeEditor.Server.Models;
using SkillTreeEditor.Server.Services;

namespace SkillTreeEditor.Server.Tests;

public sealed class MasterDataTests : IDisposable
{
    private readonly string _root = Path.Combine(Path.GetTempPath(), "master-data-editor-tests-" + Guid.NewGuid().ToString("N"));
    private readonly MasterDataService _service;
    private readonly MasterDataValidation _validation;
    private readonly MasterDataCatalog _catalog;
    private readonly MasterDataPaths _paths;
    private readonly MasterDataCodec _codec = new();

    public MasterDataTests()
    {
        Directory.CreateDirectory(Path.Combine(_root, "40_filebase"));
        Directory.CreateDirectory(Path.Combine(_root, "10_plugin"));
        Directory.CreateDirectory(Path.Combine(_root, "60_tool"));
        var workspace = new WorkspacePaths(_root);
        _paths = new(workspace);
        var backups = new BackupService(workspace);
        var skillTreeValidation = new ValidationService(workspace, new SchemaCatalog(workspace), new PluginConfigService(workspace, backups), new MasterTagCatalog(workspace));
        _catalog = new(_paths);
        _validation = new(_paths, _codec, _catalog, skillTreeValidation);
        _service = new(_paths, workspace, _codec, _validation, new WorkspaceMutationGate(workspace), backups);
    }

    [Fact]
    public async Task WorldRefRejectsMissingTargetWithoutTreatingDescriptiveTextAsReference()
    {
        Write("config.yml", "referenceResolver:\n  - prefix: 'world:'\n    database: world\n    aliases: [wd]\n");
        Write("60.features.world/v1.existing.yml", "id: existing\n");
        var missing = await _validation.ValidateAsync("test/dungeon.yml",
            "id: dungeon\nentry:\n  worldRef: wd:missing\n", CancellationToken.None);
        Assert.Contains(missing.Issues, issue => issue.Code == "REFERENCE_NOT_FOUND" && issue.Path == "/entry/worldRef");
        var existing = await _validation.ValidateAsync("test/dungeon.yml",
            "id: dungeon\nentry:\n  worldRef: world:existing\ndescription: world:missing\n", CancellationToken.None);
        Assert.True(existing.IsValid);
    }

    [Fact]
    public async Task UnrelatedStructureIdentityDoesNotBlockWorldDeletion()
    {
        const string world = "60.features.world/v1.main.yml";
        Write(world, "id: main\n");
        Write("35.features.skilltree/structures/main.json", "{\"structureId\":\"main\"}");
        Assert.Empty(await _service.ReferencesAsync(world, CancellationToken.None));
        var document = await _service.ReadAsync(world, CancellationToken.None);
        await _service.DeleteAsync(world, document.Revision, CancellationToken.None);
        Assert.False(File.Exists(_paths.Resolve(world)));
    }

    [Fact]
    public async Task BareWorldReferencesOnlyIncludeTypedValuesAndReferenceMaps()
    {
        const string world = "60.features.world/v1.main.yml";
        Write(world, "id: main\n");
        Write("test/consumer.yml", "structureId: main\nworldId:\n  ref: main\n  metadata:\n    sourceId: main\nworldIds: [main]\n");
        var references = await _service.ReferencesAsync(world, CancellationToken.None);
        Assert.Equal(new[] { "/worldId/ref", "/worldIds/0" }, references.Select(reference => reference.Pointer));
        var document = await _service.ReadAsync(world, CancellationToken.None);
        await Assert.ThrowsAsync<InvalidOperationException>(() => _service.DeleteAsync(world, document.Revision, CancellationToken.None));
    }

    [Fact]
    public async Task RawSavePreservesCommentsUnknownFieldsAndCreatesBackupOnlyOnChange()
    {
        const string path = "30.features.skill/custom/v1.alpha.yml";
        const string original = "# design:\r\n#   motif: custom\r\nid: alpha # identity\r\nparams:\r\n  CustomRPCName: 17\r\n";
        Write(path, original);
        var document = await _service.ReadAsync(path, CancellationToken.None);
        var updated = original.Replace("17", "18", StringComparison.Ordinal);
        var saved = await _service.SaveAsync(new(path, updated, document.Revision), CancellationToken.None);
        await _service.SaveAsync(new(path, updated, saved.Revision), CancellationToken.None);

        Assert.Equal(updated, saved.Raw);
        var backup = Assert.Single(Directory.GetFiles(Path.Combine(_root, "60_tool", "skilltree-editor", ".backups", "master-data")));
        Assert.Equal(original, await File.ReadAllTextAsync(backup));
        Assert.NotEqual(document.Revision, saved.Revision);
    }

    [Fact]
    public async Task SaveRoundTripPreservesEquipmentRandomUnknownKeyAndComments()
    {
        const string path = "test/random-equipment.yml";
        const string original = "# item comment\nequipment:\n  stats:\n    - status: FIRE_DAMAGE_INCREASE\n      value: 5 # base\n      customNote: keep\n";
        Write(path, original);
        var document = await _service.ReadAsync(path, CancellationToken.None);
        var content = _codec.Parse(path, document.Raw).Content!;
        content["equipment"]!["stats"]![0]!["random"] = JsonNode.Parse("""{"offsetMin":-2,"offsetMax":3,"step":0.5,"zeroChance":0.2}""");
        var rendered = _codec.Render(new(path, content, document.Raw));

        var saved = await _service.SaveAsync(new(path, rendered.Raw, document.Revision), CancellationToken.None);
        var reloaded = await _service.ReadAsync(path, CancellationToken.None);

        Assert.Equal(saved.Raw, reloaded.Raw);
        Assert.True(JsonNode.DeepEquals(content, reloaded.Content));
        Assert.Equal("keep", reloaded.Content!["equipment"]!["stats"]![0]!["customNote"]!.GetValue<string>());
        Assert.Contains("# item comment", reloaded.Raw);
        Assert.Contains("# base", reloaded.Raw);
    }

    [Fact]
    public void CodecDoesNotSilentlyTurnDecimalUnderflowIntoZero()
    {
        var content = _codec.Parse("test.yml", "value: 1e-100\nzero: 0e-100\n").Content!;

        Assert.Equal("1e-100", content["value"]!.ToString());
        Assert.Equal(0m, content["zero"]!.GetValue<decimal>());
    }

    [Theory]
    [InlineData("65.features.dungeon/v1.ruins.yml", "id: ruins\ndisplayName: 遺跡\nname: internal\ntitle: caption\n", "遺跡")]
    [InlineData("60.features.world/v1.central.yml", "id: central\ndisplayName: 星影の街\n", "星影の街")]
    [InlineData("05.features.mail/v1.welcome.yml", "id: welcome\ntitle: 初ログインの贈り物\n", "初ログインの贈り物")]
    [InlineData("09.features.guide/v1.guide.yml", "id: guide\ntitle: 初めてのダンジョン\n", "初めてのダンジョン")]
    [InlineData("40.features.mob/v1.npc.yml", "id: npc\nname: 錬金術師\ntitle: 植物調合\n", "錬金術師")]
    [InlineData("test/v1.blank.yml", "id: blank\ndisplayName: ' '\nname: 名称\n", "名称")]
    [InlineData("test/v1.invalid.yml", "id: invalid\ndisplayName: {value: x}\nname: ''\ntitle: 題名\n", "題名")]
    public async Task ListAndCandidatesResolveJapaneseDisplayNamesWithoutChangingIdentity(string path, string raw, string name)
    {
        Write(path, raw);
        var summary = Assert.Single(await _service.ListAsync(null, name, CancellationToken.None));
        var candidate = Assert.Single(await _service.CandidatesAsync(CancellationToken.None));
        Assert.Equal(name, summary.Name);
        Assert.Equal(name, candidate.Label);
        Assert.Equal(summary.Id, candidate.Value);
        Assert.Equal(path, candidate.Path);
        Assert.Equal("/id", candidate.Pointer);
        Assert.Equal(raw, (await _service.ReadAsync(path, CancellationToken.None)).Raw);
    }

    [Fact]
    public async Task ReferencesUseTheJapaneseNameOfTheReferringDocument()
    {
        const string world = "60.features.world/v1.greenfields.yml";
        Write(world, "id: greenfields\ndisplayName: ミズガルズの緑野\n");
        Write("65.features.dungeon/v1.ruins.yml", "id: ruins\ndisplayName: アルダ始まりの遺跡\nentry:\n  worldRef: world:greenfields\n");
        var reference = Assert.Single(await _service.ReferencesAsync(world, CancellationToken.None));
        Assert.Equal("アルダ始まりの遺跡", reference.Label);
        Assert.Equal("world:greenfields", reference.Value);
        Assert.Equal("/entry/worldRef", reference.Pointer);
    }

    [Fact]
    public async Task StaleRevisionCannotOverwriteOrDeleteExternalEdit()
    {
        const string path = "test/v1.data.yml";
        Write(path, "id: sample\nname: old\n");
        var original = await _service.ReadAsync(path, CancellationToken.None);
        Write(path, "id: sample\nname: external\n");

        await Assert.ThrowsAsync<InvalidOperationException>(() => _service.SaveAsync(new(path, "id: sample\nname: replacement\n", original.Revision), CancellationToken.None));
        await Assert.ThrowsAsync<InvalidOperationException>(() => _service.DeleteAsync(path, original.Revision, CancellationToken.None));
        Assert.Contains("external", await File.ReadAllTextAsync(_paths.Resolve(path)));
    }

    [Fact]
    public async Task ConcurrentSavesWithSameRevisionHaveOneWinner()
    {
        const string path = "test/v1.data.yml";
        Write(path, "id: sample\nname: initial\n");
        var original = await _service.ReadAsync(path, CancellationToken.None);
        var results = await Task.WhenAll(Enumerable.Range(0, 8).Select(async index =>
        {
            try { await _service.SaveAsync(new(path, $"id: sample\nname: value{index}\n", original.Revision), CancellationToken.None); return true; }
            catch (InvalidOperationException) { return false; }
        }));
        Assert.Single(results, value => value);
    }

    [Theory]
    [InlineData("../external.yml")]
    [InlineData("test/../../external.yml")]
    [InlineData("C:/external.yml")]
    [InlineData("test\\external.yml")]
    [InlineData("test/data.yml:stream")]
    [InlineData("test/data.yml.")]
    public void RejectsUnsafePaths(string path) => Assert.ThrowsAny<ArgumentException>(() => _paths.Resolve(path));

    [Fact]
    public void ParserRejectsDuplicateKeysAndPreservesStringTypes()
    {
        Assert.NotEmpty(_codec.Parse("test.yml", "name: first\nname: second\n").Issues);
        Assert.NotEmpty(_codec.Parse("test.json", "{\"name\":1,\"name\":2}").Issues);
        var content = _codec.Parse("test.yml", "plain: true\nquoted: \"true\"\nnumber: 12\ntext: '12'\n").Content!;
        Assert.True(content["plain"]!.GetValue<bool>());
        Assert.Equal("true", content["quoted"]!.GetValue<string>());
        Assert.Equal(12, content["number"]!.GetValue<long>());
        Assert.Equal("12", content["text"]!.GetValue<string>());
    }

    [Fact]
    public void ScalarFormEditsPreserveInlineCommentsOrderAndCrlf()
    {
        const string raw = "# design:\r\n#   motif: test\r\nid: alpha # identity\r\nparams:\r\n  RPCName: 2 # custom\r\n  untouched: \"raw#value\"\r\n";
        var content = _codec.Parse("sample.yml", raw).Content!;
        content["params"]!["RPCName"] = 3;
        var rendered = _codec.Render(new("sample.yml", content, raw));
        Assert.True(rendered.CommentsPreserved);
        Assert.Empty(rendered.Warnings);
        Assert.Equal(raw.Replace("RPCName: 2", "RPCName: 3", StringComparison.Ordinal), rendered.Raw);
    }

    [Fact]
    public void StructuralFormEditsKeepCommentsAndReportRelocation()
    {
        const string raw = "# design comment\nparams:\n  value: 2 # inline comment\n";
        var content = _codec.Parse("sample.yml", raw).Content!;
        content["params"]!["added"] = "new";
        var rendered = _codec.Render(new("sample.yml", content, raw));
        Assert.False(rendered.CommentsPreserved);
        Assert.NotEmpty(rendered.Warnings);
        Assert.Contains("design comment", rendered.Raw);
        Assert.Contains("inline comment", rendered.Raw);
        Assert.True(JsonNode.DeepEquals(content, _codec.Parse("sample.yml", rendered.Raw).Content));
    }

    [Fact]
    public async Task CatalogCombinesItemCommonAndSubtypeAndAdaptsHistoricalMapPaths()
    {
        Write("10.features.item/docs.item.YAMLスキーマ定義.md", "# ITEM\n| キー | 型 | 必須 | 説明 |\n|:--|:--|:--|:--|\n| `name` | String | ○ | 表示名 |\n");
        Write("10.features.item/20.equipment/docs.equipment.YAMLスキーマ定義.md", "# EQUIPMENT\n| キー | 型 | 必須 | 説明 |\n|:--|:--|:--|:--|\n| `equipment[].slot` | String | ○ | 装備スロット |\n| `equipment[].stats[]` | List | × | 補正 |\n| `equipment[].stats[].status` | String | ○ | status ID |\n");
        var category = await _catalog.GetAsync("10.features.item/20.equipment", CancellationToken.None);
        Assert.Equal(2, category.Documents.Count);
        Assert.Contains(category.Fields, field => field.Path == "/name" && field.Required);
        Assert.Contains(category.Fields, field => field.Path == "/equipment/slot");
        Assert.Contains(category.Fields, field => field.Path == "/equipment/stats" && field.Type == "List");
        Assert.Contains(category.Fields, field => field.Path == "/equipment/stats/*/status" && field.Reference == "status");
    }

    [Fact]
    public async Task OrbGoldAndIconGlintFieldsUseDocumentedBooleanTypesAndEffectRestriction()
    {
        Write("10.features.item/docs.item.YAMLスキーマ定義.md", "# ITEM\n| キー | 型 | 必須 | デフォルト | 説明 |\n|:--|:--|:--|:--|:--|\n| `iconGlint` | Boolean | × | false | アイコンだけを光らせる |\n");
        Write("10.features.item/40.orb/docs.orb.YAMLスキーマ定義.md", "# ORB\n## `orb.effect`\n| キー | 型 | 必須 | デフォルト | 説明 |\n|:--|:--|:--|:--|:--|\n| `chargeSaleValue` | Boolean | × | false | 売却額分のGoldを消費 |\n| `rankBasis` | String | × | TARGET | CURRENT / TARGET |\n");
        const string path = "10.features.item/40.orb/v1.40a00001.sample.yml";
        var category = await _catalog.GetAsync("10.features.item/40.orb", CancellationToken.None);
        Assert.Contains(category.Fields, field => field.Path == "/iconGlint" && field.Type == "Boolean"
            && field.Label == "アイコンのエンチャントエフェクト" && field.Default == "false");
        Assert.Contains(category.Fields, field => field.Path == "/orb/effect/chargeSaleValue" && field.Type == "Boolean"
            && field.Label == "売却額分のGoldを消費" && field.Default == "false");
        Assert.Contains(category.Fields, field => field.Path == "/orb/effect/rankBasis" && field.Type == "String"
            && field.Label == "状態変化のランク判定基準" && field.Default == "TARGET"
            && field.Enum is { Count: 2 } && field.Enum[0]?.ToString() == "CURRENT" && field.Enum[1]?.ToString() == "TARGET");

        const string raw = "id: 40a00001\ncategory: orb\niconGlint: true\nsaleValue: 100\norb:\n  effect:\n    type: ENHANCE\n    chargeSaleValue: true\n";
        Write(path, raw);
        Assert.True((await _validation.ValidateAsync(path, raw, CancellationToken.None)).IsValid);
        var edited = raw.Replace("iconGlint: true", "iconGlint: false", StringComparison.Ordinal);
        var original = await _service.ReadAsync(path, CancellationToken.None);
        var saved = await _service.SaveAsync(new(path, edited, original.Revision), CancellationToken.None);
        Assert.False(saved.Content!["iconGlint"]!.GetValue<bool>());
        Assert.True(saved.Content["orb"]!["effect"]!["chargeSaleValue"]!.GetValue<bool>());
        Assert.Contains("chargeSaleValue: true", saved.Raw);

        var wrongType = await _validation.ValidateAsync(path, raw.Replace("chargeSaleValue: true", "chargeSaleValue: yes", StringComparison.Ordinal), CancellationToken.None);
        Assert.Contains(wrongType.Issues, issue => issue.Code == "FIELD_TYPE" && issue.Path == "/orb/effect/chargeSaleValue");
        var unrelated = await _validation.ValidateAsync(path, raw.Replace("type: ENHANCE", "type: ENCHANT", StringComparison.Ordinal), CancellationToken.None);
        Assert.Contains(unrelated.Issues, issue => issue.Code == "ORB_GOLD_EFFECT_TYPE" && issue.Path == "/orb/effect/chargeSaleValue");
        var invalidRankBasis = await _validation.ValidateAsync(path, raw.Replace("chargeSaleValue: true", "rankBasis: BEFORE", StringComparison.Ordinal), CancellationToken.None);
        Assert.Contains(invalidRankBasis.Issues, issue => issue.Code == "ORB_RANK_BASIS" && issue.Path == "/orb/effect/rankBasis");
    }

    [Fact]
    public async Task EnchantOperationCatalogOffersCraftingOperationsAndMasterReference()
    {
        Write("10.features.item/40.orb/docs.orb.YAMLスキーマ定義.md", "# ORB\n## `orb.effect`\n| キー | 型 | 必須 | デフォルト | 説明 |\n|:--|:--|:--|:--|:--|\n| `enchantMasterId` | String | 条件 | Null | 候補マスタ参照 |\n| `enchantOperation` | String | 条件 | Null | エンチャント操作 |\n");
        var category = await _catalog.GetAsync("10.features.item/40.orb", CancellationToken.None);
        var operation = Assert.Single(category.Fields, field => field.Path == "/orb/effect/enchantOperation");
        Assert.Equal("エンチャント操作", operation.Label);
        Assert.Equal(["OVERWRITE_RANDOM", "FILL_ONE_EMPTY", "FILL_ALL_EMPTY", "REROLL_VALUE_RANDOM", "REMOVE_RANDOM", "LOCK_FIRST"],
            operation.Enum!.Select(value => value!.ToString()).ToArray());
        var master = Assert.Single(category.Fields, field => field.Path == "/orb/effect/enchantMasterId");
        Assert.Equal("enchant", master.Reference);
        Assert.False(master.Required);
    }

    [Theory]
    [InlineData("FILL_ONE_EMPTY", true)]
    [InlineData("FILL_ALL_EMPTY", true)]
    [InlineData("OVERWRITE_RANDOM", true)]
    [InlineData("REROLL_VALUE_RANDOM", true)]
    [InlineData("REMOVE_RANDOM", false)]
    [InlineData("LOCK_FIRST", false)]
    public async Task EnchantOperationsRequireMasterOnlyForValueGeneration(string operation, bool masterRequired)
    {
        const string path = "10.features.item/40.orb/v1.40a00032.stein_orb.yml";
        var raw = "schemaVersion: 1\nid: 40a00032\ncategory: orb\norb:\n  effect:\n    type: ENCHANT\n    enchantOperation: " + operation + "\n";
        var report = await _validation.ValidateAsync(path, raw, CancellationToken.None);
        Assert.Equal(!masterRequired, report.IsValid);
        Assert.Equal(masterRequired, report.Issues.Any(issue => issue.Code == "ORB_ENCHANT_MASTER"));

        var withMaster = raw + "    enchantMasterId: enchant:enchant002\n";
        Write("12.features.enchant/v1.enchant002.yml", "schemaVersion: 1\nid: enchant002\n");
        Write("config.yml", "referenceResolver:\n  - prefix: 'enchant:'\n    database: enchant\n");
        Assert.True((await _validation.ValidateAsync(path, withMaster, CancellationToken.None)).IsValid);
        var document = await _service.CreateAsync(new(path, withMaster), CancellationToken.None);
        var saved = await _service.SaveAsync(new(document.Path, document.Raw + "# preserved\n", document.Revision), CancellationToken.None);
        Assert.Equal(operation, saved.Content!["orb"]!["effect"]!["enchantOperation"]!.ToString());
        Assert.Equal("enchant:enchant002", saved.Content["orb"]!["effect"]!["enchantMasterId"]!.ToString());
    }

    [Fact]
    public async Task EnchantValidationRejectsUnknownOperationAndBrokenMasterReference()
    {
        const string path = "10.features.item/40.orb/v1.40a00032.stein_orb.yml";
        const string raw = "schemaVersion: 1\nid: 40a00032\ncategory: orb\norb:\n  effect:\n    type: ENCHANT\n    enchantOperation: REROLL_VALUE_RANDOM\n    enchantMasterId: enchant:missing\n";
        Write("config.yml", "referenceResolver:\n  - prefix: 'enchant:'\n    database: enchant\n");
        var missing = await _validation.ValidateAsync(path, raw, CancellationToken.None);
        Assert.Contains(missing.Issues, issue => issue.Code == "REFERENCE_NOT_FOUND");
        var unknown = await _validation.ValidateAsync(path, raw.Replace("REROLL_VALUE_RANDOM", "REROLL_UNKNOWN", StringComparison.Ordinal), CancellationToken.None);
        Assert.Contains(unknown.Issues, issue => issue.Code == "ORB_ENCHANT_OPERATION");
        var malformed = await _validation.ValidateAsync(path, raw.Replace("enchant:missing", "item:40a00001", StringComparison.Ordinal), CancellationToken.None);
        Assert.Contains(malformed.Issues, issue => issue.Code == "ORB_ENCHANT_MASTER");
    }

    [Fact]
    public async Task ConcurrentItemCreatesAllocateUniqueIdsAndCanonicalNames()
    {
        const string directory = "10.features.item/20.equipment";
        Write(directory + "/v1.20a99999.old.yml", "schemaVersion: 1\nid: 20a99999\ncategory: equipment\nname: last\n");
        var created = await Task.WhenAll(Enumerable.Range(0, 6).Select(index => _service.CreateAsync(new(directory,
            "# design:\n#   motif: test\nschemaVersion: 1\nid: 20a00001\ncategory: equipment\nname: test\n",
            AutoItemId: true, Slug: "new" + index), CancellationToken.None)));
        Assert.Equal(6, created.Select(document => document.Content!["id"]!.ToString()).Distinct().Count());
        Assert.Contains(created, document => document.Path.EndsWith("v1.20b00001.new0.yml", StringComparison.Ordinal)
            || document.Content!["id"]!.ToString() == "20b00001");
        Assert.All(created, document => Assert.Contains("# design:", document.Raw));
    }

    [Fact]
    public async Task ReferencesBlockDeleteAndDuplicateIdCreation()
    {
        const string item = "10.features.item/10.material/v1.10a00001.test.yml";
        Write(item, "schemaVersion: 1\nid: 10a00001\ncategory: material\nname: material\n");
        Write("85.shared.recipe/v1.recipe.yml", "id: recipe\ningredients:\n  - ref: item:10a00001\n");
        var document = await _service.ReadAsync(item, CancellationToken.None);
        Assert.Single(await _service.ReferencesAsync(item, CancellationToken.None));
        await Assert.ThrowsAsync<InvalidOperationException>(() => _service.DeleteAsync(item, document.Revision, CancellationToken.None));
        var duplicate = await _validation.ValidateAsync("10.features.item/10.material/v1.10a00001.copy.yml", document.Raw, CancellationToken.None);
        Assert.Contains(duplicate.Issues, issue => issue.Code == "DUPLICATE_ID");
    }

    [Fact]
    public async Task ClassAutoNameUsesVersionOrderAndId()
    {
        var created = await _service.CreateAsync(new("20.features.class", "schemaVersion: 1\nid: custom\nname: Custom\norder: 1.5\n", AutoName: true), CancellationToken.None);
        Assert.Equal("20.features.class/v1.15.custom.yml", created.Path);
    }

    [Fact]
    public async Task JSONSchemaValidationAndAdoptionRejectInvalidValues()
    {
        Write("test/schemas/sample.schema.json", "{\"type\":\"object\",\"properties\":{\"count\":{\"type\":\"integer\",\"minimum\":0}},\"required\":[\"count\"]}");
        var report = await _validation.ValidateAsync("test/data.yml", "count: -1\n", CancellationToken.None);
        Assert.False(report.IsValid);
        Assert.Contains(report.Issues, issue => issue.Code == "JSON_SCHEMA");
    }

    [Fact]
    public async Task SequenceWritesAndManualNodeCreatesAreProtected()
    {
        Write("35.features.skilltree/node-id-sequence.json", "{\"lastIssuedNodeId\":\"999\"}");
        var document = await _service.ReadAsync("35.features.skilltree/node-id-sequence.json", CancellationToken.None);
        await Assert.ThrowsAsync<UnauthorizedAccessException>(() => _service.SaveAsync(new(document.Path, "{}", document.Revision), CancellationToken.None));
        await Assert.ThrowsAsync<UnauthorizedAccessException>(() => _service.CreateAsync(new("35.features.skilltree/nodes/1000.json", "{}"), CancellationToken.None));
    }

    [Fact]
    public async Task NonItemCopyCanChangeIdentityAndRetainsComments()
    {
        Write("30.features.skill/v1.original.yml", "# custom commentary\nid: original\nparams:\n  RPCName: 7\n");
        var source = await _service.ReadAsync("30.features.skill/v1.original.yml", CancellationToken.None);
        var copy = await _service.CopyAsync(new(source.Path, source.Revision, "30.features.skill/v1.copy.yml", NewId: "copy"), CancellationToken.None);
        Assert.Equal("copy", copy.Content!["id"]!.ToString());
        Assert.Contains("# custom commentary", copy.Raw);
        Assert.Equal(7, copy.Content["params"]!["RPCName"]!.GetValue<long>());
    }

    [Fact]
    public async Task ScopedDocumentTablesDoNotIntroduceUnrelatedRootRequirements()
    {
        Write("75.shared.status/docs.status_types.YAMLスキーマ定義.md", "# Status\n## ルート\n| キー | 型 | 必須 | 説明 |\n|---|---|---|---|\n| `statuses` | List<Status> | 必須 | 定義 |\n## Status\n| キー | 型 | 必須 | 説明 |\n|---|---|---|---|\n| `id` | String | 必須 | ID |\n");
        var category = await _catalog.GetAsync("75.shared.status", CancellationToken.None);
        Assert.Contains(category.Fields, field => field.Path == "/statuses/*/id");
        Assert.DoesNotContain(category.Fields, field => field.Path == "/id");
        Assert.True((await _validation.ValidateAsync("75.shared.status/v1.status_types.yml", "statuses:\n  - id: CUSTOM\n", CancellationToken.None)).IsValid);
    }

    [Fact]
    public async Task ExplicitConfiguredReferencesRejectMissingIdsAndDeletionDetectsAliasesArraysAndSetEffects()
    {
        Write("config.yml", "referenceResolver:\n  - prefix: 'item:'\n    database: item\n    aliases: [im, custom_item]\n");
        Write("10.features.item/70.sigil/v1.70a00001.sample.yml", "schemaVersion: 1\nid: 70a00001\ncategory: sigil\n");
        Write("30.features.skill/v1.skill.yml", "id: skill\nallowedSigilIds: [70a00001]\nother:\n  ref: custom_item:70a00001\n");
        var report = await _validation.ValidateAsync("30.features.skill/v1.missing.yml", "id: missing\nitemId:\n  ref: custom_item:70a00002\n", CancellationToken.None);
        Assert.Contains(report.Issues, issue => issue.Code == "REFERENCE_NOT_FOUND");
        var references = await _service.ReferencesAsync("10.features.item/70.sigil/v1.70a00001.sample.yml", CancellationToken.None);
        Assert.Equal(2, references.Count);
        Assert.Equal("set_effect", MasterDataService.Kind("10.features.item/20.equipment/set_effect/v1.set.yml"));
        Assert.Equal("loot_table", MasterDataService.Kind("80.shared.loot/table/v1.table.yml"));
        Assert.Equal("mob_spawner", MasterDataService.Kind("41.features.mob.spawner/v1.spawner.yml"));
    }

    [Fact]
    public async Task AdministratorClassNameUsesReservedOrderAndFractionalOrderIsRejected()
    {
        var administrator = await _service.CreateAsync(new("20.features.class", "schemaVersion: 1\nid: administrator\norder: 1\n", AutoName: true), CancellationToken.None);
        Assert.Equal("20.features.class/v1.9999.administrator.yml", administrator.Path);
        await Assert.ThrowsAsync<ArgumentException>(() => _service.CreateAsync(new("20.features.class", "schemaVersion: 1\nid: invalid\norder: 1.25\n", AutoName: true), CancellationToken.None));
    }

    [Fact]
    public async Task StructureDeletionDetectsPluginConfigurationReference()
    {
        Write("35.features.skilltree/structures/main.json", "{\"structureId\":\"main\"}");
        var pluginConfig = Path.Combine(_root, "10_plugin", "AstralRecord", "src", "main", "resources", "config.yml");
        Directory.CreateDirectory(Path.GetDirectoryName(pluginConfig)!);
        File.WriteAllText(pluginConfig, "skilltree:\n  structureId: main\n");
        var references = await _service.ReferencesAsync("35.features.skilltree/structures/main.json", CancellationToken.None);
        Assert.Contains(references, reference => reference.Pointer == "/skilltree/structureId");
    }

    [Fact]
    public void JapaneseScalarRenderingRemainsReadable()
    {
        const string original = "name: 日本語 # 説明\n";
        var rendered = _codec.Render(new("test.yml", new JsonObject { ["name"] = "新しい名前" }, original));
        Assert.Contains("新しい名前", rendered.Raw);
        Assert.DoesNotContain("\\u", rendered.Raw);
    }

    [Theory]
    [InlineData("[]")]
    [InlineData("null")]
    [InlineData("invalid_scalar")]
    public async Task RootReplacementCannotBypassIdentityAndMasterValidation(string raw)
    {
        Write("20.features.class/v1.10.sample.yml", "id: sample\nname: Sample\n");
        var source = await _service.ReadAsync("20.features.class/v1.10.sample.yml", CancellationToken.None);
        var exception = await Assert.ThrowsAsync<MasterDataValidationException>(() => _service.SaveAsync(new(source.Path, raw, source.Revision), CancellationToken.None));
        Assert.Contains(exception.Report.Issues, issue => issue.Code == "ROOT_SHAPE");
        Assert.Equal(source.Raw, (await _service.ReadAsync(source.Path, CancellationToken.None)).Raw);
    }

    [Fact]
    public async Task NullRequiredFieldIsRejected()
    {
        Write("test/docs.test.YAMLスキーマ定義.md", "# Test\n| キー | 型 | 必須 | 説明 |\n|---|---|---|---|\n| `name` | String | ○ | 表示名 |\n");
        var report = await _validation.ValidateAsync("test/v1.data.yml", "name: null\n", CancellationToken.None);
        Assert.Contains(report.Issues, issue => issue.Code == "FIELD_REQUIRED" && issue.Path == "/name");
    }

    [Theory]
    [InlineData("10.features.item/10.material/v1.10a00001.sample.yml", "id: 10a00001\nname: 素材\nicon: NETHER_STAR\n", "10a00001", "素材", "NETHER_STAR", "yaml")]
    [InlineData("35.features.skilltree/nodes/1000.json", "{\"nodeId\":\"1000\",\"name\":\"ノード\",\"icon\":\"minecraft:nether_star\"}", "1000", "ノード", "minecraft:nether_star", "json")]
    public async Task ListIncludesRootStringIconAndPreservesSummaryMetadata(string path, string raw, string id, string name, string icon, string format)
    {
        Write(path, raw);
        var document = await _service.ReadAsync(path, CancellationToken.None);
        var summary = Assert.Single(await _service.ListAsync(null, id, CancellationToken.None));
        var file = new FileInfo(_paths.Resolve(path));

        Assert.Equal(path, summary.Path);
        Assert.Equal(path[..path.LastIndexOf('/')], summary.Category);
        Assert.Equal(format, summary.Format);
        Assert.Equal(id, summary.Id);
        Assert.Equal(name, summary.Name);
        Assert.Equal(icon, summary.Icon);
        Assert.Equal(file.Length, summary.Size);
        Assert.Equal(file.LastWriteTimeUtc, summary.ModifiedUtc);
        Assert.Equal(document.Revision, summary.Revision);
        Assert.Null(summary.ParseError);
        Assert.False(summary.ReadOnly);
    }

    [Theory]
    [InlineData("yml", "id: sample\n")]
    [InlineData("json", "{\"id\":\"sample\"}")]
    [InlineData("yml", "id: sample\nicon: null\n")]
    [InlineData("json", "{\"id\":\"sample\",\"icon\":null}")]
    [InlineData("yml", "id: sample\nicon: 12\n")]
    [InlineData("json", "{\"id\":\"sample\",\"icon\":12}")]
    [InlineData("yml", "id: sample\nicon: false\n")]
    [InlineData("json", "{\"id\":\"sample\",\"icon\":false}")]
    [InlineData("yml", "id: sample\nicon: [NETHER_STAR]\n")]
    [InlineData("json", "{\"id\":\"sample\",\"icon\":[\"NETHER_STAR\"]}")]
    [InlineData("yml", "id: sample\nicon: {material: NETHER_STAR}\n")]
    [InlineData("json", "{\"id\":\"sample\",\"icon\":{\"material\":\"NETHER_STAR\"}}")]
    public async Task ListReturnsNullForAbsentOrNonStringIcon(string extension, string raw)
    {
        Write("test/sample." + extension, raw);
        var summary = Assert.Single(await _service.ListAsync("test", "", CancellationToken.None));

        Assert.Equal("sample", summary.Id);
        Assert.Null(summary.Icon);
        Assert.Null(summary.ParseError);
    }

    [Theory]
    [InlineData("")]
    [InlineData(" ")]
    [InlineData(null)]
    public async Task EmptyCategoryMeansAllFiles(string? category)
    {
        Write("test/v1.data.yml", "id: sample\n");
        Assert.Single(await _service.ListAsync(category, "", CancellationToken.None));
    }

    [Fact]
    public async Task WindowsCaseVariantsCannotBypassReferenceDeletionOrMetadataValidation()
    {
        if (!OperatingSystem.IsWindows()) return;
        const string itemPath = "10.features.item/10.material/v1.10a00001.sample.yml";
        Write(itemPath, "schemaVersion: 1\nid: 10a00001\ncategory: material\nname: sample\n");
        Write("30.features.skill/v1.skill.yml", "id: skill\nitemId:\n  ref: item:10a00001\n");
        var original = await _service.ReadAsync(itemPath, CancellationToken.None);
        var changedCase = "10.FEATURES.ITEM/10.MATERIAL/V1.10A00001.SAMPLE.YML";
        await Assert.ThrowsAsync<ArgumentException>(() => _service.DeleteAsync(changedCase, original.Revision, CancellationToken.None));
        await Assert.ThrowsAsync<ArgumentException>(() => _validation.ValidateAsync(changedCase, "name: null\n", CancellationToken.None));
        await Assert.ThrowsAsync<ArgumentException>(() => _catalog.GetAsync("10.FEATURES.ITEM/10.MATERIAL", CancellationToken.None));
        Assert.Equal(original.Raw, (await _service.ReadAsync(itemPath, CancellationToken.None)).Raw);
        Assert.Single(await _service.ReferencesAsync(itemPath, CancellationToken.None));
        Assert.Equal("item", MasterDataService.Kind(changedCase));
        Assert.Equal("tag", MasterDataService.Kind("76.SHARED.TAG/v1.tags.yml"));
        Assert.Equal("status", MasterDataService.Kind("75.SHARED.STATUS/v1.status_types.yml"));
    }

    [Fact]
    public async Task WindowsCaseVariantsCannotBypassNodeAllocationAndSemanticValidation()
    {
        if (!OperatingSystem.IsWindows()) return;
        const string nodePath = "35.features.skilltree/nodes/1000.json";
        Write(nodePath, "{\"nodeId\":\"1000\"}");
        const string changedCase = "35.FEATURES.SKILLTREE/NODES/1000.json";
        await Assert.ThrowsAsync<ArgumentException>(() => _service.CreateAsync(new("35.FEATURES.SKILLTREE/NODES/1001.json", "{\"nodeId\":\"1001\"}"), CancellationToken.None));
        await Assert.ThrowsAsync<ArgumentException>(() => _validation.ValidateAsync(changedCase, "{\"nodeId\":\"1000\"}", CancellationToken.None));
        await Assert.ThrowsAsync<UnauthorizedAccessException>(() => _service.CreateAsync(new("35.features.skilltree/nodes/1001.json", "{}"), CancellationToken.None));
        var report = await _validation.ValidateAsync(nodePath, "{\"nodeId\":\"1000\"}", CancellationToken.None);
        Assert.False(report.IsValid);
        Assert.Contains(report.Issues, issue => issue.Code == "SCHEMA_REFERENCE_REQUIRED");
    }

    [Fact]
    public async Task CanonicalCasingOfArbitraryFoldersIsPreserved()
    {
        const string path = "CustomFolder/MixedCase.yml";
        var created = await _service.CreateAsync(new(path, "id: custom\nname: test\n"), CancellationToken.None);
        Assert.Equal(path, created.Path);
        Assert.Equal("test", created.Content!["name"]!.ToString());
        if (OperatingSystem.IsWindows())
            await Assert.ThrowsAsync<ArgumentException>(() => _service.ReadAsync("customfolder/MixedCase.yml", CancellationToken.None));
    }

    [Fact]
    public void LinkedDirectoriesCannotEscapeFilebase()
    {
        var target = Path.Combine(_root, "outside");
        var link = Path.Combine(_paths.Root, "linked");
        Directory.CreateDirectory(target);
        if (OperatingSystem.IsWindows())
        {
            var start = new System.Diagnostics.ProcessStartInfo("cmd.exe") { CreateNoWindow = true, UseShellExecute = false, RedirectStandardOutput = true, RedirectStandardError = true };
            start.ArgumentList.Add("/c");
            start.ArgumentList.Add("mklink");
            start.ArgumentList.Add("/J");
            start.ArgumentList.Add(link);
            start.ArgumentList.Add(target);
            using var process = System.Diagnostics.Process.Start(start)!;
            process.WaitForExit();
            Assert.Equal(0, process.ExitCode);
        }
        else Directory.CreateSymbolicLink(link, target);
        try
        {
            Assert.Throws<UnauthorizedAccessException>(() => _paths.Resolve("linked/data.yml"));
            Assert.DoesNotContain(_paths.Enumerate(), entry => entry.Contains("linked", StringComparison.Ordinal));
        }
        finally { Directory.Delete(link); }
    }

    [Fact]
    public async Task RenumberedQuestCatalogValidatesItemsInBothRequirementAndRewardArrays()
    {
        const string category = "51.features.quest";
        Write(category + "/docs.quest.YAMLスキーマ定義.md", """
            # quest
            ## スキーマ定義
            | Field | Type | Required | Description |
            |---|---|---|---|
            | `acceptRequirements.items` | object[] | no | 受領アイテム |
            | `rewards.items` | object[] | no | 報酬アイテム |
            ### item
            | Field | Type | Required | Description |
            |---|---|---|---|
            | `amount` | number | yes | 数量 |
            """);
        var catalog = await _catalog.GetAsync(category, CancellationToken.None);
        Assert.Contains(catalog.Fields, field => field.Path == "/acceptRequirements/items/*/amount");
        Assert.Contains(catalog.Fields, field => field.Path == "/rewards/items/*/amount");
        Assert.DoesNotContain(catalog.Fields, field => field.Path == "/amount");
        var invalid = await _validation.ValidateAsync(category + "/v1.patrol.yml",
            "id: patrol\nacceptRequirements:\n  items: [{}]\nrewards:\n  items: [{}]\n", CancellationToken.None);
        Assert.Contains(invalid.Issues, issue => issue.Code == "FIELD_REQUIRED" && issue.Path == "/acceptRequirements/items/0/amount");
        Assert.Contains(invalid.Issues, issue => issue.Code == "FIELD_REQUIRED" && issue.Path == "/rewards/items/0/amount");
        var valid = await _validation.ValidateAsync(category + "/v1.patrol.yml",
            "id: patrol\nacceptRequirements:\n  items: [{amount: 1}]\nrewards:\n  items: [{amount: 2}]\n", CancellationToken.None);
        Assert.True(valid.IsValid);
    }

    [Theory]
    [InlineData("51.features.quest", "quest")]
    [InlineData("52.features.quest_board", "quest_board")]
    public async Task RenumberedQuestMastersPreserveIdentityCommentsAndUnknownFieldsOnSave(string category, string kind)
    {
        var path = category + "/v1.patrol.yml";
        const string raw = "# preserved\nid: patrol\nname: Patrol\nfutureField: retained\n";
        var created = await _service.CreateAsync(new(path, raw), CancellationToken.None);
        var updated = raw.Replace("name: Patrol", "name: Patrol updated", StringComparison.Ordinal);
        var saved = await _service.SaveAsync(new(path, updated, created.Revision), CancellationToken.None);
        Assert.Equal(updated, saved.Raw);
        Assert.Equal("patrol", saved.Content!["id"]!.ToString());
        Assert.Equal(kind, MasterDataService.Kind(path));
        Assert.Equal(category, Assert.Single(await _service.ListAsync(category, "", CancellationToken.None)).Category);
    }

    private void Write(string path, string raw)
    {
        var full = Path.Combine(_paths.Root, path.Replace('/', Path.DirectorySeparatorChar));
        Directory.CreateDirectory(Path.GetDirectoryName(full)!);
        File.WriteAllText(full, raw);
    }

    public void Dispose()
    {
        if (Directory.Exists(_root)) Directory.Delete(_root, recursive: true);
    }
}
