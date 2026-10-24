using System.Text.Json.Nodes;
using SkillTreeEditor.Server.Services;

namespace SkillTreeEditor.Server.Tests;

public sealed partial class BalanceRuntimeIntegrationTests
{
    [Fact]
    public void ExistingFireballCarriesPrismInterceptionMetadata()
    {
        var catalog = Catalog(); AddReal(catalog, "wizard_elemental_prism"); AddReal(catalog, "mage_fireball");
        var build = Build("wizard_elemental_prism", "mage_fireball"); build["baseStats"]!["MAGIC_ATTACK"] = 40;
        var result = Run(catalog, Request(2, 8), build);
        Assert.Single(Timeline(result, "prism_absorbed"));
        Assert.Contains(result["contributions"]!.AsArray(), row => row?["id"]?.ToString() == "wizard_elemental_prism" && row["damage"]!.GetValue<double>() > 0);
        Assert.Equal(0, result["contributions"]!.AsArray().Single(row => row?["id"]?.ToString() == "mage_fireball")!["damage"]!.GetValue<double>());
    }

    [Fact]
    public void MeditationRegenMultiplierIsRecomputedInsteadOfAccumulated()
    {
        var catalog = Catalog(); AddReal(catalog, "adventurer_meditation"); AddReal(catalog, "adventurer_meditation", "buff");
        AddSkill(catalog, Skill("adventurer_mana_burst", """{"range":7,"angle":120,"damageRatio":1,"maxTargets":1}""", cost: 80));
        var build = Build("adventurer_mana_burst"); build["passiveSkillIds"] = new JsonArray("adventurer_meditation");
        build["baseStats"]!["MP_REGEN"] = 5;
        var request = Request(5, 2); request["inputs"] = new JsonArray(new JsonObject { ["tick"] = 1, ["sneaking"] = true });
        var result = Run(catalog, request, build);
        Assert.Equal(27.5, LastResource(result)["mp"]!.GetValue<double>(), 10);
    }

    [Fact]
    public void BlinkFindsTheFlatFloorFromTheNormalJumpApex()
    {
        var catalog = Catalog(); AddReal(catalog, "mage_blink");
        var build = Build(); build["passiveSkillIds"] = new JsonArray("mage_blink");
        var request = Request(.1, 30); request["world"]!["playerPosition"] = new JsonArray(0, 1.25, 0);
        request["inputs"] = new JsonArray(new JsonObject { ["tick"] = 0, ["onGround"] = false, ["sneaking"] = true });
        var result = Run(catalog, request, build);
        Assert.Equal("[0,0,10]", LastResource(result)["playerPosition"]!.ToJsonString());
        Assert.Single(Timeline(result, "blink"));
    }

    [Fact]
    public void WorldPositionOverridesTheLegacyDistanceBeforeTargetSearch()
    {
        var catalog = Catalog(); AddReal(catalog, "swordsman_flame_rush");
        var request = Request(.5, 100); request["world"]!["enemyPosition"] = new JsonArray(0, 0, 2);
        var result = Run(catalog, request, Build("swordsman_flame_rush"));
        Assert.True(result["damageDealt"]!.GetValue<double>() > 0);
    }

    private static void AddReal(JsonObject catalog, string id, string kind = "skill")
    {
        var root = new DirectoryInfo(AppContext.BaseDirectory);
        while (root is not null && !Directory.Exists(Path.Combine(root.FullName, "40_filebase"))) root = root.Parent;
        var path = Path.Combine(root!.FullName, "40_filebase", kind == "buff" ? "70.shared.buff" : "30.features.skill", "v1." + id + ".yml");
        var parsed = new MasterDataCodec().Parse(path, File.ReadAllText(path));
        catalog["masters"]!.AsArray().Add(new JsonObject { ["kind"] = kind, ["path"] = Path.GetRelativePath(root.FullName, path).Replace('\\', '/'), ["content"] = parsed.Content!.DeepClone() });
    }
}
