using System.Globalization;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;

namespace SkillTreeEditor.Server.Services;

internal static class ProgressionEquipmentEnchants
{
    private const decimal MaxValue = 100_000_000_000m;
    private const decimal Resolution = 10_000m; // 付与値は小数4桁
    private static readonly Regex ValuePattern = new(@"\A-?(0|[1-9][0-9]{0,11})([.][0-9]{1,4})?\z", RegexOptions.CultureInvariant);

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
                min = decimal.Ceiling(Math.Max(min, -MaxValue) * Resolution) / Resolution;
                max = decimal.Floor(Math.Min(max, MaxValue) * Resolution) / Resolution;
                if (min > max) continue;
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
        if (endpoints is not { Length: 1 or 2 } || !TryDecimal(endpoints[0], out var first)
            || !TryDecimal(endpoints[^1], out var last)) return false;
        min = Math.Min(first, last);
        max = Math.Max(first, last);
        return min <= MaxValue && max >= -MaxValue;
    }

    private static bool TryDecimal(string text, out decimal value)
        => decimal.TryParse(text, NumberStyles.Float, CultureInfo.InvariantCulture, out value);
}
