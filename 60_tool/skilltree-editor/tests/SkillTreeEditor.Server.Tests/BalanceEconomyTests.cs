using System.Text.Json.Nodes;
using SkillTreeEditor.Server.Services;

namespace SkillTreeEditor.Server.Tests;

public sealed class BalanceEconomyTests
{
    private static JsonObject Parse(string json) => JsonNode.Parse(json)!.AsObject();
    private static JsonObject Combat(double success = 1) => Parse($$"""{"successRate":{{success}},"meanElapsedSeconds":10}""");

    [Fact]
    public void MissingCombatProbabilityCannotCreateWinningEconomy()
    {
        var result = BalanceEconomy.Estimate(SimpleCatalog(), SimpleRequest(), Parse("""{"validTrials":0,"successRate":null}"""), CancellationToken.None);
        Assert.Equal("unsupported", result["status"]!.ToString());
        Assert.Contains(result["diagnostics"]!.AsArray(), issue => issue?["code"]?.ToString() == "COMBAT_SUMMARY_MISSING");
        Assert.Null(result["collection"]?["expectedKills"]);
    }

    [Fact]
    public void CollectionBudgetIsSeparateFromSingleFightDeadline()
    {
        var request = SimpleRequest();
        request["maxSeconds"] = 1;
        var result = BalanceEconomy.Estimate(SimpleCatalog(), request, Combat(), CancellationToken.None);
        Assert.DoesNotContain(result["diagnostics"]!.AsArray(), issue => issue?["code"]?.ToString() == "TIME_BUDGET_EXCEEDED");
        Assert.Null(result["collection"]?["probabilityWithinBudget"]);
        request["economy"]!["timeBudgetSeconds"] = 1;
        var limited = BalanceEconomy.Estimate(SimpleCatalog(), request, Combat(), CancellationToken.None);
        Assert.Contains(limited["diagnostics"]!.AsArray(), issue => issue?["code"]?.ToString() == "TIME_BUDGET_EXCEEDED");
        Assert.Equal(0, limited["collection"]!["probabilityWithinBudget"]!.GetValue<double>());
    }

    [Fact]
    public void UnknownOrbCostIsNotReportedAsFreeUpgrade()
    {
        var catalog = SimpleCatalog();
        catalog["masters"]!.AsArray().Add(Parse("""{"kind":"item","path":"weapon.yml","content":{"id":"weapon","equipment":{"slot":"WEAPON","enhance":{"maxLevel":1,"levels":[{"level":1,"successRate":1,"failAction":"NONE"}]}}}}"""));
        var request = SimpleRequest();
        request["economy"]!["enhanceItemId"] = "weapon";
        request["economy"]!["targetEnhanceLevel"] = 1;
        var result = BalanceEconomy.Estimate(catalog, request, Combat(), CancellationToken.None);
        Assert.Null(result["enhancement"]!["expectedGold"]);
        Assert.Null(result["gold"]!["expense"]);
        Assert.Null(result["gold"]!["net"]);
    }

    [Fact]
    public void HugePickTailCollapsesWithoutChangingItsProbability()
    {
        var catalog = SimpleCatalog();
        catalog["masters"]![0]!["content"]!["drops"] = Parse("""{"lootTable":"loot_table:t"}""");
        catalog["masters"]!.AsArray().Add(Parse("""{"kind":"loot_table","path":"t.yml","content":{"id":"t","rolls":1,"pools":["loot_pool:p"]}}"""));
        var pool = Parse("""{"kind":"loot_pool","path":"p.yml","content":{"id":"p","pick":"0~10000","contents":[]}}""");
        for (var index = 0; index < 12; index++) pool["content"]!["contents"]!.AsArray().Add(Parse($$"""{"itemId":"item:{{(index == 0 ? "a" : "other")}}","rate":100,"amount":1}"""));
        catalog["masters"]!.AsArray().Add(pool);
        var result = BalanceEconomy.Estimate(catalog, SimpleRequest(), Combat(), CancellationToken.None);
        Assert.Equal("completed", result["status"]!.ToString());
        Assert.Equal(9994.5 / 10001, result["collection"]!["expectedItemsPerKill"]!.GetValue<double>(), 10);
        using var cancellation = new CancellationTokenSource(); cancellation.Cancel();
        Assert.Throws<OperationCanceledException>(() => BalanceEconomy.Estimate(catalog, SimpleRequest(), Combat(), cancellation.Token));
    }

