using System.Text.Json.Nodes;
using SkillTreeEditor.Server.Services;

namespace SkillTreeEditor.Server.Tests;

public sealed class BalanceStaticGeometryTests
{
    [Fact]
    public void ManaBurstAndCrescentCannotHitBehindThePlayer()
    {
        var c = Context(); c.EnemyPosition = new(0, 0, -3);
        foreach (var id in new[] { "adventurer_mana_burst", "swordmaster_crescent_duet", "swordmaster_trinity_edge" })
            Assert.False(Matches(c, Master(id), new("damage", Tick: 1), castTarget: c.EnemyPosition));
    }

    [Fact]
    public void FlameRushRetainsItsCastTimeTargetsForTheDelayedSecondHit()
    {
        var c = Context(); var definition = Master("swordsman_flame_rush");
        var selected = c.EnemyPosition;
        c.PlayerPosition = new(100, 0, 0); c.EnemyPosition = new(0, 0, -20); c.Facing = new(1, 0, 0);
        Assert.True(Matches(c, definition, new("damage", Tick: 4), castTarget: selected));
        Assert.False(Matches(c, definition, new("damage", Tick: 4), castTarget: new(0, 0, -3)));
    }

    [Fact]
    public void CrescentRequeriesCurrentEyeAndFacingForEachScheduledStrike()
    {
        var c = Context(); var definition = Master("swordmaster_crescent_duet");
        var target = c.EnemyPosition;
        c.PlayerPosition = new(10, 0, 0); c.Facing = new(1, 0, 0); c.EnemyPosition = new(13, 0, 0);
        Assert.True(Matches(c, definition, new("damage", Tick: 5), castTarget: target));
        c.Facing = new(-1, 0, 0);
        Assert.False(Matches(c, definition, new("damage", Tick: 5), castTarget: target));
    }

    [Fact]
    public void FixedLotusAndFollowingTempestUseDifferentSphereOrigins()
    {
        var c = Context(); c.PlayerPosition = new(100, 0, 0);
        Assert.True(Matches(c, Master("swordmaster_flame_lotus"), new("damage", Tick: 5)));
        Assert.False(Matches(c, Master("swordmaster_blade_tempest"), new("damage", Tick: 5)));
        c.EnemyPosition = new(100, 0, 2);
        Assert.False(Matches(c, Master("swordmaster_flame_lotus"), new("damage", Tick: 9)));
        Assert.True(Matches(c, Master("swordmaster_blade_tempest"), new("damage", Tick: 9)));
    }

    [Fact]
    public void ConfirmedProjectileCollisionSurvivesCasterMovementAndTurning()
    {
        var c = Context(); c.PlayerPosition = new(100, 0, 100); c.Facing = new(0, 0, -1);
        Assert.True(Matches(c, Master("mage_fireball"), new("damage", FlightSpeed: 0, Range: 16, HitRadius: .45)));
        Assert.True(Matches(c, Master("wizard_prismatic_dart"), new("damage", FlightSpeed: 0, Range: 16, HitRadius: .3)));
    }

    [Fact]
    public void DelayedWizardLineUsesItsCallbackTimeAim()
    {
        var c = Context(); c.EnemyPosition = new(3, 0, 0); c.Facing = new(1, 0, 0);
        Assert.True(Matches(c, Master("wizard_cinder_lance"), new("damage", Tick: 1)));
        c.Facing = new(0, 0, 1);
        Assert.False(Matches(c, Master("wizard_cinder_lance"), new("damage", Tick: 1)));
    }

    [Fact]
    public void HeartseekerFailsBeforeCostsWhenItsLineHasNoTarget()
    {
        var c = Context(); var definition = Master("swordmaster_heartseeker");
        Assert.True(BalanceStaticGeometry.CanCast(c, definition));
        c.EnemyPosition = new(0, 0, -3);
        Assert.False(BalanceStaticGeometry.CanCast(c, definition));
    }

    [Fact]
    public void SmashKeepsTheSelectedPrimaryAfterItLeavesTheOriginalCylinder()
    {
        var c = Context(); c.EnemyPosition = new(0, 0, 100);
        Assert.True(Matches(c, Master("adventurer_smash"), new("damage", Tick: 8), castTarget: new(0, 0, 3)));
        Assert.False(Matches(c, Master("adventurer_smash"), new("damage", Tick: 8), castTarget: new(0, 0, 100)));
    }

