using System.Text.Json.Nodes;
using SkillTreeEditor.Server.Services;

namespace SkillTreeEditor.Server.Tests;

public sealed class BalanceSkillBuffsTests
{
    [Theory]
    [InlineData("FLAT", 12.5)]
    [InlineData("SCALAR", .35)]
    [InlineData("FINAL_SCALAR", .25)]
    public void ActualBuffModifierAndDurationReplaceSkillParameters(string mode, double value)
    {
        var definition = Definition();
        var buff = Buff("hunter_build_up");
        buff["durationTicks"] = 150;
        buff["modifiers"]![0]!["type"] = mode;
        buff["modifiers"]![0]!["value"] = value;
        var resolved = Resolve(definition, Catalog(buff));

        Assert.True(resolved.Supported, string.Join(" / ", resolved.Reasons));
        var stat = Assert.Single(resolved.Effects, effect => effect.Kind == "stat");
        Assert.Equal("RANGED_ATTACK", stat.Status);
        Assert.Equal(mode, stat.Mode);
        Assert.Equal(value, stat.Value);
        Assert.Equal(150, stat.DurationTicks);
        Assert.Equal(10, resolved.Effects.Count(effect => effect.Kind == "resource"));
        Assert.Contains("test/hunter_build_up.yml", resolved.Sources);
        Assert.DoesNotContain("40_filebase/70.shared.buff/v1.hunter_build_up.yml", resolved.Sources);
    }

    [Fact]
    public void LevelTwoRecoveryResolvesTheExecutorSelectedBuffId()
    {
        var definition = Definition(.02, .12);
        var buff = Buff("hunter_build_up_lv2");
        buff["modifiers"]![0]!["value"] = .42;
        var resolved = Resolve(definition, Catalog(buff));

        Assert.True(resolved.Supported, string.Join(" / ", resolved.Reasons));
        Assert.Equal(.42, Assert.Single(resolved.Effects, effect => effect.Kind == "stat").Value);
        Assert.Contains("test/hunter_build_up_lv2.yml", resolved.Sources);
    }

    [Fact]
    public void MissingOrDuplicateReferencedBuffCannotProduceSupportedProfile()
    {
        var definition = Definition();
        var missing = Resolve(definition, Catalog(Buff("different")));
        Assert.False(missing.Supported);
        Assert.Contains(missing.Reasons, reason => reason.Contains("定義がありません"));

        var duplicated = Resolve(definition, Catalog(Buff("hunter_build_up"), Buff("hunter_build_up")));
        Assert.False(duplicated.Supported);
        Assert.Contains(duplicated.Reasons, reason => reason.Contains("複数あります"));
    }

    [Fact]
    public void AdditionalModifierOrUnknownEffectStopsInsteadOfSilentlyDroppingIt()
    {
        var definition = Definition();
        var extra = Buff("hunter_build_up");
        extra["modifiers"]!.AsArray().Add(new JsonObject { ["status"] = "ATTACK", ["type"] = "FLAT", ["value"] = 5 });
        Assert.False(Resolve(definition, Catalog(extra)).Supported);

        var unknown = Buff("hunter_build_up");
        unknown["onTick"] = new JsonObject { ["heal"] = 10 };
        var result = Resolve(definition, Catalog(unknown));
        Assert.False(result.Supported);
        Assert.Contains(result.Reasons, reason => reason.Contains("onTick"));
    }

    [Theory]
    [InlineData("ATTACK", "SCALAR", "hunter_build_up")]
    [InlineData("RANGED_ATTACK", "UNRECOGNIZED", "hunter_build_up")]
    [InlineData("RANGED_ATTACK", "SCALAR", "another_group")]
    public void ChangedStatusModeOrStackGroupStops(string status, string mode, string stackGroup)
    {
        var buff = Buff("hunter_build_up");
        buff["modifiers"]![0]!["status"] = status;
        buff["modifiers"]![0]!["type"] = mode;
        buff["stackGroup"] = stackGroup;
        Assert.False(Resolve(Definition(), Catalog(buff)).Supported);
    }

    [Fact]
    public void OtherImplementationsKeepTheirOriginalProfile()
    {
        var profile = new BalanceSkillProfile("other", [], [], [], [], []);
        var actual = BalanceSkillBuffs.Resolve(new JsonObject { ["implementationId"] = "other" }, Catalog(), profile);
        Assert.Same(profile, actual);
    }

    private static BalanceSkillProfile Resolve(JsonObject definition, JsonObject catalog)
    {
        var ranged = BalanceSkillRangedProfiles.Resolve(definition);
        Assert.NotNull(ranged);
        return BalanceSkillBuffs.Resolve(definition, catalog, ranged);
    }

    private static JsonObject Definition(double recovery = .01, double increase = .11) => new()
    {
        ["id"] = "build_up", ["implementationId"] = "hunter_build_up",
        ["params"] = new JsonObject { ["buffId"] = "buff:hunter_build_up", ["buffDurationTicks"] = 200,
            ["energyRecoveryRatio"] = recovery, ["rangedAttackIncreaseRatio"] = increase }
    };

    private static JsonObject Buff(string id) => new()
    {
        ["schemaVersion"] = 1, ["id"] = id, ["type"] = "BUFF", ["name"] = "Build Up",
        ["durationTicks"] = 200, ["isDebuff"] = false, ["resetOnChallenge"] = true,
        ["stackGroup"] = "hunter_build_up",
        ["modifiers"] = new JsonArray(new JsonObject { ["status"] = "RANGED_ATTACK", ["type"] = "SCALAR", ["value"] = .11 })
    };

    private static JsonObject Catalog(params JsonObject[] buffs) => new()
    {
        ["masters"] = new JsonArray(buffs.Select(buff => (JsonNode?)new JsonObject
        {
            ["kind"] = "buff", ["path"] = "test/" + buff["id"] + ".yml", ["content"] = buff.DeepClone()
        }).ToArray())
    };
}
