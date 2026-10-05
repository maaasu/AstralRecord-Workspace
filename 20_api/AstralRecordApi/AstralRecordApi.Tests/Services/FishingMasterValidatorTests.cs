using System.Text.Json.Nodes;
using System.Reflection;
using System.Text.Json;
using AstralRecordApi.Models;
using AstralRecordApi.Services;
using Xunit;

namespace AstralRecordApi.Tests.Services;

public sealed class FishingMasterValidatorTests
{
    [Fact]
    public void ProductionFishingDefinitions_AreConsistentWithoutDatabase()
    {
        var root = new DirectoryInfo(AppContext.BaseDirectory);
        while (root is not null && !Directory.Exists(Path.Combine(root.FullName, "40_filebase")))
            root = root.Parent;
        Assert.NotNull(root);
        var basePath = Path.Combine(root.FullName, "40_filebase");
        var sources = new (string Type, string Path, string? Category)[]
        {
            ("fishing_fish", "44.features.fishing.fish", null),
            ("fishing_area", "46.features.fishing.area", null),
            ("fishing_rules", "47.features.fishing.rules", null),
            ("item", "10.features.item/10.material", "material"),
            ("world", "60.features.world", null),
            ("loot.table", "80.shared.loot/table", null),
        };
        var parser = typeof(MasterDataSeeder).Assembly
            .GetType("AstralRecordApi.Utilities.MasterDataYamlParser")!
            .GetMethod("ParseObject", BindingFlags.Static | BindingFlags.Public)!;
        var entries = new List<FishingMasterEntry>();
        foreach (var source in sources)
        {
            foreach (var path in Directory.EnumerateFiles(Path.Combine(basePath, source.Path), "*.yml"))
            {
                var payload = (JsonObject)parser.Invoke(null, [File.ReadAllText(path), path])!;
                entries.Add(new FishingMasterEntry(source.Type, payload["id"]!.ToString(),
                    source.Category, payload.ToJsonString()));
            }
        }
        FishingMasterValidator.Validate(entries);
    }

    /// <summary>設計入力: 40_filebase/46.features.fishing.area/docs.fishing-area.YAMLスキーマ定義.md。</summary>
    [Fact]
    public void ValidBandAndStackableReward_AreAccepted()
        => FishingMasterValidator.Validate(Fixture());

    [Fact]
    public void UnknownEditorFields_ArePreservedByValidation()
    {
        var entries = Fixture();
        var area = entries.Single(e => e.Type == "fishing_area");
        var root = JsonNode.Parse(area.PayloadJson)!.AsObject();
        root["editorNote"] = "保持する";
        entries[entries.IndexOf(area)] = area with { PayloadJson = root.ToJsonString() };
        FishingMasterValidator.Validate(entries);
    }

    [Fact]
    public void AreaResponse_FlattensReferenceObjectsForApi()
    {
        var json = Fixture().Single(e => e.Type == "fishing_area").PayloadJson;
        var helper = typeof(MasterDataSeeder).Assembly
            .GetType("AstralRecordApi.Repositories.MasterDataPayloadJson")!;
        var deserialize = helper.GetMethod("Deserialize", BindingFlags.Static | BindingFlags.Public)!
            .MakeGenericMethod(typeof(FishingAreaResponse));
        var response = (FishingAreaResponse)deserialize.Invoke(null, [json])!;
        Assert.Equal("world:verdia", response.WorldId);
        Assert.Equal("fishing_rules:default", response.RulesId);
        Assert.Equal("fishing_fish:river_cod", response.LevelRanges[0].Fish[0].FishId);
        var encoded = JsonSerializer.Serialize(response, new JsonSerializerOptions(JsonSerializerDefaults.Web));
        Assert.Contains("\"worldId\":\"world:verdia\"", encoded, StringComparison.Ordinal);
    }

    /// <summary>抽選率・重複帯・有効ワールド制約は、独立に拒否する。</summary>
    [Theory]
    [InlineData("chancePercent", "99", "chancePercent sum")]
    [InlineData("maxLevel", "-1", "maxLevel")]
    [InlineData("fishId", "{\"ref\":\"fishing_fish:missing\"}", "fishId")]
    public void InvalidBand_IsRejected(string key, string value, string reason)
    {
        var entries = Fixture();
        var area = entries.Single(e => e.Type == "fishing_area");
        var root = JsonNode.Parse(area.PayloadJson)!.AsObject();
        var range = root["levelRanges"]![0]!.AsObject();
        if (key == "chancePercent") range["fish"]![0]![key] = JsonNode.Parse(value);
        else if (key == "fishId") range["fish"]![0]![key] = JsonNode.Parse(value);
        else range[key] = JsonNode.Parse(value);
        entries[entries.IndexOf(area)] = area with { PayloadJson = root.ToJsonString() };
        Assert.Contains(reason, Assert.Throws<InvalidOperationException>(() =>
            FishingMasterValidator.Validate(entries)).Message, StringComparison.Ordinal);
    }

