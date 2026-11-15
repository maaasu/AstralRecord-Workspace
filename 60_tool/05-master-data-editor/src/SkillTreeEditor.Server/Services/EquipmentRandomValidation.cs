using System.Text.Json;
using System.Text.Json.Nodes;
using System.Globalization;
using SkillTreeEditor.Server.Models;

namespace SkillTreeEditor.Server.Services;

/// <summary>Validates the opt-in equipment stat roll contract beyond JSON Schema's cross-field checks.</summary>
public static class EquipmentRandomValidation
{
    public static void Validate(JsonObject item, List<MasterDataIssue> issues)
    {
        if (item["equipment"] is not JsonObject equipment || equipment["stats"] is not JsonArray stats) return;
        for (var index = 0; index < stats.Count; index++)
        {
            if (stats[index] is not JsonObject stat || !stat.ContainsKey("random")) continue;
            var path = $"/equipment/stats/{index}";
            void Error(string field, string message) => issues.Add(new("error", "EQUIPMENT_STAT_RANDOM", path + field, message));

            if (stat["value"] is not JsonValue value || !TryDecimal(value, out var baseValue, allowString: true) || !FourPlaces(baseValue)
                || stat.ContainsKey("min") || stat.ContainsKey("max"))
            {
                Error("/value", "random を使う場合、value は小数4桁以内の単一数値にしてください。範囲とは併用できません。");
                continue;
            }
            if (stat["random"] is not JsonObject random)
            {
                Error("/random", "random は設定オブジェクトにしてください。");
                continue;
            }
            if (!TryDecimal(random["offsetMin"], out var offsetMin) || !FourPlaces(offsetMin)
                || !TryDecimal(random["offsetMax"], out var offsetMax) || !FourPlaces(offsetMax)
                || !TryDecimal(random["step"], out var step) || !FourPlaces(step))
            {
                Error("/random", "offsetMin、offsetMax、step は小数4桁以内の有限数値で指定してください。");
                continue;
            }
            if (!TryExactDoubleDecimal(baseValue, out _))
            {
                Error("/value", "基準値は double で十進往復できる数値にしてください。");
                continue;
            }
            if (!TryExactDoubleDecimal(offsetMin, out _) || !TryExactDoubleDecimal(offsetMax, out _)
                || !TryExactDoubleDecimal(step, out _))
            {
                Error("/random", "offsetMin、offsetMax、step は double で十進往復できる数値にしてください。");
                continue;
            }
            if (offsetMin > offsetMax || step <= 0)
            {
                Error("/random", "offsetMin は offsetMax 以下、step は正数にしてください。");
                continue;
            }
            // Multiplication avoids overflow from dividing an arbitrarily large decimal span.
            var span = (double)offsetMax - (double)offsetMin;
            if (!double.IsFinite(span) || span > (double)step * 999_999)
                Error("/random/step", "offset 差は step の整数倍とし、候補数を100万以下にしてください。");
            else
            {
                try
                {
                    var steps = (offsetMax - offsetMin) / step;
                    if (steps != decimal.Truncate(steps) || steps > 999_999)
                        Error("/random/step", "offset 差は step の整数倍とし、候補数を100万以下にしてください。");
                }
                catch (OverflowException)
                {
                    Error("/random/step", "offset 差は step の整数倍とし、候補数を100万以下にしてください。");
                }
            }
            if (!TryExactDoubleEndpoint(baseValue, offsetMin, out var low)
                || !TryExactDoubleEndpoint(baseValue, offsetMax, out var high))
                Error("/random", "基準値と offset の端点は double で十進往復できる有限数値にしてください。");
            else if (offsetMin != offsetMax)
            {
                var magnitude = Math.Max(Math.Abs(low), Math.Abs(high));
                var ulp = Math.BitIncrement(magnitude) - magnitude;
                if ((double)step < ulp)
                    Error("/random/step", "刻み幅は抽選区間で区別できる double の最小幅以上にしてください。");
            }

            var zeroChance = 0d;
            if (random.ContainsKey("zeroChance") && (!TryDouble(random["zeroChance"], out zeroChance)
                || zeroChance is < 0 or > 1))
                Error("/random/zeroChance", "zeroChance は0から1の有限数値にしてください。");
            else if (zeroChance > 0 && (double)baseValue + (double)offsetMin <= 0
                && (double)baseValue + (double)offsetMax >= 0)
                Error("/random/zeroChance", "zeroChance を使う場合、通常の抽選区間に0を含めないでください。");
        }
    }

    private static bool TryDecimal(JsonNode? node, out decimal result, bool allowString = false)
    {
        result = 0;
        if (node is not JsonValue value) return false;
        var text = value.GetValueKind() == JsonValueKind.Number ? value.ToJsonString()
            : allowString && value.GetValueKind() == JsonValueKind.String ? value.GetValue<string>() : null;
        if (text is null) return false;
        if (!decimal.TryParse(text, NumberStyles.Float, CultureInfo.InvariantCulture, out result)) return false;
        var exponent = text.IndexOfAny(new[] { 'e', 'E' });
        var significand = exponent < 0 ? text : text[..exponent];
        return result != 0 || !significand.Any(character => character is >= '1' and <= '9');
    }

    private static bool TryDouble(JsonNode? node, out double result)
    {
        result = 0;
        return node is JsonValue value && value.GetValueKind() == JsonValueKind.Number
            && double.TryParse(value.ToJsonString(), System.Globalization.NumberStyles.Float,
                System.Globalization.CultureInfo.InvariantCulture, out result) && double.IsFinite(result);
    }

    private static bool FourPlaces(decimal value) => decimal.Round(value, 4) == value;

    private static bool TryExactDoubleEndpoint(decimal baseValue, decimal offset, out double result)
    {
        result = 0;
        decimal endpoint;
        try { endpoint = baseValue + offset; }
        catch (OverflowException) { return false; }
        return TryExactDoubleDecimal(endpoint, out result);
    }

    private static bool TryExactDoubleDecimal(decimal value, out double result)
    {
        result = (double)value;
        return double.IsFinite(result)
            && decimal.TryParse(result.ToString("R", CultureInfo.InvariantCulture), NumberStyles.Float,
                CultureInfo.InvariantCulture, out var roundTrip)
            && roundTrip == value;
    }
}
