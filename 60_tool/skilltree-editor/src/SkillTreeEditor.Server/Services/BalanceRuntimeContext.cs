using System.Text.Json.Nodes;

namespace SkillTreeEditor.Server.Services;

internal readonly record struct BalanceVector(double X, double Y, double Z)
{
    internal static BalanceVector Zero => new(0, 0, 0);
    internal double LengthSquared => X * X + Y * Y + Z * Z;
    internal double Length => Math.Sqrt(LengthSquared);
    internal BalanceVector Normalized => LengthSquared <= 1e-8 ? new(0, 0, 1) : this / Length;
    internal BalanceVector Horizontal => new(X, 0, Z);
    public static BalanceVector operator +(BalanceVector a, BalanceVector b) => new(a.X + b.X, a.Y + b.Y, a.Z + b.Z);
    public static BalanceVector operator -(BalanceVector a, BalanceVector b) => new(a.X - b.X, a.Y - b.Y, a.Z - b.Z);
    public static BalanceVector operator *(BalanceVector a, double b) => new(a.X * b, a.Y * b, a.Z * b);
    public static BalanceVector operator /(BalanceVector a, double b) => new(a.X / b, a.Y / b, a.Z / b);
    internal static double Dot(BalanceVector a, BalanceVector b) => a.X * b.X + a.Y * b.Y + a.Z * b.Z;
}

internal sealed record BalanceRuntimeCast(string Id, JsonObject Definition, IReadOnlyDictionary<string, double> Stats, long Serial = 0,
    double? ShieldBreakRatio = null, double? SuperCriticalChance = null, double? ResolvedAttackPower = null,
    IReadOnlySet<string>? AdditiveElements = null, string ConditionAttackType = "MAGIC")
{
    internal string ImplementationId => BalanceCombatData.Text(Definition["implementationId"]);
    internal JsonObject Params => BalanceSkillParameters.Params(Definition);
    internal bool Tagged(string tag) => BalanceCombatData.Array(Definition["tags"]).Any(value => value?.ToString() == tag);
    internal double Stat(string key, double fallback = 0) => Stats.GetValueOrDefault(key, fallback);
}

internal sealed record BalanceRuntimeHit(BalanceRuntimeCast Cast, string AttackType, string Source,
    IReadOnlyList<BalanceSkillComponent> Components, double RawDamage, double HpDamage, double ShieldDamage,
    bool Evaded, bool Critical, bool Killed, bool Secondary = false)
{
    internal bool Landed => !Evaded && (HpDamage > 0 || ShieldDamage > 0);
    internal double Damage => HpDamage + ShieldDamage;
}

internal sealed record BalanceRuntimeEvent(string Kind, BalanceRuntimeCast? Cast = null, BalanceRuntimeHit? Hit = null,
    string Condition = "", string Reason = "SKILL", JsonObject? Input = null);

internal sealed record BalanceRuntimeProjectile(double Range, double Speed, double Radius,
    BalanceVector Origin, BalanceVector Direction, string AttackType, IReadOnlyList<BalanceSkillComponent> Components,
    double Gravity = 0, int MaxTicks = 0, bool Piercing = false, bool Secondary = false, bool Interceptable = true,
    Action<BalanceRuntimeHit>? OnHit = null, Action<BalanceVector, string>? OnEnd = null,
    Action<BalanceVector>? OnCollision = null, Func<IReadOnlyList<BalanceSkillComponent>>? EvaluateComponents = null,
    bool PointCollision = false, double HomingStrength = 0, double HomingRange = 0,
    Action<BalanceVector, BalanceVector, string>? OnTermination = null,
    IReadOnlyList<BalanceSkillComponent>? InterceptionComponents = null);

internal sealed record BalanceRuntimeCircle(string Key, string Kind, BalanceVector Center, double Radius,
    int CreatedTick, int UntilTick, bool Support = false);