    private static JsonObject SimpleCatalog() => Parse("""{"masters":[{"kind":"mob","path":"mob.yml","content":{"id":"boar","level":1,"drops":{"items":[{"itemId":"item:a","rate":100,"amount":1}]}}},{"kind":"item","path":"a.yml","content":{"id":"a","saleValue":4}}]}""");
    private static JsonObject SimpleRequest() => Parse("""{"mode":"strict","enemy":{"mobId":"boar","level":1},"economy":{"targetItemId":"a","quantity":1}}""");

    [Fact]
    public void BuildLuckUsesPluginGeneratedEffectiveDropProbability()
    {
        var catalog = SimpleCatalog();
        catalog["masters"]![0]!["content"]!["drops"]!["items"]![0]!["rate"] = 80;
        var combat = Combat(); combat["playerStats"] = Parse("""{"LUCK":10,"DROP_RATE_INCREASE":100}""");
        var result = BalanceEconomy.Estimate(catalog, SimpleRequest(), combat, CancellationToken.None);
        Assert.Equal(.88, result["collection"]!["expectedItemsPerKill"]!.GetValue<double>(), 10);
    }

    [Fact]
    public void TranscendenceUsesNestedEnhanceOverrides()
    {
        var catalog = SimpleCatalog();
        catalog["masters"]!.AsArray().Add(Parse("""{"kind":"item","path":"weapon.yml","content":{"id":"weapon","equipment":{"slot":"WEAPON","enhance":{"maxLevel":1,"levels":[{"level":1,"successRate":1},{"level":2,"successRate":1}]},"transcendence":[{"rank":1,"overrides":{"enhance":{"maxLevel":2}}}]}}}"""));
        var request = SimpleRequest();
        request["economy"]!["enhanceItemId"] = "weapon";
        request["economy"]!["targetEnhanceLevel"] = 2;
        request["economy"]!["transcendenceRank"] = 1;
        var result = BalanceEconomy.Estimate(catalog, request, Combat(), CancellationToken.None);
        Assert.Equal("completed", result["status"]!.ToString());
        Assert.Equal(2, result["enhancement"]!["expectedAttempts"]!.GetValue<double>(), 10);
    }

    [Fact]
    public void PoolFirstRollsIndependentlyThenUniformlyLimitsSuccesses()
    {
        var catalog = Parse("""
            {"masters":[
              {"kind":"mob","path":"mob.yml","content":{"id":"boar","level":1,"drops":{"exp":8,"lootTable":{"ref":"loot_table:t"}}}},
              {"kind":"item","path":"a.yml","content":{"id":"a","saleValue":4}},
              {"kind":"item","path":"b.yml","content":{"id":"b","saleValue":5}},
              {"kind":"loot_table","path":"t.yml","content":{"id":"t","rolls":1,"pools":[{"ref":"loot_pool:p"}]}},
              {"kind":"loot_pool","path":"p.yml","content":{"id":"p","pick":1,"contents":[
                {"itemId":{"ref":"item:a"},"rate":100,"amount":1},
                {"itemId":{"ref":"item:b"},"rate":100,"amount":1}]}}]}
            """);
        var request = Parse("""{"mode":"strict","enemy":{"mobId":"boar","level":1},"economy":{"targetItemId":"a","quantity":1,"sellItemIds":["b"]}}""");
        var result = BalanceEconomy.Estimate(catalog, request, Combat(), CancellationToken.None);
        Assert.Equal("completed", result["status"]?.ToString());
        Assert.Equal(0.5, (double)result["collection"]!["expectedItemsPerKill"]!, 8);
        Assert.Equal(2, (double)result["collection"]!["expectedKills"]!, 8);
        Assert.Equal(1, (int)result["collection"]!["medianKills"]!);
        Assert.Equal(4, (int)result["collection"]!["p90Kills"]!);
        Assert.Equal(5, (double)result["gold"]!["income"]!, 8);
    }

