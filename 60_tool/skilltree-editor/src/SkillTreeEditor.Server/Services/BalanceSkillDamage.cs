namespace SkillTreeEditor.Server.Services;

internal readonly record struct BalanceSkillHit(double Damage, bool Landed, bool Critical);

internal static class BalanceSkillDamage
{
    // DamageCalculator: one accuracy roll and one critical roll for all elemental components.
    internal static BalanceSkillHit Calculate(IReadOnlyDictionary<string, double> attacker,
        IReadOnlyDictionary<string, double> victim, string type, IReadOnlyList<BalanceSkillComponent> components,
        string source, double attackerMultiplier, bool playerAgainstMob, Random rng)
    {
        var typed = type switch { "RANGED" => "RANGED_ATTACK", "MAGIC" => "MAGIC_ATTACK", _ => "MELEE_ATTACK" };
        var primary = type switch { "RANGED" => "DEXTERITY", "MAGIC" => "INTELLIGENCE", _ => "STRENGTH" };
        var defense = type switch { "RANGED" => "RANGED_DEFENSE", "MAGIC" => "MAGIC_DEFENSE", _ => "MELEE_DEFENSE" };
        var penetration = type switch { "RANGED" => "RANGED_DEFENSE_PENETRATION_RATE", "MAGIC" => "MAGIC_DEFENSE_PENETRATION_RATE", _ => "MELEE_DEFENSE_PENETRATION_RATE" };
        var accuracy = BalanceCombatData.Stat(attacker, "ACCURACY", playerAgainstMob ? 0 : 100);
        if (!playerAgainstMob && accuracy <= 0) accuracy = 100;
        var chance = BalanceCombatMath.HitChance(Math.Max(0, accuracy), Math.Max(0, BalanceCombatData.Stat(victim, "EVASION")));
        if (chance < 100 && rng.NextDouble() * 100 >= chance) return new(0, false, false);
        var attack = Math.Max(0, BalanceCombatMath.AttackPower(BalanceCombatData.Stat(attacker, "ATTACK"),
            BalanceCombatData.Stat(attacker, typed), BalanceCombatData.Stat(attacker, primary)));
        var ratio = components.Where(component => component.Ratio > 0).Sum(component => component.Ratio);
        if (ratio <= 0) return new(0, true, false);
        var increase = source == "SKILL" ? "SKILL_DAMAGE_INCREASE" : "NORMAL_ATTACK_DAMAGE_INCREASE";
        var damage = attack * ratio * Math.Max(0, 1 + BalanceCombatData.Stat(attacker, increase) / 100) * attackerMultiplier;
        var critical = BalanceCombatData.Stat(attacker, "CRITICAL_RATE") > 0
            && rng.NextDouble() * 100 < BalanceCombatData.Stat(attacker, "CRITICAL_RATE");
        if (critical)
        {
            var value = BalanceCombatData.Stat(attacker, "CRITICAL_DAMAGE");
            damage *= (value <= 0 ? 150 : value) / 100;
        }
        var superCritical = playerAgainstMob && BalanceCombatData.Stat(attacker, "SUPER_CRITICAL_RATE") > 0
            && rng.NextDouble() * 100 < BalanceCombatData.Stat(attacker, "SUPER_CRITICAL_RATE");
        if (superCritical)
        {
            var value = BalanceCombatData.Stat(attacker, "SUPER_CRITICAL_DAMAGE");
            damage *= 1 + (value <= 0 ? 30 : value) / 100;
        }
        var effective = BalanceCombatMath.EffectiveDefense(BalanceCombatData.Stat(victim, "DEFENSE"),
            BalanceCombatData.Stat(victim, defense), BalanceCombatData.Stat(attacker, "DEFENSE_PENETRATION_RATE"), BalanceCombatData.Stat(attacker, penetration));
        damage *= BalanceCombatMath.DefenseMultiplier(attack, effective);
        var attributed = 0.0;
        foreach (var component in components.Where(component => component.Ratio > 0))
        {
            var element = component.Element;
            var cap = BalanceCombatData.Stat(victim, element + "_RESISTANCE_CAP", element == "NONE" ? 50 : 75);
            var resistance = Math.Min(BalanceCombatData.Stat(victim, element + "_RESISTANCE"), cap)
                - (element == "NONE" ? 0 : BalanceCombatData.Stat(attacker, element + "_PENETRATION"));
            attributed += damage * component.Ratio / ratio
                * Math.Max(0, 1 + BalanceCombatData.Stat(attacker, element + "_DAMAGE_INCREASE") / 100)
                * Math.Max(0, 1 - resistance / 100);
        }
        return new(Math.Max(0, attributed * Math.Max(0, BalanceCombatData.Stat(attacker, "FINAL_DAMAGE_MULTIPLIER", 100)) / 100), true, critical || superCritical);
    }

    internal static int FlightTicks(double distance, double targetHalfDepth, BalanceSkillEffect effect)
    {
        if (effect.FlightSpeed <= 0) return 0;
        var entry = Math.Max(0, distance - effect.FlightOriginOffset - Math.Max(0, targetHalfDepth) - effect.HitRadius);
        return Math.Max(0, (int)Math.Ceiling(entry / effect.FlightSpeed) - 1);
    }

    internal static bool OutOfRange(double distance, double halfDepth, BalanceSkillEffect effect)
        // Cone/sphere tests use target centre; line/projectile tests use the expanded AABB.
        => effect.Range > 0 && (effect.FlightSpeed > 0 || effect.HitRadius > 0
            ? distance - halfDepth - effect.HitRadius - effect.FlightOriginOffset : distance) > effect.Range;
}
