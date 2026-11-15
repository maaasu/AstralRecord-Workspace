using System.Globalization;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace SkillTreeEditor.Server.Services;

internal static class ProgressionEquipmentEnchants
{
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

    internal static decimal? Value(JsonNode? node) => node?.GetValueKind() == JsonValueKind.Number
        && TryDecimal(node.ToJsonString(), out var value) ? value : null;

    internal static bool CanStore(decimal value) => value is >= -99_999_999_999_999.9999m and <= 99_999_999_999_999.9999m
        && decimal.Round(value, 4) == value;

    private static bool TryRange(JsonNode? node, out decimal min, out decimal max)
    {
        min = max = 0;
        var endpoints = (MasterDataValidation.Text(node) ?? node?.ToJsonString())?.Split('~');
        if (endpoints is not { Length: 1 or 2 } || !TryDecimal(endpoints[0], out var first)
            || !TryDecimal(endpoints[^1], out var last)) return false;
        min = Math.Min(first, last);
        max = Math.Max(first, last);
        return true;
    }

    private static bool TryDecimal(string text, out decimal value)
        => decimal.TryParse(text, NumberStyles.Float, CultureInfo.InvariantCulture, out value);
}
