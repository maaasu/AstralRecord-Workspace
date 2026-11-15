namespace SkillTreeEditor.Server.Services;

/// <summary>Deterministic subset of the Plugin combat calculations, with explicit inputs.</summary>
public static class BalanceCombatMath
{
    private static readonly double DefenseExponent = Math.Log(0.5) / Math.Log(0.6);

    public static double HitChance(double accuracy, double evasion)
        => Math.Clamp(accuracy - evasion, 1, 100);

    public static double AttackPower(double attack, double typedAttack, double primary)
        => (attack + typedAttack) * (1 + primary / 100);

    public static double EffectiveDefense(double general, double typed, double generalPenetration,
        double typedPenetration, double multiplier = 1)
        => (Math.Max(0, general) * Math.Max(0, 1 - Math.Max(0, generalPenetration) / 100)
            + Math.Max(0, typed) * Math.Max(0, 1 - Math.Max(0, typedPenetration) / 100)) * Math.Max(0, multiplier);

    public static double DefenseMultiplier(double attackPower, double effectiveDefense)
    {
        if (!double.IsFinite(attackPower) || attackPower <= 0) return 0;
        var ratio = Math.Max(0, effectiveDefense) / attackPower;
        return ratio >= 2.5 ? 0 : Math.Pow(Math.Max(0, 1 - ratio / 2.5), DefenseExponent);
    }

    public static long CooldownTicks(long baseTicks, double reduction)
    {
        if (baseTicks <= 0) return 0;
        if (!double.IsFinite(reduction)) reduction = 0;
        return Math.Max(0, (long)Math.Ceiling(baseTicks * (1 - Math.Clamp(reduction, 0, 50) / 100)));
    }

    public static long AttackIntervalTicks(long baseTicks, double speed)
    {
        if (baseTicks <= 0) return 0;
        if (!double.IsFinite(speed) || speed <= 0) speed = 100;
        return Math.Max(1, (long)Math.Ceiling(baseTicks * 100 / speed));
    }

    public static long CastTimeTicks(long baseTicks, double reduction, double additional = 0)
    {
        var first = double.IsFinite(reduction) ? Math.Clamp(reduction, 0, 50) : 0;
        var second = double.IsFinite(additional) ? Math.Clamp(additional, 0, 50) : 0;
        var multiplier = Math.Max(.5, (1 - first / 100) * (1 - second / 100));
        return Math.Max(0, (long)Math.Ceiling(Math.Max(0, baseTicks) * multiplier));
    }

    public static double ShieldDamage(double finalDamage, double victimMaxHealth, double shieldBreak,
        double shieldBreakMultiplier, double currentShield)
    {
        var basis = Math.Max(1, Math.Floor(Math.Max(0, finalDamage) / Math.Max(1, victimMaxHealth * 0.1)));
        return Math.Min(Math.Max(0, currentShield), (basis + Math.Max(0, shieldBreak)) * Math.Max(0, shieldBreakMultiplier));
    }

    public static double DegradationDamageMultiplier(int stage)
    {
        if (stage <= 0) return 1;
        var reduction = Math.Min(stage, 11) switch { 1 => 10, 2 or 3 => 15, 4 => 30, var s => 40 + (s - 5) * 10 };
        return Math.Max(0, 1 - reduction / 100.0);
    }

    public static double DegradationSpeedMultiplier(int stage)
        => stage <= 3 ? 1 : 1 - Math.Min(0.5, (stage - 3) * 0.1);
}
