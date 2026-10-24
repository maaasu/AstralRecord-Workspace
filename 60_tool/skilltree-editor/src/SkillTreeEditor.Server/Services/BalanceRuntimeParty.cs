namespace SkillTreeEditor.Server.Services;

/// <summary>Maintains explicitly supplied party members' timed stats, shield grants and guardian links.</summary>
internal sealed class BalanceRuntimeParty
{
    private sealed record StatGrant(BalanceRuntimeAlly Ally, string Status, double Value, int Until);
    private sealed record Protection(int Until, double Ratio, Action<double> OnRedirected);
    private readonly Dictionary<string, StatGrant> stats = new(StringComparer.Ordinal);
    private readonly Dictionary<string, (BalanceRuntimeAlly Ally, int Until)> shields = new(StringComparer.Ordinal);
    private readonly Dictionary<string, Protection> protections = new(StringComparer.Ordinal);
    private bool installed;

    internal static BalanceRuntimeParty For(BalanceRuntimeContext c) => c.Memory("melee.party", () => new BalanceRuntimeParty());

    internal void Tick(BalanceRuntimeContext c)
    {
        foreach (var (key, value) in stats.Where(pair => pair.Value.Until <= c.Tick || pair.Value.Ally.Dead).ToArray())
        {
            c.AddStat(key, value.Status, 0, "FLAT", 0, "ally:" + value.Ally.Id);
            stats.Remove(key);
        }
        foreach (var (key, value) in shields.Where(pair => pair.Value.Until <= c.Tick).ToArray())
        { value.Ally.Shield = 0; shields.Remove(key); }
        foreach (var key in protections.Where(pair => pair.Value.Until <= c.Tick).Select(pair => pair.Key).ToArray()) protections.Remove(key);
    }

    internal void AddStat(BalanceRuntimeContext c, BalanceRuntimeAlly ally, string key, string status, double value, int duration)
    {
        var identity = ally.Id + ":" + key;
        c.AddStat(identity, status, value, "FLAT", duration, "ally:" + ally.Id);
        stats[identity] = new(ally, status, value, c.Tick + Math.Max(1, duration));
    }

    internal void ClearStats(BalanceRuntimeContext c, string key)
    {
        foreach (var (identity, value) in stats.Where(pair => pair.Key.EndsWith(":" + key, StringComparison.Ordinal)).ToArray())
        {
            c.AddStat(identity, value.Status, 0, "FLAT", 0, "ally:" + value.Ally.Id);
            stats.Remove(identity);
        }
    }

    internal void GrantShield(BalanceRuntimeContext c, BalanceRuntimeAlly ally, double amount, int duration)
    {
        if (amount <= 0 || duration < 1) return;
        ally.Shield = amount;
        shields[ally.Id] = (ally, c.Tick + duration);
    }

    internal bool Protect(BalanceRuntimeContext c, double range, double aimRadius, int duration, double redirectRatio, Action<double> onRedirected)
    {
        var direction = c.Facing.Normalized;
        var target = c.Allies.Where(ally => !ally.Dead).Select(ally =>
        {
            var offset = ally.Position + new BalanceVector(0, 1.62, 0) - c.Eye;
            var along = BalanceVector.Dot(offset, direction);
            return (Ally: ally, Along: along, Perpendicular: (offset - direction * along).LengthSquared);
        }).Where(value => value.Along >= 0 && value.Along <= range && value.Perpendicular <= aimRadius * aimRadius)
            .OrderBy(value => value.Along).Select(value => value.Ally).FirstOrDefault();
        if (target is null) return false;
        protections[target.Id] = new(c.Tick + Math.Max(1, duration), Math.Clamp(redirectRatio, 0, 1), onRedirected);
        if (!installed)
        {
            var previous = c.AllyDamageInterceptor;
            c.AllyDamageInterceptor = (ally, damage) =>
            {
                damage = previous?.Invoke(ally, damage) ?? damage;
                if (damage <= 0 || c.Hp <= 0 || ally.Dead || !protections.TryGetValue(ally.Id, out var protection) || c.Tick >= protection.Until) return damage;
                var redirected = damage * protection.Ratio;
                if (c.Tick >= c.InvulnerableUntil)
                {
                    c.AddResource("HP", -redirected);
                    protection.OnRedirected(redirected);
                }
                return damage - redirected;
            };
            installed = true;
        }
        return true;
    }

    internal static bool Within(BalanceRuntimeAlly ally, BalanceVector center, double radius, double height = double.PositiveInfinity)
        => !ally.Dead && (ally.Position - center).Horizontal.LengthSquared <= radius * radius && Math.Abs(ally.Position.Y - center.Y) <= height;
}
