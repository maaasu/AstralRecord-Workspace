using System.Text.Json.Nodes;
using SkillTreeEditor.Server.Services;

namespace SkillTreeEditor.Server.Tests;

public sealed class BalanceMeleeRuntimeTests
{
    [Fact]
    public void GuardGainMatchesProductionRuntimeGoldenBoundaries()
    {
        var root = RepoRoot();
        var fixture = JsonNode.Parse(File.ReadAllText(Path.Combine(root, "60_tool", "skilltree-editor", "tests", "plugin-parity", "melee-runtime-golden.json")))!;
        foreach (var source in fixture["sources"]!.AsArray().OfType<JsonObject>())
        {
            var bytes = File.ReadAllBytes(Path.Combine(root, source["path"]!.ToString()));
            Assert.Equal(source["sha256"]!.ToString(), Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(bytes)).ToLowerInvariant());
        }
        foreach (var value in fixture["cases"]!.AsArray().OfType<JsonObject>().Where(value => value["id"]!.ToString().StartsWith("guard_gain_", StringComparison.Ordinal)))
            Assert.Equal(value["gain"]!.GetValue<double>(), BalanceMeleeRuntime.GuardGain(value["raw"]!.GetValue<double>(), value["health"]!.GetValue<double>(), value["maximumHealth"]!.GetValue<double>()), 12);
    }

    [Fact]
    public void EveryOwnedRealMasterPassesExecutorParameterValidation()
    {
        var root = RepoRoot();
        var count = 0;
        foreach (var path in Directory.EnumerateFiles(Path.Combine(root, "40_filebase", "30.features.skill"), "*.yml"))
        {
            var parsed = new MasterDataCodec().Parse(path, File.ReadAllText(path));
            if (parsed.Content is not JsonObject definition || BalanceMeleeRuntime.Resolve(definition) is not { } profile) continue;
            count++;
            Assert.True(profile.Supported, $"{definition["id"]}: {string.Join(" / ", profile.Reasons)}");
            Assert.True(profile.Runtime);
            Assert.Equal(definition["passive"] is not null, profile.Passive);
        }
        Assert.Equal(32, count);
    }

    [Fact]
    public void CounterCancelsEvenZeroDamageAddsSpiritAndRunsASeparateSweep()
    {
        var f = new Fixture("swordmaster_seijaku_issen");
        f.Context.WeaponTag = "SWORD"; f.Context.Sneaking = true;
        f.Event("normal_attempt");
        Assert.Equal(true, f.Context.State["normal.cancelled"]);
        f.Event("before_incoming");
        Assert.True(f.Context.IncomingCancelled);
        Assert.Equal(1, f.Context.Resource("RIPOSTE"));
        Assert.Single(f.Hits);
        Assert.Equal(4.29, f.Hits[0].Ratio, 10);
        f.Advance(3);
        Assert.Equal(2, f.Hits.Count);
        Assert.Equal(4.29, f.Hits[1].Ratio, 10);
        Assert.Equal(3, f.Hits[1].Tick);
    }

    [Fact]
    public void SpiritIsBoundAndConsumedAtCastEvenWhenAttackHasNoTarget()
    {
        var f = new Fixture("swordmaster_seijaku_issen");
        f.Cast("swordmaster_serene_mend");
        Assert.Equal(1, f.Context.Resource("RIPOSTE"));
        f.Context.EnemyPosition = new(0, 0, 100);
        f.Cast("swordmaster_riposte");
        Assert.Equal(0, f.Context.Resource("RIPOSTE")); Assert.Empty(f.Hits);
        var unbound = new Fixture();
        unbound.Cast("swordmaster_serene_mend");
        Assert.Equal(0, unbound.Context.Resource("RIPOSTE"));
    }

    [Fact]
    public void DodgeWindowNegatesAllDirectHitsButRecoversEnergyOnlyOnce()
    {
        var f = new Fixture("administrator_just_dodge");
        f.Resources["ENG"] = 0;
        f.Event("dodge"); f.Context.IncomingDamage = 20;
        f.Event("before_incoming"); var recovered = f.Context.Eng;
        Assert.True(f.Context.IncomingCancelled); Assert.True(recovered > 0);
        f.Context.IncomingCancelled = false;
        f.Event("before_incoming");
        Assert.True(f.Context.IncomingCancelled); Assert.Equal(recovered, f.Context.Eng);
        f.Advance(f.Param("administrator_just_dodge", "invulnerabilityTicks"));
        f.Context.IncomingCancelled = false; f.Event("before_incoming");
        Assert.False(f.Context.IncomingCancelled);
    }

    [Fact]
    public void GuardUsesActualHealthDamageAndDecaysByTheLockedInitialAmount()
    {
        var f = new Fixture("paladin_guard_convert");
        f.Event("after_incoming", new(f.Snapshot("paladin_guard_convert"), "MELEE", "NORMAL_ATTACK", [], 100, 20, 0, false, false, false));
        Assert.Equal(10, f.Context.Resource("GUARD")); // 8 mitigation + 2 health ratio, capped at Lv1 maximum.
        f.Advance(199); Assert.Equal(10, f.Context.Resource("GUARD"));
        f.Advance(200); Assert.Equal(9, f.Context.Resource("GUARD"));
        f.Advance(220); Assert.Equal(8, f.Context.Resource("GUARD"));
        f.Context.Spend("GUARD", 6);
        f.Advance(240); Assert.Equal(1, f.Context.Resource("GUARD"));
    }

    [Fact]
    public void DualCrestWaitsForNextTickAndInsufficientEnergyKeepsTheCrests()
    {
        var f = new Fixture("melee_dual_crest");
        f.DirectHit(); f.Context.Tick = 10; f.DirectHit(); f.Context.Tick = 20; f.DirectHit();
        Assert.Empty(f.Hits);
        f.Resources["ENG"] = 0; f.Advance(21); Assert.Empty(f.Hits);
        f.Resources["ENG"] = 10; f.Advance(30); f.DirectHit(); f.Advance(31);
        Assert.Single(f.Hits); Assert.True(f.Hits[0].Secondary); Assert.Equal(8, f.Context.Eng);
    }

    [Fact]
    public void HolyControlWithoutPillarConsumesHpAndReportsFailureWithCommonCost()
    {
        var f = new Fixture(); f.Resources["HP"] = 500;
        var result = f.Cast("paladin_holy_control");
        Assert.False(result.Success); Assert.True(result.ConsumeCommon); Assert.Equal(300, f.Context.Hp);
        f.Resources["HP"] = 200;
        result = f.Cast("paladin_holy_control");
        Assert.False(result.Success); Assert.False(result.ConsumeCommon); Assert.Equal(200, f.Context.Hp);
    }

    [Fact]
    public void HolyFieldBonusIsEvaluatedAtLanceCastAndLineOriginIsLocked()
    {
        var f = new Fixture();
        f.Cast("paladin_holy_field"); f.Cast("paladin_blessed_lance");
        f.Context.PlayerPosition = new(100, 0, 0); f.Context.Facing = new(1, 0, 0);
        f.Advance(1);
        Assert.Single(f.Hits);
        Assert.Equal(1.8 * 1.35, f.Hits[0].Ratio, 10);
    }

    [Fact]
    public void ShieldFallbackCreatesARealPillarInsteadOfAPartyShield()
    {
        var f = new Fixture(); f.Resources["SHIELD"] = 80;
        Assert.True(f.Cast("paladin_shield").Success);
        Assert.Equal(40, f.Context.Shield);
        Assert.Single(f.Hits); Assert.Equal(.5, f.Hits[0].Ratio);
        f.Advance(19); Assert.Single(f.Hits);
        f.Advance(20); Assert.Equal(2, f.Hits.Count);
    }

    [Fact]
    public void GuardianChainFirstPullIsAfterItsIntervalAndUsesDistanceInterpolation()
    {
        var f = new Fixture(); f.Context.EnemyPosition = new(0, 0, 7.5);
        f.Cast("paladin_guardian_chain"); Assert.Empty(f.Velocities);
        f.Advance(19); Assert.Empty(f.Velocities);
        f.Advance(20); Assert.Single(f.Velocities);
        Assert.Equal(-.85, f.Velocities[0].Velocity.Z, 10);
        Assert.Equal(.35, f.Velocities[0].Velocity.Y, 10);
    }

    [Fact]
    public void PartyShieldReplacesEachTargetsShieldAndExpiresWithoutAnAttackPillar()
    {
        var f = new Fixture(); f.Resources["SHIELD"] = 80;
        var ally = new BalanceRuntimeAlly { Id = "front", Position = new(0, 0, 2), Hp = 100, Shield = 9 };
        f.Context.Allies.Add(ally);
        Assert.True(f.Cast("paladin_shield").Success);
        Assert.Equal(40, ally.Shield); Assert.False(ally.ShieldEnabled); Assert.Empty(f.Hits);
        f.Advance(399); Assert.Equal(40, ally.Shield);
        f.Advance(400); Assert.Equal(0, ally.Shield);
    }

    [Fact]
    public void GuardianProtectUsesAimedPartyTargetAndRedirectsOnlyFinalHealthDamage()
    {
        var f = new Fixture("paladin_guard_convert");
        var target = new BalanceRuntimeAlly { Id = "front", Position = new(0, 0, 3), Hp = 100 };
        var offAxis = new BalanceRuntimeAlly { Id = "side", Position = new(10, 0, 1), Hp = 100 };
        f.Context.Allies.Add(target); f.Context.Allies.Add(offAxis);
        f.Resources["SHIELD"] = 100;
        Assert.True(f.Cast("paladin_guardian_protect").Success);
        Assert.Equal(100, f.Context.AllyDamageInterceptor!(offAxis, 100));
        Assert.Equal(10, f.Context.AllyDamageInterceptor!(target, 100), 10);
        Assert.Equal(10, f.Context.Hp, 10); Assert.Equal(100, f.Context.Shield);
        Assert.Equal(9, f.Context.Resource("GUARD"), 10);
        f.Advance(200); Assert.Equal(100, f.Context.AllyDamageInterceptor!(target, 100));
    }

    [Fact]
    public void HolyFieldSharesItsFlatBonusThroughTheCommonAllyStatTarget()
    {
        var f = new Fixture();
        f.Context.Allies.Add(new BalanceRuntimeAlly { Id = "near", Position = new(0, 0, 1), Hp = 100 });
        f.Context.Allies.Add(new BalanceRuntimeAlly { Id = "far", Position = new(0, 0, 20), Hp = 100 });
        f.Cast("paladin_holy_field");
        Assert.Contains(f.Stats, value => value.Target == "ally:near" && value.Status == "SHIELD_BREAK" && value.Value == -10);
        Assert.DoesNotContain(f.Stats, value => value.Target == "ally:far");
    }

    [Theory]
    [InlineData("swordmaster_seijaku_issen", "counterTicks", 21)]
    [InlineData("swordmaster_seijaku_issen", "maxRiposteStacks", 11)]
    [InlineData("paladin_blessed_lance", "fieldMultiplier", 6)]
    [InlineData("paladin_guardian_bulwark", "incomingMultiplier", 1.1)]
    [InlineData("melee_dual_crest", "hitIntervalTicks", 9)]
    public void InvalidExecutorValuesAreNotSupported(string id, string key, double value)
    {
        var definition = Master(id); definition["params"]![key] = value;
        Assert.False(BalanceMeleeRuntime.Resolve(definition)!.Supported);
    }

    private static JsonObject Master(string id)
    {
        var path = Path.Combine(RepoRoot(), "40_filebase", "30.features.skill", "v1." + id + ".yml");
        return (JsonObject)new MasterDataCodec().Parse(path, File.ReadAllText(path)).Content!;
    }

    private static string RepoRoot()
    {
        for (var d = new DirectoryInfo(AppContext.BaseDirectory); d is not null; d = d.Parent)
            if (Directory.Exists(Path.Combine(d.FullName, "40_filebase"))) return d.FullName;
        throw new DirectoryNotFoundException("Repository root not found.");
    }

    private sealed class Fixture
    {
        internal readonly BalanceMeleeRuntime Runtime = new();
        internal readonly Dictionary<string, double> Resources = new() { ["HP"] = 100, ["MP"] = 100, ["ENG"] = 100, ["SHIELD"] = 0, ["ENEMY_HP"] = 10000, ["ENEMY_SHIELD"] = 0 };
        internal readonly List<(int Tick, double Ratio, bool Secondary)> Hits = [];
        internal readonly List<(string Target, BalanceVector Velocity)> Velocities = [];
        internal readonly List<(string Target, string Status, double Value)> Stats = [];
        internal readonly List<(int Tick, Action Action)> Queue = [];
        internal BalanceRuntimeContext Context { get; }
        private readonly Dictionary<string, JsonObject> definitions = new(StringComparer.Ordinal);
        private readonly Dictionary<string, double> stats = new() { ["MAX_HEALTH"] = 100, ["MAX_MANA"] = 100, ["MAX_ENERGY"] = 100, ["MAX_SHIELD"] = 100, ["ATTACK_SPEED"] = 100 };
        internal Fixture(params string[] passives)
        {
            var activePassives = passives.ToDictionary(id => id, Master);
            foreach (var pair in activePassives) definitions[pair.Key] = pair.Value;
            BalanceRuntimeContext? context = null;
            Context = context = new()
            {
                Catalog = new(), Build = new(), Request = new(), Random = new(1), Definitions = definitions, Passives = activePassives,
                PlayerPosition = new(0, 0, 0), EnemyPosition = new(0, 0, 3), FloorY = 0,
                ReadResource = key => Resources.GetValueOrDefault(key), WriteResource = (key, value) => Resources[key] = Math.Max(0, value),
                ReadStats = enemy => enemy ? new Dictionary<string, double>() : stats,
                Queue = (cast, delay, action, scope) => Queue.Add((context!.Tick + delay, action)),
                Damage = (cast, type, components, source, shieldMultiplier, secondary, victimOverride) =>
                {
                    Hits.Add((context!.Tick, components.Sum(component => component.Ratio), secondary));
                    return new(cast, type, source, components, 1, 1, 0, false, false, false, secondary);
                },
                Healing = (cast, amount) => { Resources["HP"] += amount; return amount; },
                Condition = (cast, type, chance, ticks, power) => true,
                StatEffect = (key, status, value, mode, duration, target) => Stats.Add((target, status, value)), MultiplierEffect = (key, kind, value, duration) => { },
                Buff = (cast, id) => true, Projectile = (cast, projectile) => { }, SetSkillCooldown = (id, tick) => { },
                Trace = (cast, kind, value, detail) => { }, Dispatch = value => Runtime.OnEvent(context!, value),
                MovePlayer = (start, direction, distance) => context!.PlayerPosition = start + direction * distance,
                Velocity = (target, velocity) => { Velocities.Add((target, velocity)); return true; }, CancelScope = key => { }
            };
            Runtime.OnEvent(Context, new("bind"));
        }
        internal int Param(string id, string key) => BalanceSkillParameters.Integer(BalanceSkillParameters.Params(Master(id)), key);
        internal BalanceRuntimeCast Snapshot(string id)
        {
            if (!definitions.TryGetValue(id, out var definition)) definitions[id] = definition = Master(id);
            return Context.Snapshot(definition);
        }
        internal BalanceRuntimeCastResult Cast(string id) => Runtime.Cast(Context, Snapshot(id));
        internal void Event(string kind, BalanceRuntimeHit? hit = null) => Runtime.OnEvent(Context, new(kind, Hit: hit));
        internal void DirectHit() => Event("damage_applied", new(Snapshot("melee_dual_crest"), "MELEE", "NORMAL_ATTACK", [], 1, 1, 0, false, false, false));
        internal void Advance(int until)
        {
            for (var tick = Context.Tick + 1; tick <= until; tick++)
            {
                Context.Tick = tick; Event("tick");
                foreach (var action in Queue.Where(item => item.Tick <= tick).ToArray()) { Queue.Remove(action); action.Action(); }
            }
        }
    }
}
