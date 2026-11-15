using SkillTreeEditor.Server.Services;

namespace SkillTreeEditor.Server.Tests;

public sealed class FishingMasterEditorTests : IDisposable
{
    private readonly string root = Path.Combine(Path.GetTempPath(), "fishing-editor-tests-" + Guid.NewGuid().ToString("N"));
    private readonly MasterDataPaths paths;
    private readonly MasterDataCodec codec = new();
    private readonly MasterDataValidation validation;
    private readonly MasterDataService service;

    public FishingMasterEditorTests()
    {
        Directory.CreateDirectory(Path.Combine(root, "40_filebase"));
        Directory.CreateDirectory(Path.Combine(root, "10_plugin"));
        Directory.CreateDirectory(Path.Combine(root, "60_tool"));
        var workspace = new WorkspacePaths(root);
        paths = new(workspace);
        var backups = new BackupService(workspace);
        var tree = new ValidationService(workspace, new SchemaCatalog(workspace), new PluginConfigService(workspace, backups), new MasterTagCatalog(workspace));
        validation = new(paths, codec, new MasterDataCatalog(paths), tree);
        service = new(paths, workspace, codec, validation, new WorkspaceMutationGate(workspace), backups);
        Write("60.features.world/v1.verdia.yml", "id: verdia\nname: Verdía\n");
        Write("10.features.item/10.material/v1.10a00001.cod.yml", "id: 10a00001\ncategory: material\nname: Cod\nmaxStack: 64\n");
        Write("44.features.fishing.fish/v1.river_cod.yml", Fish);
        Write("47.features.fishing.rules/v1.default.yml", Rules);
    }

    private const string Fish = """
        schemaVersion: 1
        id: river_cod
        type: FISH
        name: River Cod
        entityType: COD
        maxHealth: 20
        swimSpeed: 0.13
        fleeSpeed: 0.26
        pullPerTick: 0.008
        biteChancePercent: 45
        landFleeSpeed: 0.16
        hopSpeed: 0.32
        caughtItemId: {ref: 'item:10a00001'}
        caughtAmount: 1
        """;
    private const string Rules = """
        schemaVersion: 1
        id: default
        type: FISHING_RULES
        searchRadius: 10
        fishCountMin: 4
        fishCountMax: 6
        waitingTicks: 1200
        attackTicks: 40
        turnTicks: 80
        reelMultiplier: 0.30
        slackDistance: 0.65
        landingTicks: 14
        hangingTicks: 100
        escapeTicks: 1200
        nibbleTicks: 24
        nibbleCycleTicks: 8
        nibbleDistance: 0.15
        reelCooldownTicks: 6
        """;
    private const string Area = """
        schemaVersion: 1
        id: verdia_river
        type: FISHING_AREA
        name: Verdía River
        worldId: {ref: 'world:verdia'}
        rulesId: {ref: 'fishing_rules:default'}
        enabled: true
        levelRanges:
          - minLevel: 0
            maxLevel: 9
            fish:
              - fishId: {ref: 'fishing_fish:river_cod'}
                chancePercent: 100
          - minLevel: 10
            maxLevel: 2147483647
            fish:
              - fishId: {ref: 'fishing_fish:river_cod'}
                chancePercent: 100
        """;

    [Fact]
    public async Task ValidFishingMastersCanBeSavedWithoutLosingCommentsOrUnknownKeys()
    {
        const string areaPath = "46.features.fishing.area/v1.verdia_river.yml";
        var raw = "# keep this comment\n" + Area + "\ncustomFutureField: retained\n";
        var created = await service.CreateAsync(new(areaPath, raw, false, null, null, false), CancellationToken.None);
        Assert.Equal(raw, created.Raw);
        var changed = raw.Replace("Verdía River", "Verdía Fishing", StringComparison.Ordinal);
        var saved = await service.SaveAsync(new(areaPath, changed, created.Revision), CancellationToken.None);
        Assert.Equal(changed, saved.Raw);
        Assert.Equal("fishing_fish", MasterDataService.Kind("44.features.fishing.fish/v1.river_cod.yml"));
        Assert.Equal("fishing_area", MasterDataService.Kind(areaPath));
        Assert.Equal("fishing_rules", MasterDataService.Kind("47.features.fishing.rules/v1.default.yml"));
        var candidates = await service.CandidatesAsync(CancellationToken.None);
        Assert.Contains(candidates, candidate => candidate.Kind == "fishing_fish" && candidate.Value == "river_cod");
    }

    [Theory]
    [InlineData("chancePercent: 100", "chancePercent: 99", "FISHING_CHANCE_SUM")]
    [InlineData("maxLevel: 9", "maxLevel: 10", "FISHING_LEVEL_OVERLAP")]
    [InlineData("fishing_fish:river_cod", "fishing_fish:missing", "FISHING_REFERENCE_NOT_FOUND")]
    [InlineData("world:verdia", "world:missing", "FISHING_REFERENCE_NOT_FOUND")]
    public async Task AreaValidationReportsBadDistributionBandsAndReferences(string before, string after, string code)
    {
        var edited = Area.Replace(before, after, StringComparison.Ordinal);
        var report = await validation.ValidateAsync("46.features.fishing.area/v1.verdia_river.yml", edited, CancellationToken.None);
        Assert.Contains(report.Issues, issue => issue.Code == code);
    }