    [Fact]
    public void LevelProfileMergesOnlyOverriddenDropFieldsAndVictoryChangesAttempts()
    {
        var catalog = Parse("""
            {"masters":[
              {"kind":"mob","path":"mob.yml","content":{"id":"boar","level":1,"drops":{"exp":8,"items":[{"itemId":{"ref":"item:a"},"rate":100,"amount":2}]},"levels":[{"level":2,"drops":{"exp":16}}]}},
              {"kind":"item","path":"a.yml","content":{"id":"a","saleValue":4}}]}
            """);
        var request = Parse("""{"mode":"strict","enemy":{"mobId":"boar","level":2},"economy":{"targetItemId":"a","quantity":3}}""");
        var result = BalanceEconomy.Estimate(catalog, request, Combat(0.5), CancellationToken.None);
        Assert.Equal("completed", result["status"]?.ToString());
        Assert.Equal(4, (double)result["collection"]!["expectedKills"]!, 8);
        Assert.Equal(3, (int)result["collection"]!["medianKills"]!);
        Assert.Equal(15, result["growth"]!["experiencePerVictory"]!.GetValue<int>());
    }

    [Fact]
    public void UnreachableTargetIsExplicit()
    {
        var catalog = Parse("""{"masters":[{"kind":"mob","path":"mob.yml","content":{"id":"boar","drops":{"exp":8}}},{"kind":"item","path":"a.yml","content":{"id":"a"}}]}""");
        var request = Parse("""{"mode":"strict","enemy":{"mobId":"boar","level":1},"economy":{"targetItemId":"a","quantity":1}}""");
        var result = BalanceEconomy.Estimate(catalog, request, Combat(), CancellationToken.None);
        Assert.Equal("unreachable", result["status"]?.ToString());
        Assert.Contains(result["diagnostics"]!.AsArray(), issue => issue?["code"]?.ToString() == "TARGET_UNREACHABLE");
    }

    [Fact]
    public void DecreaseOneFailureIsIncludedInUpgradeDistribution()
    {
        var catalog = Parse("""
            {"masters":[
              {"kind":"mob","path":"mob.yml","content":{"id":"boar","drops":{"exp":8}}},
              {"kind":"item","path":"weapon.yml","content":{"id":"weapon","equipment":{"slot":"WEAPON","enhance":{"maxLevel":2,"levels":[
                {"level":1,"successRate":0.5,"failAction":"NONE"},
                {"level":2,"successRate":0.5,"failAction":"DECREASE_ONE"}]}}}},
              {"kind":"item","path":"orb.yml","content":{"id":"orb","saleValue":10,"orb":{"effect":{"type":"ENHANCE","targetSlots":["WEAPON"],"chargeSaleValue":true}}}}]}
            """);
        var request = Parse("""{"mode":"strict","enemy":{"mobId":"boar","level":1},"economy":{"enhanceItemId":"weapon","fromEnhanceLevel":0,"targetEnhanceLevel":2,"orbItemId":"orb"}}""");
        var result = BalanceEconomy.Estimate(catalog, request, Combat(), CancellationToken.None);
        Assert.Equal("completed", result["status"]?.ToString());
        Assert.Equal(6, (double)result["enhancement"]!["expectedAttempts"]!, 8);
        Assert.Equal(60, (double)result["gold"]!["expense"]!, 8);
    }

