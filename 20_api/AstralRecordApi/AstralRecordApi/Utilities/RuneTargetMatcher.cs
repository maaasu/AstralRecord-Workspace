using AstralRecordApi.Models;

namespace AstralRecordApi.Utilities;

internal static class RuneTargetMatcher
{
    public static bool Matches(ItemEquipmentResponse equipment, ItemRuneResponse rune)
    {
        var slotMatches = rune.TargetSlots.Any(targetSlot =>
            IdEqualsIgnoreCase(targetSlot, "ANY") || IdEqualsIgnoreCase(targetSlot, equipment.Slot));
        if (!slotMatches)
            return false;

        return rune.TargetTags.Count == 0
            || rune.TargetTags.Any(targetTag => IdEquals(targetTag, equipment.Tag));
    }

    private static bool IdEqualsIgnoreCase(string? left, string? right) =>
        string.Equals(left?.Trim(), right?.Trim(), StringComparison.OrdinalIgnoreCase);

    private static bool IdEquals(string? left, string? right) =>
        string.Equals(left?.Trim(), right?.Trim(), StringComparison.Ordinal);
}
