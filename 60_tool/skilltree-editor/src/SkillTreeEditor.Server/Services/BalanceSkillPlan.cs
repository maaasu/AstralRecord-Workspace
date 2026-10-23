using System.Text.Json.Nodes;
using System.Runtime.CompilerServices;

[assembly: InternalsVisibleTo("SkillTreeEditor.Server.Tests")]

namespace SkillTreeEditor.Server.Services;

// A plan describes Plugin executor operations, never guesses behavior from a skill name.
internal sealed record BalanceSkillView(
    IReadOnlyDictionary<string, double> Player, IReadOnlyDictionary<string, double> Enemy,
    double Hp, double EnemyHp, double Mp, double Eng, double Shield, double EnemyShield,
    bool LastLanded, bool LastCritical, bool LastKilled, IReadOnlySet<string> Conditions, int CircleCount,
    double LastDamage = 0, double LastShieldDamage = 0)
{
    internal double Stat(string key) => Player.GetValueOrDefault(key);
    internal bool HasCondition(string key) => Conditions.Contains(key);
}

internal sealed record BalanceSkillComponent(string Element, double Ratio);

internal sealed record BalanceSkillEffect(
    string Kind, int Tick = 0, double Value = 0,
    string AttackType = "MAGIC", string Element = "NONE", string Basis = "flat",
    int DurationTicks = 0, string Target = "player", string Status = "",
    string Mode = "FLAT", string Trigger = "always", double Chance = 100,
    double Minimum = 0, double ShieldMultiplier = 1,
    Func<BalanceSkillView, double>? Evaluate = null,
    IReadOnlyList<BalanceSkillComponent>? Components = null,
    double FlightSpeed = 0, double HitRadius = 0, double Range = 0, string Scope = "",
    bool EvaluateAtCast = false, Func<BalanceSkillView, bool>? When = null, double FlightOriginOffset = 0);

internal sealed record BalanceSkillProfile(
    string ImplementationId, IReadOnlyList<BalanceSkillEffect> Effects,
    IReadOnlyList<string> Sources, IReadOnlyList<string> KnownParameters,
    IReadOnlyList<string> Assumptions, IReadOnlyList<string> Reasons)
{
    internal bool Supported => Reasons.Count == 0;
}

internal static class BalanceSkillParameters
{
    internal static JsonObject Params(JsonObject definition) => definition["params"] as JsonObject ?? new();
    internal static double Number(JsonObject parameters, string key, double fallback = 0)
    {
        if (!parameters.ContainsKey(key)) return fallback;
        if (parameters[key] is not JsonValue node || node.GetValueKind() != System.Text.Json.JsonValueKind.Number
            || !double.TryParse(node.ToJsonString(), System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out var value) || !double.IsFinite(value))
            throw new ArgumentException($"params.{key} は有限の数値で指定してください。");
        return value;
    }
    internal static int Integer(JsonObject parameters, string key, int fallback = 0)
    {
        var value = Number(parameters, key, fallback);
        if (value != Math.Truncate(value) || value < int.MinValue || value > int.MaxValue)
            throw new ArgumentException($"params.{key} は整数で指定してください。");
        return (int)value;
    }
    internal static double[] Numbers(JsonObject parameters, string key)
    {
        if (!parameters.ContainsKey(key)) return [];
        if (parameters[key] is not JsonArray values) throw new ArgumentException($"params.{key} は数値配列です。");
        return values.Select(value => Number(new JsonObject { [key] = value?.DeepClone() }, key)).ToArray();
    }
}
