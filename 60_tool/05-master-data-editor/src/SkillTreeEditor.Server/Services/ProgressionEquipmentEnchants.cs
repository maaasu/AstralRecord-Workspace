using System.Globalization;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;

namespace SkillTreeEditor.Server.Services;

internal static class ProgressionEquipmentEnchants
{
    private const decimal MaxValue = 100_000_000_000m;
    private const int FractionDigits = 4;
    private const long Resolution = 10_000;
    private const long MaxUnits = (long)MaxValue * Resolution;
    private const int MaxUnitDigits = 16;
    private static readonly Regex ValuePattern = new(@"\A-?(0|[1-9][0-9]{0,11})([.][0-9]{1,4})?\z", RegexOptions.CultureInvariant);
    private static readonly Regex EndpointPattern = new(
        @"\A(?<sign>[+-]?)(?<whole>[0-9]*)(?:[.](?<fraction>[0-9]*))?(?:[eE](?<exponent>[+-]?[0-9]+))?\z",
        RegexOptions.CultureInvariant | RegexOptions.NonBacktracking);

    internal sealed record Effect(string MasterId, string EquipmentType, string EffectId, string Status, string Type,
        decimal Min, decimal Max);

    internal static int MaxSlots(JsonObject? equipment) => equipment?["enchant"] is JsonObject definition
        && EquipmentType(MasterDataValidation.Text(equipment["slot"])) is not null
            ? Math.Max(0, MasterDataService.ReadModelData(definition["maxSlots"]) ?? 1) : 0;

    internal static string? EquipmentType(string? slot) => slot?.Trim().ToUpperInvariant() switch
    {
        "WEAPON" or "SUBWEAPON" => "WEAPON",
        "HEAD" or "CHEST" or "LEGS" or "FEET" => "ARMOR",
        "ACCESSORY" => "ACCESSORY",
        _ => null,
    };

    internal static (string MasterId, int Priority)? OrbSource(JsonObject item)
    {
        if (item["orb"] is not JsonObject orb || orb["effect"] is not JsonObject effect
            || !string.Equals(MasterDataValidation.Text(effect["type"]), "ENCHANT", StringComparison.OrdinalIgnoreCase)
            || MasterDataValidation.Text(effect["enchantMasterId"]) is not { } reference)
            return null;
        var id = reference.Trim();
        if (id.StartsWith("enchant:", StringComparison.OrdinalIgnoreCase)) id = id["enchant:".Length..].Trim();
        if (id.Length == 0) return null;
        var priority = MasterDataValidation.Text(effect["enchantOperation"])?.ToUpperInvariant() switch
        {
            "FILL_ONE_EMPTY" => 0,
            "FILL_ALL_EMPTY" => 1,
            "OVERWRITE_RANDOM" => 2,
            _ => 3,
        };
        return ("enchant:" + id, priority);
    }

    internal static IEnumerable<Effect> Read(JsonObject master)
    {
        if (MasterDataValidation.Text(master["id"]) is not { } id) yield break;
        foreach (var target in (master["targets"] as JsonArray ?? []).OfType<JsonObject>())
        {
            var equipment = MasterDataValidation.Text(target["equipmentType"])?.Trim().ToUpperInvariant();
            if (equipment is not ("WEAPON" or "ARMOR" or "ACCESSORY")) continue;
            foreach (var entry in (target["entries"] as JsonArray ?? []).OfType<JsonObject>())
            {
                if (MasterDataValidation.Text(entry["effectId"])?.Trim() is not { Length: > 0 and <= 100 } effectId
                    || MasterDataValidation.Text(entry["status"])?.Trim() is not { Length: > 0 and <= 50 } status
                    || MasterDataValidation.Text(entry["type"])?.Trim().ToUpperInvariant() is not ("FLAT" or "SCALAR")
                    || MasterDataService.ReadModelData(entry["weight"]) is not > 0
                    || !TryRange(entry["value"], out var min, out var max))
                    continue;
                yield return new("enchant:" + id, equipment, effectId, status,
                    MasterDataValidation.Text(entry["type"])!.Trim().ToUpperInvariant(), min, max);
            }
        }
    }

    internal static decimal? Value(JsonNode? node) => JsonValueReader.String(node) is { } text
        && ValuePattern.IsMatch(text) && TryDecimal(text, out var value) && Math.Abs(value) <= MaxValue ? value : null;

    private static bool TryRange(JsonNode? node, out decimal min, out decimal max)
    {
        min = max = 0;
        var endpoints = (MasterDataValidation.Text(node) ?? node?.ToJsonString())?.Split('~');
        if (endpoints is not { Length: 1 or 2 } || !TryBracketEndpoint(endpoints[0], out var first)
            || !TryBracketEndpoint(endpoints[^1], out var last)) return false;
        // 切り上げと切り下げは単調なので、端点が逆順でも内側の範囲を求められる。
        var lower = Math.Max(-MaxUnits, Math.Min(first.Ceiling, last.Ceiling));
        var upper = Math.Min(MaxUnits, Math.Max(first.Floor, last.Floor));
        min = (decimal)lower / Resolution;
        max = (decimal)upper / Resolution;
        return lower <= upper;
    }

    // プラグインの BigDecimal と同じく、src の全桁を使って 0.0001 刻みの両側を決める。
    // 保存範囲の外側は番兵へ写し、巨大な指数や係数でも累乗や多倍長整数を作らない。
    private static bool TryBracketEndpoint(string text, out (long Floor, long Ceiling) bounds)
    {
        bounds = (0, 0);
        var match = EndpointPattern.Match(text.Trim());
        if (!match.Success) return false;
        var whole = match.Groups["whole"].ValueSpan;
        var fraction = match.Groups["fraction"].ValueSpan;
        if (whole.IsEmpty && fraction.IsEmpty) return false;

        var exponentGroup = match.Groups["exponent"];
        var exponent = 0;
        if (exponentGroup.Success && !int.TryParse(exponentGroup.ValueSpan, NumberStyles.AllowLeadingSign,
            CultureInfo.InvariantCulture, out exponent)) return false;
        var scale = (long)fraction.Length - exponent;
        if (scale is < int.MinValue or > int.MaxValue) return false;

        var digits = string.Concat(whole, fraction).AsSpan().TrimStart('0');
        if (digits.IsEmpty) return true;
        var unitDigits = digits.Length - scale + FractionDigits;
        const long outside = MaxUnits + 1;
        long floor;
        long ceiling;
        if (unitDigits > MaxUnitDigits)
        {
            floor = ceiling = outside;
        }
        else
        {
            var kept = (int)Math.Clamp(unitDigits, 0, digits.Length);
            floor = kept == 0 ? 0 : long.Parse(digits[..kept], NumberStyles.None, CultureInfo.InvariantCulture);
            for (var index = kept; index < unitDigits; index++) floor *= 10;
            var hasRemainder = !digits[kept..].TrimEnd('0').IsEmpty;
            ceiling = Math.Min(outside, floor + (hasRemainder ? 1 : 0));
            floor = Math.Min(outside, floor);
        }

        bounds = match.Groups["sign"].Value == "-" ? (-ceiling, -floor) : (floor, ceiling);
        return true;
    }

    private static bool TryDecimal(string text, out decimal value)
        => decimal.TryParse(text, NumberStyles.Float, CultureInfo.InvariantCulture, out value);
}