    [Fact]
    public void RealPoolRatesReduceOrbChanceAfterPickAndReportStockRisk()
    {
        var catalog = Parse("""
            {"masters":[
              {"kind":"mob","path":"40_filebase/40.features.mob/enemy/v1.midgard_grassboar.yml","content":{"id":"midgard_grassboar","level":1,"drops":{"exp":8,"lootTable":{"ref":"loot_table:normal_enemy_common_table_tier_1"}}}},
              {"kind":"item","path":"orb.yml","content":{"id":"40a00003","saleValue":80,"orb":{"effect":{"type":"ENHANCE","targetSlots":["WEAPON"],"rank":0}}}},
              {"kind":"item","path":"weapon.yml","content":{"id":"20a00073","equipment":{"slot":"WEAPON","enhance":{"maxLevel":2,"levels":[{"level":1,"successRate":1,"failAction":"NONE"},{"level":2,"successRate":0.75,"failAction":"NONE"}]}}}},
              {"kind":"loot_table","path":"table.yml","content":{"id":"normal_enemy_common_table_tier_1","rolls":1,"pools":[{"ref":"loot_pool:orbs"}]}},
              {"kind":"loot_pool","path":"pool.yml","content":{"id":"orbs","pick":1,"contents":[
                {"itemId":{"ref":"item:40a00003"},"rate":4,"amount":1},
                {"itemId":{"ref":"item:40a00001"},"rate":6,"amount":1},
                {"itemId":{"ref":"item:40a00002"},"rate":2,"amount":1}]}}]}
            """);
        var request = Parse("""{"mode":"strict","enemy":{"mobId":"midgard_grassboar","level":1},"economy":{"targetItemId":"40a00003","quantity":1,"enhanceItemId":"20a00073","fromEnhanceLevel":0,"targetEnhanceLevel":2,"orbItemId":"40a00003"}}""");
        var result = BalanceEconomy.Estimate(catalog, request, Combat(), CancellationToken.None);
        Assert.Equal("unreachable", result["status"]?.ToString());
        Assert.Equal(0.038416, (double)result["collection"]!["expectedItemsPerKill"]!, 8);
        Assert.Equal(18, (int)result["collection"]!["medianKills"]!);
        Assert.Equal(59, (int)result["collection"]!["p90Kills"]!);
        Assert.Equal(1 + 1 / 0.75, (double)result["enhancement"]!["expectedAttempts"]!, 8);
        Assert.Equal(0, (double)result["enhancement"]!["goldPerAttempt"]!, 8);
        Assert.Equal(0, (double)result["enhancement"]!["orbStockCompletionProbability"]!, 8);
        request["economy"]!["quantity"] = 3;
        var enoughStock = BalanceEconomy.Estimate(catalog, request, Combat(), CancellationToken.None);
        Assert.Equal("completed", enoughStock["status"]?.ToString());
        Assert.Equal(0.9375, (double)enoughStock["enhancement"]!["orbStockCompletionProbability"]!, 8);
        Assert.Equal("partial", enoughStock["gold"]!["completeness"]?.ToString());
    }

    [Fact]
    public void GrowthNeedsWholeWinningRewardsAndChecksClassPath()
    {
        var catalog = Parse("""
            {"masters":[
              {"kind":"mob","path":"mob.yml","content":{"id":"boar","drops":{"exp":16}}},
              {"kind":"class","path":"class.yml","content":{"id":"paladin","maxLevel":100,"expRate":100,"unlockLevel":15,"unlockClassLevel":[{"class":"swordsman","level":50}]}}]}
            """);
        var request = Parse("""{"mode":"strict","enemy":{"mobId":"boar","level":1},"builds":[{"classId":"swordsman","playerLevel":10,"classLevel":1}],"economy":{"classId":"paladin","classLevel":1,"targetClassLevel":2,"classProgress":{"swordsman":40}}}""");
        var result = BalanceEconomy.Estimate(catalog, request, Combat(0.5), CancellationToken.None);
        Assert.Equal("completed", result["status"]?.ToString());
        Assert.Equal(12, (double)result["growth"]!["expectedKillsToClassLevel"]!, 8);
        Assert.False((bool)result["growth"]!["classPath"]!["canSelectNow"]!);
        Assert.Contains(result["diagnostics"]!.AsArray(), issue => issue?["code"]?.ToString() == "CLASS_PREREQUISITES_MISSING");
    }
}
