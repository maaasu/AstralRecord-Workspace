using System.Globalization;

namespace AstralRecordWeb.Models;

public static class ItemEquipmentStatFormatter
{
    public static string Format(ItemEquipmentStatResponse stat)
    {
        var value = stat.Value;
        if (value is null) return string.Empty;
        if (stat.Random is not { } random) return FormatLegacy(value);

        if (!decimal.TryParse(value.Min, NumberStyles.Float, CultureInfo.InvariantCulture, out var basis))
            return FormatLegacy(value);

        var min = basis + (decimal)random.OffsetMin;
        var max = basis + (decimal)random.OffsetMax;
        var range = $"{min.ToString("0.####", CultureInfo.InvariantCulture)} - {max.ToString("0.####", CultureInfo.InvariantCulture)}";
        return random.ZeroChance > 0
            ? $"{range} (0の確率 {random.ZeroChance.ToString("P2", CultureInfo.CurrentCulture)})"
            : range;
    }

    private static string FormatLegacy(ItemEquipmentStatValueResponse value)
        => string.Equals(value.Min, value.Max, StringComparison.Ordinal)
            ? value.Min
            : $"{value.Min} - {value.Max}";
}
