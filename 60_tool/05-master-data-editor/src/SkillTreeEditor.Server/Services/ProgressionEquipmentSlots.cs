namespace SkillTreeEditor.Server.Services;

internal static class ProgressionEquipmentSlots
{
    private static readonly IReadOnlyDictionary<string, (string Type, string? Tag)> Definitions =
        new Dictionary<string, (string, string?)>(StringComparer.Ordinal)
        {
            ["MAIN_HAND"] = ("WEAPON", null), ["OFF_HAND"] = ("SUBWEAPON", null),
            ["HEAD"] = ("HEAD", null), ["CHEST"] = ("CHEST", null),
            ["LEGS"] = ("LEGS", null), ["FEET"] = ("FEET", null),
            ["SKILLBOOK"] = ("SKILLBOOK", null), ["AMULET"] = ("ACCESSORY", "AMULET"),
            ["TALISMAN_1"] = ("ACCESSORY", "TALISMAN"), ["TALISMAN_2"] = ("ACCESSORY", "TALISMAN"),
            ["CHARM_1"] = ("ACCESSORY", "CHARM"), ["CHARM_2"] = ("ACCESSORY", "CHARM"),
            ["CHARM_3"] = ("ACCESSORY", "CHARM"), ["CORE"] = ("ACCESSORY", "CORE"),
            ["RELIC_1"] = ("ACCESSORY", "RELIC"), ["RELIC_2"] = ("ACCESSORY", "RELIC")
        };

    internal static bool Matches(string? slot, string? type, string? tag)
        => slot is not null && Definitions.TryGetValue(slot, out var expected)
            && expected.Type == type && (expected.Tag is null || expected.Tag == tag);

    internal static string[] Compatible(string? type, string? tag)
        => Definitions.Keys.Where(slot => Matches(slot, type, tag)).ToArray();
}