    [Fact]
    public void OverlappingBands_AreRejected()
    {
        var entries = Fixture();
        var area = entries.Single(e => e.Type == "fishing_area");
        var root = JsonNode.Parse(area.PayloadJson)!.AsObject();
        root["levelRanges"]!.AsArray().Add(root["levelRanges"]![0]!.DeepClone());
        entries[entries.IndexOf(area)] = area with { PayloadJson = root.ToJsonString() };
        Assert.Contains("overlapping", Assert.Throws<InvalidOperationException>(() =>
            FishingMasterValidator.Validate(entries)).Message, StringComparison.Ordinal);
    }

    [Fact]
    public void TwoEnabledAreasForWorld_AreRejectedButDisabledAreaIsAllowed()
    {
        var entries = Fixture();
        var area = entries.Single(e => e.Type == "fishing_area");
        var duplicate = JsonNode.Parse(area.PayloadJson)!.AsObject();
        duplicate["id"] = "other";
        entries.Add(new FishingMasterEntry("fishing_area", "other", null, duplicate.ToJsonString()));
        Assert.Contains("multiple enabled", Assert.Throws<InvalidOperationException>(() =>
            FishingMasterValidator.Validate(entries)).Message, StringComparison.Ordinal);
        duplicate["enabled"] = false;
        entries[^1] = entries[^1] with { PayloadJson = duplicate.ToJsonString() };
        FishingMasterValidator.Validate(entries);
    }

    [Fact]
    public void UnstackableGuaranteedReward_IsRejected()
    {
        var entries = Fixture();
        entries[0] = entries[0] with { Category = "equipment" };
        Assert.Contains("stackable", Assert.Throws<InvalidOperationException>(() =>
            FishingMasterValidator.Validate(entries)).Message, StringComparison.Ordinal);
    }

    [Fact]
    public void ExcessiveFishCount_IsRejected()
    {
        var entries = Fixture();
        var rules = entries.Single(e => e.Type == "fishing_rules");
        var root = JsonNode.Parse(rules.PayloadJson)!.AsObject();
        root["fishCountMax"] = 33;
        entries[entries.IndexOf(rules)] = rules with { PayloadJson = root.ToJsonString() };
        Assert.Contains("fishCountMax", Assert.Throws<InvalidOperationException>(() =>
            FishingMasterValidator.Validate(entries)).Message, StringComparison.Ordinal);
    }

    private static List<FishingMasterEntry> Fixture() =>
    [
        new("item", "10a00060", "material", """{"id":"10a00060","maxStack":64}"""),
        new("world", "verdia", null, """{"id":"verdia"}"""),
        new("fishing_fish", "river_cod", null, """{"schemaVersion":1,"id":"river_cod","type":"FISH","name":"川ダラ","entityType":"COD","maxHealth":20,"swimSpeed":0.13,"fleeSpeed":0.26,"pullPerTick":0.008,"biteChancePercent":45,"landFleeSpeed":0.16,"hopSpeed":0.32,"caughtItemId":{"ref":"item:10a00060"},"caughtAmount":1}"""),
        new("fishing_rules", "default", null, """{"schemaVersion":1,"id":"default","type":"FISHING_RULES","searchRadius":10,"fishCountMin":4,"fishCountMax":6,"waitingTicks":1200,"attackTicks":40,"turnTicks":80,"reelMultiplier":0.3,"slackDistance":0.65,"landingTicks":14,"hangingTicks":100,"escapeTicks":1200,"nibbleTicks":24,"nibbleCycleTicks":8,"nibbleDistance":0.15,"reelCooldownTicks":6}"""),
        new("fishing_area", "verdia", null, """{"schemaVersion":1,"id":"verdia","type":"FISHING_AREA","name":"ヴェルディアの水辺","worldId":{"ref":"world:verdia"},"rulesId":{"ref":"fishing_rules:default"},"enabled":true,"levelRanges":[{"minLevel":0,"maxLevel":2147483647,"fish":[{"fishId":{"ref":"fishing_fish:river_cod"},"chancePercent":100}]}]}""")
    ];
}
