using System.Text.Json.Nodes;

namespace SkillTreeEditor.Server.Services;

/// <summary>Plugin target searches for the legacy effect plans when used in an explicit-position trial.</summary>
internal static class BalanceStaticGeometry
{
    private enum Shape { Always, Collision, CastConeTargets, CurrentCone, CurrentLine, CastLine, CurrentSphere, CastSphere, StarCleave, CastPrimary, GroundSphere, GroundCylinder, GroundPlayers, HolyPillar, StaticOnly }
    private sealed record Descriptor(Shape Shape, string Range = "range", string Angle = "angle", double HeightOffset = 0);
    private static readonly IReadOnlyDictionary<string, Descriptor> Descriptors = BuildDescriptors();
    private const string Targeting = "10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/skill/active/service/SkillTargetingService.java";

    internal static BalanceSkillProfile Annotate(JsonObject definition, BalanceSkillProfile profile)
    {
        if (profile.Runtime || !profile.Supported) return profile;
        var id = BalanceCombatData.Text(definition["implementationId"]);
        if (!Descriptors.TryGetValue(id, out var descriptor) || descriptor.Shape == Shape.StaticOnly)
            return profile with
            {
                Requirements = (profile.Requirements ?? []).Append("static_only").Distinct(StringComparer.Ordinal).ToArray(),
                Assumptions = profile.Assumptions.Append(id == "adventurer_astral_edge"
                    ? "この旧planは6frameの薙ぎ払いと実命中後4tickの追撃時刻を表現しないため、位置が変わる戦闘では使用できません。"
                    : "この旧planの動的な対象検索は未照合のため、位置が変わる戦闘では使用できません。").ToArray()
            };
        return profile with { Sources = profile.Sources.Append(Targeting).Distinct(StringComparer.Ordinal).ToArray() };
    }

    /// <summary>Checks executor failures that occur before common costs, rather than treating them as paid misses.</summary>
    internal static bool CanCast(BalanceRuntimeContext c, JsonObject definition)
    {
        var id = BalanceCombatData.Text(definition["implementationId"]);
        if (id == "swordmaster_heartseeker")
            return c.EnemyAlive && c.InLine(c.Eye, ClippedEnd(c, c.Eye, c.Facing, N(definition, "range")), N(definition, "hitRadius"));
        if (id is "archmage_astral_spear" or "archmage_starfall" or "archmage_orbital_volley"
            or "archmage_aegis_circle" or "archmage_meteor_circle" or "archmage_renewal_circle" or "archmage_vigor_circle")
        {
            var ground = Ground(c, c.Eye, c.Facing, N(definition, "range"));
            return Math.Abs(ground.Y - (c.FloorY + .05)) <= 1e-6 && (ground - c.Eye).LengthSquared <= N(definition, "range") * N(definition, "range");
        }
        return true;
    }

    /// <summary>
    /// Matches an effect's actual target shape. castTarget is the enemy's foot position at executor invocation.
    /// Collision descriptors run only after the shared projectile adapter has confirmed its collision.
    /// </summary>
    internal static bool Matches(BalanceRuntimeContext c, JsonObject definition, BalanceSkillEffect effect,
        BalanceVector castPlayer, BalanceVector castEye, BalanceVector castFacing, BalanceVector castTarget)
    {
        var id = BalanceCombatData.Text(definition["implementationId"]);
        if (!Descriptors.TryGetValue(id, out var descriptor) || descriptor.Shape == Shape.StaticOnly)
            return false; // Annotate requires static_only; approximate mode must not invent a target shape either.
        if (effect.Kind is "circle" or "stat" or "incoming" or "outgoing") return true;
        // Per-hit recovery and post-hit states use the already resolved hit, rather than another target search.
        if (effect.Kind == "resource" && effect.Trigger is "landed" or "critical" or "killed") return true;
        if (effect.Kind == "heal" && effect.Trigger == "killed") return true;
        var radius = N(definition, descriptor.Range, effect.Range);
        var angle = N(definition, descriptor.Angle);
        return descriptor.Shape switch
        {
            Shape.Always or Shape.Collision => true,
            // FlameRush captures the entity list at cast and reuses it for its delayed second strike.
            Shape.CastConeTargets => InCone(castEye, castFacing, castTarget + new BalanceVector(0, c.EnemyHeight / 2, 0), radius, angle),
            // CrescentDuet calls cone(context) again inside each sequence callback.
            Shape.CurrentCone => c.InCone(radius, angle),
            Shape.CurrentLine => c.InLine(c.Eye, ClippedEnd(c, c.Eye, c.Facing, radius), N(definition, "hitRadius", effect.HitRadius)),
            Shape.CastLine => c.InLine(castEye, ClippedEnd(c, castEye, castFacing, radius), N(definition, "hitRadius", effect.HitRadius)),
            Shape.CurrentSphere => c.InSphere(c.PlayerPosition + new BalanceVector(0, descriptor.HeightOffset, 0), radius),
            Shape.CastSphere => c.InSphere(castPlayer + new BalanceVector(0, descriptor.HeightOffset, 0), radius),
            Shape.StarCleave => effect.Tick == 0 ? c.InCone(N(definition, "range"), N(definition, "angle"))
                : c.InSphere(castTarget + new BalanceVector(0, .8, 0), N(definition, "burstRadius")),
            // Smash fixes one primary entity selected by the cast-time cylinder. It does not recheck distance at impact.
            Shape.CastPrimary => InRadiusAt(c, castEye, N(definition, "primaryRadius"), N(definition, "primaryRadius"), castTarget),
            Shape.GroundSphere => c.InSphere(Ground(c, castEye, castFacing, N(definition, "range")), N(definition, "radius")),
            Shape.GroundCylinder => c.InRadius(Ground(c, castEye, castFacing, N(definition, "range")) + new BalanceVector(0, .06, 0), N(definition, "radius"), 3),
            Shape.GroundPlayers => WithinPlayer(c.PlayerPosition, Ground(c, castEye, castFacing, N(definition, "range")) + new BalanceVector(0, .06, 0), N(definition, "radius"), 2),
            Shape.HolyPillar => c.InSphere(new(castPlayer.X, c.FloorY + .05, castPlayer.Z), N(definition, "radius")),
            _ => false
        };
    }

