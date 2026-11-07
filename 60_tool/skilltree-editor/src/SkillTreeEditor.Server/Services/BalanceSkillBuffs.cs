using System.Globalization;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace SkillTreeEditor.Server.Services;

/// <summary>Resolves the actual Buff master selected by HunterBuildUpExecutor.</summary>
internal static class BalanceSkillBuffs
{
    private const string BuildUp = "hunter_build_up";
    private const string BuffRoot = "10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/buff/";
    private static readonly HashSet<string> KnownFields = new(StringComparer.Ordinal)
    {
        "schemaVersion", "id", "type", "name", "lore", "icon", "hudIconTexture", "durationTicks",
        "isDebuff", "resetOnChallenge", "stackGroup", "modifiers"
    };

    internal static BalanceSkillProfile Resolve(JsonObject definition, JsonObject catalog, BalanceSkillProfile profile)
    {
        if (BalanceCombatData.Text(definition["implementationId"]) != BuildUp) return profile;
        var reasons = profile.Reasons.ToList();
        var parameters = definition["params"] as JsonObject;
        if (parameters is null || !TryNumber(parameters["energyRecoveryRatio"], out var recovery))
        {
            reasons.Add("hunter_build_up の energyRecoveryRatio から参照Buffレベルを解決できません。");
            return profile with { Reasons = reasons.Distinct(StringComparer.Ordinal).ToArray() };
        }

        // HunterBuildUpExecutor.resolveLevel uses Math.rint and a 1e-9 tolerance.
        var level = (int)Math.Round(recovery * 100, MidpointRounding.ToEven);
        if (level is < 1 or > 5 || Math.Abs(recovery - level / 100.0) > 1e-9)
        {
            reasons.Add("hunter_build_up の参照BuffレベルはENG回復率1%から5%に対応しません。");
            return profile with { Reasons = reasons.Distinct(StringComparer.Ordinal).ToArray() };
        }
        var buffId = level == 1 ? BuildUp : BuildUp + "_lv" + level;
        var matches = BalanceCombatData.Masters(catalog).Where(master => master.Kind == "buff"
            && BalanceCombatData.Text(master.Content["id"]) == buffId).ToArray();
        if (matches.Length != 1)
        {
            reasons.Add($"参照Buff {buffId} の定義が{(matches.Length == 0 ? "ありません" : "複数あります")}。実executorの発動結果を確定できません。");
            return profile with { Reasons = reasons.Distinct(StringComparer.Ordinal).ToArray() };
        }

        var buff = matches[0].Content;
        foreach (var field in buff.Select(entry => entry.Key).Where(key => !KnownFields.Contains(key)))
            reasons.Add($"参照Buff {buffId} の追加効果/項目 {field} は未検証です。");
        if (BalanceCombatData.Text(buff["type"]) != "BUFF") reasons.Add($"参照Buff {buffId} のtypeはBUFFが必要です。");
        if (buff["isDebuff"]?.GetValueKind() != JsonValueKind.False)
            reasons.Add($"参照Buff {buffId} のisDebuff変更は未対応です。");
        if (buff["resetOnChallenge"]?.GetValueKind() != JsonValueKind.True)
            reasons.Add($"参照Buff {buffId} のresetOnChallenge変更は未対応です。");
        if (BalanceCombatData.Text(buff["stackGroup"]) != BuildUp)
            reasons.Add($"参照Buff {buffId} のstackGroup変更は未対応です。");
        if (!TryInteger(buff["durationTicks"], out var duration) || duration is < 1 or > 72_000)
            reasons.Add($"参照Buff {buffId} のdurationTicksは1～72000の整数が必要です。");

        var modifiers = buff["modifiers"] as JsonArray;
        JsonObject? modifier = modifiers is { Count: 1 } ? modifiers[0] as JsonObject : null;
        if (modifier is null)
            reasons.Add($"参照Buff {buffId} は単一のRANGED_ATTACK modifierだけを再現できます。");
        var mode = BalanceCombatData.Text(modifier?["type"]);
        if (modifier is not null)
        {
            foreach (var field in modifier.Select(entry => entry.Key).Where(key => key is not ("status" or "type" or "value")))
                reasons.Add($"参照Buff {buffId} のmodifier追加項目 {field} は未検証です。");
            if (BalanceCombatData.Text(modifier["status"]) != "RANGED_ATTACK")
                reasons.Add($"参照Buff {buffId} の未知stats {BalanceCombatData.Text(modifier["status"])} は未対応です。");
            if (mode is not ("FLAT" or "SCALAR" or "FINAL_SCALAR"))
                reasons.Add($"参照Buff {buffId} のmodifier.type {mode} は未対応です。");
        }
        if (!TryNumber(modifier?["value"], out var value))
            reasons.Add($"参照Buff {buffId} のmodifier.valueは有限の数値が必要です。");
        if (string.IsNullOrWhiteSpace(matches[0].Path))
            reasons.Add($"参照Buff {buffId} の出典pathがありません。");
        var statEffects = profile.Effects.Select((effect, index) => (effect, index))
            .Where(pair => pair.effect.Kind == "stat" && pair.effect.Status == "RANGED_ATTACK").ToArray();
        if (statEffects.Length != 1)
            reasons.Add("hunter_build_up のRANGED_ATTACK効果計画を一意に置換できません。");

        if (reasons.Count > 0)
            return profile with { Reasons = reasons.Distinct(StringComparer.Ordinal).ToArray() };

        var effects = profile.Effects.ToArray();
        effects[statEffects[0].index] = statEffects[0].effect with { Value = value, Mode = mode, DurationTicks = duration };
        var sources = profile.Sources.Where(path => !path.StartsWith("40_filebase/70.shared.buff/v1.hunter_build_up", StringComparison.Ordinal))
            .Concat([matches[0].Path, BuffRoot + "repository/BuffRepository.kt", BuffRoot + "service/BuffService.java"])
            .Distinct(StringComparer.Ordinal).ToArray();
        var assumptions = profile.Assumptions.Concat([$"参照Buff {buffId} の実マスター {matches[0].Path} の補正値・持続tickを適用する。"])
            .Distinct(StringComparer.Ordinal).ToArray();
        return profile with { Effects = effects, Sources = sources, Assumptions = assumptions };
    }

    private static bool TryNumber(JsonNode? node, out double value)
    {
        value = 0;
        return node is JsonValue json && json.GetValueKind() == JsonValueKind.Number
            && double.TryParse(json.ToJsonString(), NumberStyles.Float, CultureInfo.InvariantCulture, out value)
            && double.IsFinite(value);
    }

    private static bool TryInteger(JsonNode? node, out int value)
    {
        value = 0;
        return TryNumber(node, out var number) && number == Math.Truncate(number)
            && number >= int.MinValue && number <= int.MaxValue && (value = (int)number) == number;
    }
}