    [Fact]
    public async Task EnabledAreasMustBeUniquePerWorld()
    {
        Write("46.features.fishing.area/v1.first.yml", Area);
        var duplicate = Area.Replace("verdia_river", "second", StringComparison.Ordinal);
        var report = await validation.ValidateAsync("46.features.fishing.area/v1.second.yml", duplicate, CancellationToken.None);
        Assert.Contains(report.Issues, issue => issue.Code == "FISHING_DUPLICATE_ENABLED_WORLD");
    }

    [Fact]
    public async Task FishAndRulesRejectInvalidRuntimeBounds()
    {
        var fish = await validation.ValidateAsync("44.features.fishing.fish/v1.other.yml", Fish.Replace("caughtAmount: 1", "caughtAmount: 65", StringComparison.Ordinal), CancellationToken.None);
        Assert.Contains(fish.Issues, issue => issue.Code == "FISHING_CAUGHT_AMOUNT");
        var rules = await validation.ValidateAsync("47.features.fishing.rules/v1.other.yml", Rules.Replace("fishCountMax: 6", "fishCountMax: 2", StringComparison.Ordinal), CancellationToken.None);
        Assert.Contains(rules.Issues, issue => issue.Code == "FISHING_FISH_COUNT_RANGE");
    }

    [Fact]
    public async Task CaughtAmountCannotExceedReferencedItemStack()
    {
        Write("10.features.item/10.material/v1.10a00002.small.yml", "id: 10a00002\ncategory: material\nname: Small stack\nmaxStack: 2\n");
        var raw = Fish.Replace("item:10a00001", "item:10a00002", StringComparison.Ordinal)
            .Replace("caughtAmount: 1", "caughtAmount: 3", StringComparison.Ordinal);
        var report = await validation.ValidateAsync("44.features.fishing.fish/v1.other.yml", raw, CancellationToken.None);
        Assert.Contains(report.Issues, issue => issue.Code == "FISHING_CAUGHT_STACK" && issue.Path == "/caughtAmount");
    }

    [Fact]
    public async Task DuplicateFishWithinBandIsRejected()
    {
        var raw = Area.Replace("chancePercent: 100", "chancePercent: 50\n      - fishId: {ref: 'fishing_fish:river_cod'}\n        chancePercent: 50", StringComparison.Ordinal);
        var report = await validation.ValidateAsync("46.features.fishing.area/v1.verdia_river.yml", raw, CancellationToken.None);
        Assert.Contains(report.Issues, issue => issue.Code == "FISHING_DUPLICATE_FISH");
    }

    [Fact]
    public async Task RulesAllowFractionalSearchRadiusAndReferenceMapsPreserveFutureKeys()
    {
        var rules = await validation.ValidateAsync("47.features.fishing.rules/v1.other.yml",
            Rules.Replace("searchRadius: 10", "searchRadius: 0.5", StringComparison.Ordinal), CancellationToken.None);
        Assert.DoesNotContain(rules.Issues, issue => issue.Code == "FISHING_SEARCH_RADIUS");
        var fish = await validation.ValidateAsync("44.features.fishing.fish/v1.other.yml",
            Fish.Replace("{ref: 'item:10a00001'}", "{ref: 'item:10a00001', futureKey: retained}", StringComparison.Ordinal), CancellationToken.None);
        Assert.DoesNotContain(fish.Issues, issue => issue.Code == "FISHING_REFERENCE");
    }

    [Fact]
    public async Task FishingCatalogReadsJapaneseFieldsThroughLocalSchemaReferences()
    {
        Write("46.features.fishing.area/docs.fishing-area.YAMLスキーマ定義.md", "# 釣り場\n説明文のみ。\n");
        Write("46.features.fishing.area/schemas/fishing-area.v1.schema.json", """
            {
              "type": "object",
              "required": ["worldId", "levelRanges"],
              "properties": {
                "worldId": {"title": "ワールド", "$ref": "#/$defs/worldRef"},
                "levelRanges": {"title": "釣りレベル帯", "type": "array", "items": {"$ref": "#/$defs/band"}}
              },
              "$defs": {
                "worldRef": {"type": "object", "required": ["ref"], "properties": {"ref": {"type": "string"}}},
                "band": {
                  "type": "object", "required": ["fish"],
                  "properties": {
                    "fish": {
                      "title": "魚の候補", "type": "array",
                      "items": {"type": "object", "properties": {"fishId": {"title": "魚", "type": "object"}}}
                    }
                  }
                }
              }
            }
            """);
        var category = await new MasterDataCatalog(paths).GetAsync("46.features.fishing.area", CancellationToken.None);
        Assert.Contains(category.Fields, field => field.Path == "/worldId" && field.Label == "ワールド" && field.Reference == "world");
        Assert.Contains(category.Fields, field => field.Path == "/levelRanges/*/fish/*/fishId" && field.Label == "魚" && field.Reference == "fishing_fish");
    }

    private void Write(string relative, string raw)
    {
        var file = Path.Combine(root, "40_filebase", relative.Replace('/', Path.DirectorySeparatorChar));
        Directory.CreateDirectory(Path.GetDirectoryName(file)!);
        File.WriteAllText(file, raw);
    }
    public void Dispose() => Directory.Delete(root, true);
}