    private static double N(JsonObject definition, string key, double fallback = 0)
        => BalanceSkillParameters.Number(BalanceSkillParameters.Params(definition), key, fallback);

    private static bool InCone(BalanceVector eye, BalanceVector direction, BalanceVector target, double range, double angle)
    {
        var delta = target - eye;
        return delta.LengthSquared > 1e-8 && delta.LengthSquared <= range * range
            && BalanceVector.Dot(delta.Normalized, direction.Normalized) >= Math.Cos(angle / 2 * Math.PI / 180);
    }

    private static bool InRadiusAt(BalanceRuntimeContext c, BalanceVector center, double radius, double height, BalanceVector target)
    {
        var dx = Math.Max(0, Math.Abs(center.X - target.X) - c.EnemyHalfWidth);
        var dz = Math.Max(0, Math.Abs(center.Z - target.Z) - c.EnemyHalfWidth);
        return dx * dx + dz * dz <= radius * radius && target.Y + c.EnemyHeight >= center.Y - height && target.Y <= center.Y + height;
    }

    private static bool WithinPlayer(BalanceVector player, BalanceVector center, double radius, double height)
        => (player - center).Horizontal.LengthSquared <= radius * radius && Math.Abs(player.Y - center.Y) <= height;

    private static BalanceVector ClippedEnd(BalanceRuntimeContext c, BalanceVector eye, BalanceVector facing, double range)
    {
        var direction = facing.Normalized;
        var floorDistance = direction.Y < -1e-8 ? (c.FloorY - eye.Y) / direction.Y : double.PositiveInfinity;
        return eye + direction * (floorDistance >= 0 && floorDistance <= range ? floorDistance : range);
    }

    private static BalanceVector Ground(BalanceRuntimeContext c, BalanceVector eye, BalanceVector facing, double range)
    {
        var probe = ClippedEnd(c, eye, facing, range);
        return c.FloorY >= Math.Floor(probe.Y) - 32 && c.FloorY <= Math.Floor(probe.Y) + 3
            ? new(probe.X, c.FloorY + .05, probe.Z) : probe;
    }

    private static IReadOnlyDictionary<string, Descriptor> BuildDescriptors()
    {
        var result = new Dictionary<string, Descriptor>(StringComparer.Ordinal);
        void Add(Shape shape, params string[] ids) { foreach (var id in ids) result[id] = new(shape); }
        Add(Shape.Always, "hunter_build_up", "swordmaster_overdrive", "mage_heal_aura", "wizard_self_heal", "wizard_mana_veil", "paladin_holy_reprieve");
        Add(Shape.Collision, "adventurer_quick_shot", "sharpshooter_fire_arrow", "sharpshooter_ice_arrow", "sharpshooter_piercing_line", "sharpshooter_ricochet_arrow",
            "sharpshooter_thermal_break", "sharpshooter_phantom_shot", "sharpshooter_spreading_ambition", "mage_fireball", "mage_frost_ball", "wizard_prismatic_dart", "wizard_elemental_ball", "wizard_mana_siphon");
        Add(Shape.StaticOnly, "adventurer_astral_edge");
        result["swordsman_flame_rush"] = new(Shape.CastConeTargets, Angle: "targetAngle");
        result["swordsman_shield_drain"] = new(Shape.CurrentCone, Angle: "targetAngle");
        result["paladin_holy_smash"] = new(Shape.CurrentCone, Angle: "targetAngle");
        Add(Shape.CurrentCone, "adventurer_mana_burst", "swordmaster_crescent_duet", "swordmaster_trinity_edge");
        Add(Shape.CurrentLine, "swordmaster_heartseeker", "swordmaster_thunder_line", "wizard_arcane_lance", "wizard_cinder_lance", "wizard_storm_chain");
        Add(Shape.CastLine, "paladin_blessed_lance");
        result["swordmaster_blade_tempest"] = new(Shape.CurrentSphere, "radius", HeightOffset: .8);
        result["swordmaster_frost_bloom"] = new(Shape.CurrentSphere, "radius", HeightOffset: .6);
        result["swordmaster_flame_lotus"] = new(Shape.CastSphere, "radius", HeightOffset: .2);
        Add(Shape.StarCleave, "swordmaster_star_cleave");
        Add(Shape.CastPrimary, "adventurer_smash");
        Add(Shape.GroundSphere, "wizard_arcane_overload", "wizard_triad_convergence");
        Add(Shape.GroundCylinder, "archmage_astral_spear", "archmage_starfall", "archmage_orbital_volley", "archmage_meteor_circle");
        Add(Shape.GroundPlayers, "archmage_aegis_circle", "archmage_renewal_circle", "archmage_vigor_circle");
        Add(Shape.HolyPillar, "paladin_holy_smite");
        return result;
    }
}