    [Fact]
    public void CriticalBurstIsCenteredOnTheCastTimeTargetRatherThanTheCaster()
    {
        var c = Context(); c.PlayerPosition = new(100, 0, 0);
        Assert.True(Matches(c, Master("swordmaster_star_cleave"), new("damage", Tick: 1), castTarget: new(0, 0, 3)));
        c.EnemyPosition = new(100, 0, 3);
        Assert.False(Matches(c, Master("swordmaster_star_cleave"), new("damage", Tick: 1), castTarget: new(0, 0, 3)));
    }

    [Fact]
    public void UnknownGeometryAndTheUnexpandedAstralSweepAreExplicitlyStaticOnly()
    {
        foreach (var id in new[] { "future_cone", "adventurer_astral_edge" })
        {
            var definition = new JsonObject { ["implementationId"] = id };
            var profile = new BalanceSkillProfile(id, [new("damage", Range: 5)], [], [], [], []);
            Assert.Contains("static_only", BalanceStaticGeometry.Annotate(definition, profile).Requirements!);
        }
    }

    [Fact]
    public void ExistingStaticProfilesHaveAnExplicitShapeDescriptor()
    {
        var codec = new MasterDataCodec(); var checkedCount = 0;
        foreach (var path in Directory.EnumerateFiles(Path.Combine(Root(), "40_filebase", "30.features.skill"), "*.yml"))
        {
            var definition = (JsonObject)codec.Parse(path, File.ReadAllText(path)).Content!;
            var profile = BalanceSkillMeleeProfiles.Resolve(definition) ?? BalanceSkillRangedProfiles.Resolve(definition) ?? BalanceSkillMagicProfiles.Resolve(definition);
            if (profile?.Supported != true) continue;
            var annotated = BalanceStaticGeometry.Annotate(definition, profile);
            if (profile.ImplementationId != "adventurer_astral_edge") Assert.DoesNotContain("static_only", annotated.Requirements ?? []);
            checkedCount++;
        }
        Assert.True(checkedCount >= 40);
    }

    private static bool Matches(BalanceRuntimeContext c, JsonObject definition, BalanceSkillEffect effect, BalanceVector? castTarget = null)
        => BalanceStaticGeometry.Matches(c, definition, effect, new(0, 0, 0), new(0, 1.62, 0), new(0, 0, 1), castTarget ?? new(0, 0, 3));

    private static JsonObject Master(string id)
    {
        var path = Path.Combine(Root(), "40_filebase", "30.features.skill", "v1." + id + ".yml");
        return (JsonObject)new MasterDataCodec().Parse(path, File.ReadAllText(path)).Content!;
    }
    private static string Root()
    {
        for (var d = new DirectoryInfo(AppContext.BaseDirectory); d is not null; d = d.Parent)
            if (Directory.Exists(Path.Combine(d.FullName, "40_filebase"))) return d.FullName;
        throw new DirectoryNotFoundException();
    }
    private static BalanceRuntimeContext Context() => new()
    {
        Catalog = new(), Build = new(), Request = new(), Random = new(1), Definitions = new Dictionary<string, JsonObject>(), Passives = new Dictionary<string, JsonObject>(),
        PlayerPosition = new(0, 0, 0), EnemyPosition = new(0, 0, 3), Facing = new(0, 0, 1), FloorY = 0,
        ReadResource = _ => 100, WriteResource = (_, _) => { }, ReadStats = _ => new Dictionary<string, double>(),
        Queue = (_, _, _, _) => { }, Damage = (cast, type, components, source, shield, secondary, victim) => new(cast, type, source, components, 0, 0, 0, false, false, false),
        Healing = (_, _) => 0, Condition = (_, _, _, _, _) => false, StatEffect = (_, _, _, _, _, _) => { }, MultiplierEffect = (_, _, _, _) => { },
        Buff = (_, _) => false, Projectile = (_, _) => { }, SetSkillCooldown = (_, _) => { }, Trace = (_, _, _, _) => { }, Dispatch = _ => { },
        MovePlayer = (from, direction, distance) => from + direction * distance, Velocity = (_, _) => true, CancelScope = _ => { }
    };
}