internal sealed class BalanceRuntimeAlly
{
    internal required string Id { get; init; }
    internal BalanceVector Position { get; set; }
    internal Dictionary<string, double> Stats { get; } = new(StringComparer.OrdinalIgnoreCase);
    internal Dictionary<string, double> BaseStats { get; } = new(StringComparer.OrdinalIgnoreCase);
    internal HashSet<string> Conditions { get; } = new(StringComparer.Ordinal);
    internal double Hp, Mp, Eng, Shield;
    internal int InvulnerableUntil;
    internal bool Dead => Hp <= 0;
    internal bool ShieldEnabled { get; set; }
}

internal sealed record BalanceRuntimeCastResult(bool Success, int? CooldownTicks = null, bool ConsumeCommon = true)
{
    internal static BalanceRuntimeCastResult Failed => new(false, ConsumeCommon: false);
    internal static BalanceRuntimeCastResult Succeeded => new(true);
}

internal interface IBalanceSkillRuntime
{
    bool Handles(string implementationId);
    BalanceRuntimeCastResult Cast(BalanceRuntimeContext context, BalanceRuntimeCast cast);
    void OnEvent(BalanceRuntimeContext context, BalanceRuntimeEvent value);
}

/// <summary>Per-trial services. All numeric damage, healing and resource application stay in the shared engine.</summary>
internal sealed class BalanceRuntimeContext
{
    internal int Tick { get; set; }
    internal required JsonObject Catalog { get; init; }
    internal required JsonObject Build { get; init; }
    internal required JsonObject Request { get; init; }
    internal required Random Random { get; init; }
    internal required IReadOnlyDictionary<string, JsonObject> Definitions { get; init; }
    internal required IReadOnlyDictionary<string, JsonObject> Passives { get; init; }
    internal required Func<string, double> ReadResource { get; init; }
    internal required Action<string, double> WriteResource { get; init; }
    internal required Func<bool, IReadOnlyDictionary<string, double>> ReadStats { get; init; }
    internal required Action<BalanceRuntimeCast, int, Action, string> Queue { get; init; }
    internal required Func<BalanceRuntimeCast, string, IReadOnlyList<BalanceSkillComponent>, string, double, bool, IReadOnlyDictionary<string, double>?, BalanceRuntimeHit> Damage { get; init; }
    internal required Func<BalanceRuntimeCast, double, double> Healing { get; init; }
    internal required Func<BalanceRuntimeCast, string, double, int, double, bool> Condition { get; init; }
    internal required Action<string, string, double, string, int, string> StatEffect { get; init; }
    internal required Action<string, string, double, int> MultiplierEffect { get; init; }
    internal required Func<BalanceRuntimeCast, string, bool> Buff { get; init; }
    internal required Action<BalanceRuntimeCast, BalanceRuntimeProjectile> Projectile { get; init; }
    internal required Action<string, int> SetSkillCooldown { get; init; }
    internal required Action<BalanceRuntimeCast, string, double, string> Trace { get; init; }
    internal required Action<BalanceRuntimeEvent> Dispatch { get; init; }
    internal required Func<BalanceVector, BalanceVector, double, BalanceVector> MovePlayer { get; init; }
    internal required Func<string, BalanceVector, bool> Velocity { get; init; }
    internal required Action<string> CancelScope { get; init; }
    internal Action<string> RemoveBuff { get; init; } = _ => { };
    internal Func<BalanceRuntimeCast, BalanceRuntimeProjectile, BalanceVector, BalanceVector, bool>? Intercept { get; set; }
    internal Action? NormalAttack { get; init; }
    internal Action<string, double>? ResourceRecovery { get; init; }
    internal Func<BalanceRuntimeCast, bool>? TryConsumeCastResources { get; init; }
    internal List<BalanceSkillComponent> NormalDamageComponents { get; } = [];
    internal HashSet<string> NormalAdditiveElements { get; } = new(StringComparer.Ordinal);
    internal List<BalanceVector> NormalDirections { get; } = [];
    internal double NormalDamageMultiplier { get; set; } = 1;
    internal Action<BalanceVector, BalanceRuntimeHit?>? NormalImpact { get; set; }
    internal Func<string, int>? BuffRemaining { get; init; }
    internal Action<string, int>? SetBuffRemaining { get; init; }
    internal Func<int>? ClearNegativeEffects { get; init; }
    internal Func<string, BalanceVector, bool>? AdditionalVelocity { get; init; }
    internal List<BalanceRuntimeAlly> Allies { get; } = [];
    internal Func<BalanceRuntimeAlly, double, double>? AllyDamageInterceptor { get; set; }
    internal Func<BalanceRuntimeCast, BalanceRuntimeAlly, double, double>? AllyHealing { get; init; }
    internal Action<BalanceRuntimeAlly, string, double>? AllyRecovery { get; init; }
    internal Func<BalanceRuntimeCast, BalanceRuntimeAlly, string, bool>? ApplyAllyBuff { get; init; }
    internal Action<BalanceRuntimeAlly, string>? RemoveAllyBuff { get; init; }
    internal Func<BalanceRuntimeAlly, int>? ClearAllyNegativeEffects { get; init; }
    internal string PlayerId { get; init; } = "00000000-0000-0000-0000-000000000000";
    internal double HealAlly(BalanceRuntimeCast cast, BalanceRuntimeAlly ally, double amount) => AllyHealing?.Invoke(cast, ally, amount) ?? 0;
    internal void RecoverAlly(BalanceRuntimeAlly ally, string resource, double amount) => AllyRecovery?.Invoke(ally, resource, amount);
    internal string WeaponTag { get; set; } = "";
    internal bool HasBow => WeaponTag is "BOW" or "SHORTBOW" or "LONGBOW";
    internal double NaturalRegenMultiplier { get; set; } = 1;
    internal Dictionary<string, object> State { get; } = new(StringComparer.Ordinal);
    internal Dictionary<string, double> CustomResources { get; } = new(StringComparer.Ordinal);
    internal Dictionary<string, BalanceRuntimeCircle> Circles { get; } = new(StringComparer.Ordinal);
    internal HashSet<string> EnemyConditions { get; } = new(StringComparer.Ordinal);
    internal HashSet<string> PlayerConditions { get; } = new(StringComparer.Ordinal);
    internal BalanceVector PlayerPosition { get; set; }
    internal BalanceVector EnemyPosition { get; set; }
    internal BalanceVector Facing { get; set; } = new(0, 0, 1);
    internal double EnemyHalfWidth { get; set; } = .3;
    internal double EnemyHeight { get; set; } = 1.8;
    internal bool Sneaking { get; set; }
    internal bool OnGround { get; set; } = true;
    internal bool Dodging { get; set; }
    internal bool IncomingCancelled { get; set; }
    internal double IncomingMultiplier { get; set; } = 1;
    internal double IncomingDamage { get; set; }
    internal double IncomingRawDamage { get; set; }
    internal int InvulnerableUntil { get; set; }
    internal int CastReductionTicks { get; set; }
    internal double AdditionalCastReductionPercent { get; set; }
    internal bool SuppressNotifications { get; set; }
    internal double FloorY { get; set; }
    internal double Hp => Resource("HP");
    internal double Mp => Resource("MP");
    internal double Eng => Resource("ENG");
    internal double Shield => Resource("SHIELD");
    internal double EnemyHp => Resource("ENEMY_HP");
    internal double EnemyShield => Resource("ENEMY_SHIELD");
    internal bool EnemyAlive => EnemyHp > 0;
    internal IReadOnlyDictionary<string, double> PlayerStats => ReadStats(false);
    internal IReadOnlyDictionary<string, double> EnemyStats => ReadStats(true);
    internal BalanceVector Eye => PlayerPosition + new BalanceVector(0, 1.62, 0);
    internal BalanceVector EnemyCenter => EnemyPosition + new BalanceVector(0, EnemyHeight / 2, 0);
    internal double Distance => (EnemyPosition - PlayerPosition).Length;
    internal int CircleCount => Circles.Values.Count(circle => circle.UntilTick > Tick);
    internal int StrikeCircleCount => Math.Min(3, CircleCount);
    internal bool HasPassive(string id) => Passives.ContainsKey(id);
    internal JsonObject? Passive(string id) => Passives.GetValueOrDefault(id);
    internal JsonObject? Skill(string id) => Definitions.GetValueOrDefault(id);
    internal double Stat(string key, double fallback = 0) => PlayerStats.GetValueOrDefault(key, fallback);
    internal double Resource(string key) => key is "HP" or "MP" or "ENG" or "SHIELD" or "ENEMY_HP" or "ENEMY_SHIELD"
        ? ReadResource(key) : CustomResources.GetValueOrDefault(key);
    internal void SetResource(string key, double value)
    {
        if (!double.IsFinite(value)) throw new InvalidOperationException("専用リソースを有限数で計算できません。");
        if (key is "HP" or "MP" or "ENG" or "SHIELD" or "ENEMY_HP" or "ENEMY_SHIELD") WriteResource(key, value);
        else CustomResources[key] = Math.Max(0, value);
    }
    internal void AddResource(string key, double value) => SetResource(key, Resource(key) + value);
    internal void RecoverResource(string key, double value)
    {
        if (ResourceRecovery is not null) ResourceRecovery(key, value);
        else AddResource(key, value * (1 + Math.Max(0, Stat("SUPPORT_POWER")) / 100));
    }
    internal bool Spend(string key, double value)
    {
        if (Resource(key) + 1e-9 < value) return false;
        AddResource(key, -value); return true;
    }
    internal BalanceRuntimeCast Snapshot(JsonObject definition) => new(BalanceCombatData.Text(definition["id"]), definition,
        new Dictionary<string, double>(PlayerStats, StringComparer.OrdinalIgnoreCase));
    internal BalanceRuntimeCast? Snapshot(string implementationId) => Skill(implementationId) is { } definition ? Snapshot(definition) : null;
    internal void Schedule(BalanceRuntimeCast cast, int delay, Action action, string scope = "") => Queue(cast, Math.Max(0, delay), action, scope);
    internal BalanceRuntimeHit Hit(BalanceRuntimeCast cast, double ratio, string type = "MAGIC", string element = "NONE",
        string source = "SKILL", double shieldMultiplier = 1, bool secondary = false, IReadOnlyDictionary<string, double>? victimOverride = null)
        => Damage(cast, type, [new(element, ratio)], source, shieldMultiplier, secondary, victimOverride);
    internal BalanceRuntimeHit Hit(BalanceRuntimeCast cast, IReadOnlyList<BalanceSkillComponent> components, string type = "MAGIC",
        string source = "SKILL", double shieldMultiplier = 1, bool secondary = false, IReadOnlyDictionary<string, double>? victimOverride = null)
        => Damage(cast, type, components, source, shieldMultiplier, secondary, victimOverride);
    internal double Heal(BalanceRuntimeCast cast, double amount) => Healing(cast, amount);
    internal bool ApplyCondition(BalanceRuntimeCast cast, string type, double chance, int ticks, double strength = 1)
        => Condition(cast, type, chance, ticks, strength);
    internal void AddStat(string key, string status, double value, string mode, int duration, string target = "player")
        => StatEffect(key, status, value, mode, duration, target);
    internal void Multiply(string key, string kind, double value, int duration) => MultiplierEffect(key, kind, value, duration);
    internal bool ApplyBuff(BalanceRuntimeCast cast, string id) => Buff(cast, id);
    internal void Launch(BalanceRuntimeCast cast, BalanceRuntimeProjectile projectile) => Projectile(cast, projectile);
    internal void Record(BalanceRuntimeCast cast, string kind, double value = 0, string detail = "") => Trace(cast, kind, value, detail);
    internal BalanceVector Dash(double distance) => MovePlayer(PlayerPosition, Facing.Horizontal.Normalized, Math.Max(0, distance));
    internal BalanceVector Backstep(double distance) => MovePlayer(PlayerPosition, Facing.Horizontal.Normalized * -1, Math.Max(0, distance));
    internal BalanceVector GroundTarget(double range)
    {
        var end = RayEnd(range);
        // SkillTargetingService.groundAt searches integer block heights, then adds 0.05.
        return FloorY >= Math.Floor(end.Y) - 32 && FloorY <= Math.Floor(end.Y) + 3
            ? new(end.X, FloorY + .05, end.Z) : end;
    }
    internal BalanceVector RayEnd(double range)
    {
        var direction = Facing.Normalized;
        var distance = direction.Y < -1e-8 ? (FloorY - Eye.Y) / direction.Y : double.PositiveInfinity;
        return distance >= 0 && distance <= range ? Eye + direction * distance : Eye + direction * range;
    }
    internal bool SetVelocity(string target, BalanceVector value) => Velocity(target, value);
    internal bool AddVelocity(string target, BalanceVector value) => AdditionalVelocity?.Invoke(target, value) ?? Velocity(target, value);
    internal bool InRadius(BalanceVector center, double radius, double height)
    {
        var dx = Math.Max(0, Math.Abs(center.X - EnemyPosition.X) - EnemyHalfWidth);
        var dz = Math.Max(0, Math.Abs(center.Z - EnemyPosition.Z) - EnemyHalfWidth);
        return dx * dx + dz * dz <= radius * radius
            && EnemyPosition.Y + EnemyHeight >= center.Y - height - 1e-6 && EnemyPosition.Y <= center.Y + height + 1e-6;
    }
    internal bool InSphere(BalanceVector center, double radius)
    {
        var nearest = new BalanceVector(Math.Clamp(center.X, EnemyPosition.X - EnemyHalfWidth, EnemyPosition.X + EnemyHalfWidth),
            Math.Clamp(center.Y, EnemyPosition.Y, EnemyPosition.Y + EnemyHeight),
            Math.Clamp(center.Z, EnemyPosition.Z - EnemyHalfWidth, EnemyPosition.Z + EnemyHalfWidth));
        return (nearest - center).LengthSquared <= radius * radius;
    }
    internal bool InCone(double range, double angle, BalanceVector? origin = null, BalanceVector? direction = null)
    {
        var delta = EnemyCenter - (origin ?? Eye);
        return delta.LengthSquared > 1e-8 && delta.LengthSquared <= range * range
            && BalanceVector.Dot(delta.Normalized, (direction ?? Facing).Normalized) >= Math.Cos(angle / 2 * Math.PI / 180);
    }
    internal bool InLine(BalanceVector start, BalanceVector end, double radius)
        => BalanceRuntimeGeometry.SegmentHitsBox(start, end, EnemyPosition, EnemyHalfWidth, EnemyHeight, radius, out _);
    internal T Memory<T>(string key, Func<T> create) where T : class
    {
        if (State.TryGetValue(key, out var value)) return (T)value;
        var result = create(); State[key] = result; return result;
    }
}

internal static class BalanceRuntimeGeometry
{
    internal static bool SegmentHitsBox(BalanceVector start, BalanceVector end, BalanceVector foot,
        double halfWidth, double height, double radius, out double fraction)
    {
        var min = foot + new BalanceVector(-halfWidth - radius, -radius, -halfWidth - radius);
        var max = foot + new BalanceVector(halfWidth + radius, height + radius, halfWidth + radius);
        var delta = end - start; var near = 0d; var far = 1d;
        double[] from = [start.X, start.Y, start.Z], dir = [delta.X, delta.Y, delta.Z];
        double[] low = [min.X, min.Y, min.Z], high = [max.X, max.Y, max.Z];
        for (var axis = 0; axis < 3; axis++)
        {
            if (Math.Abs(dir[axis]) <= 1e-8) { if (from[axis] < low[axis] || from[axis] > high[axis]) { fraction = 0; return false; } continue; }
            var first = (low[axis] - from[axis]) / dir[axis]; var second = (high[axis] - from[axis]) / dir[axis];
            if (first > second) (first, second) = (second, first);
            near = Math.Max(near, first); far = Math.Min(far, second);
            if (near > far) { fraction = 0; return false; }
        }
        fraction = near; return true;
    }
}
