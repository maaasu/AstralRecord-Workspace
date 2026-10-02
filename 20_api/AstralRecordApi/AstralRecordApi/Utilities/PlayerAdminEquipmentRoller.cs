using System.Globalization;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;

namespace AstralRecordApi.Utilities;

/// <summary>
/// Resolves a master item's equipment values once for an offline admin grant.
/// Mirrors ItemService.createLocalEquipmentInstance without changing persistent state.
/// </summary>
public static class PlayerAdminEquipmentRoller
{
    private const int MaxNumericTextLength = 128;
    private static readonly CultureInfo Invariant = CultureInfo.InvariantCulture;

    public static (EquipmentInstanceEntity Instance, IReadOnlyList<EquipmentInstanceStatRollEntity> Rolls) Create(
        ItemResponse item,
        Guid accountId,
        Guid actor,
        DateTime now,
        Random? random = null)
    {
        ArgumentNullException.ThrowIfNull(item);
        var equipment = item.Equipment ?? throw new ArgumentException("Item must be equipment.", nameof(item));
        random ??= Random.Shared;

        var instanceId = Guid.NewGuid();
        var runeMaxSlots = equipment.Rune is null ? 0 : Math.Max(0, ResolveInt(equipment.Rune.MaxSlots, random));
        var durabilityMax = Math.Max(0, equipment.Durability?.Max ?? 0);
        var instance = new EquipmentInstanceEntity
        {
            EquipmentInstanceId = instanceId,
            AccountId = accountId,
            ItemId = item.Id,
            EnhanceLevel = 0,
            RuneMaxSlots = runeMaxSlots,
            TranscendenceRank = 0,
            DurabilityMax = durabilityMax,
            DurabilityValue = durabilityMax,
            CreatedAt = now,
            UpdatedAt = now,
            CreatedBy = actor,
            UpdatedBy = actor,
        };

        var rolls = new List<EquipmentInstanceStatRollEntity>();
        foreach (var stat in equipment.Stats)
        {
            if (string.IsNullOrWhiteSpace(stat.Status))
                continue;

            rolls.Add(new EquipmentInstanceStatRollEntity
            {
                StatRollId = Guid.NewGuid(),
                EquipmentInstanceId = instanceId,
                Status = stat.Status.Trim(),
                RandomMin = ResolveNumericString(stat.Value?.Min, random),
                RandomMax = ResolveNumericString(stat.Value?.Max, random),
                SortOrder = rolls.Count,
                CreatedAt = now,
                UpdatedAt = now,
                CreatedBy = actor,
                UpdatedBy = actor,
            });
        }

        return (instance, rolls);
    }

    private static int ResolveInt(string raw, Random random)
    {
        var value = Normalize(raw);
        var separator = value.IndexOf('~');
        if (separator < 0)
            return int.Parse(value, NumberStyles.AllowLeadingSign, Invariant);

        var min = int.Parse(value[..separator].Trim(), NumberStyles.AllowLeadingSign, Invariant);
        var max = int.Parse(value[(separator + 1)..].Trim(), NumberStyles.AllowLeadingSign, Invariant);
        if (min > max)
            (min, max) = (max, min);

        // A long upper bound keeps int.MaxValue inclusive without overflow.
        return min == max ? min : (int)random.NextInt64(min, (long)max + 1);
    }

    private static string ResolveNumericString(string? raw, Random random)
    {
        // A missing stat value has the same numeric fallback as ItemStat's default zero.
        var value = Normalize(string.IsNullOrWhiteSpace(raw) ? "0" : raw);
        var separator = value.IndexOf('~');
        if (separator < 0)
            return Format(decimal.Parse(value, NumberStyles.Float, Invariant));

        var minText = value[..separator].Trim();
        var maxText = value[(separator + 1)..].Trim();
        if (int.TryParse(minText, NumberStyles.AllowLeadingSign, Invariant, out var intMin)
            && int.TryParse(maxText, NumberStyles.AllowLeadingSign, Invariant, out var intMax))
        {
            if (intMin > intMax)
                (intMin, intMax) = (intMax, intMin);
            return (intMin == intMax ? intMin : random.NextInt64(intMin, (long)intMax + 1))
                .ToString(Invariant);
        }

        var min = decimal.Parse(minText, NumberStyles.Float, Invariant);
        var max = decimal.Parse(maxText, NumberStyles.Float, Invariant);
        if (min > max)
            (min, max) = (max, min);

        // Master values are bounded by decimal. Reject a span that cannot be sampled
        // safely rather than silently wrapping or persisting a range expression.
        decimal span;
        try
        {
            span = checked(max - min);
        }
        catch (OverflowException exception)
        {
            throw new FormatException("Equipment stat range exceeds supported decimal bounds.", exception);
        }

        var sample = (decimal)random.NextDouble();
        var resolved = decimal.Round(min + span * sample, 4, MidpointRounding.AwayFromZero);
        return Format(resolved);
    }

    private static string Normalize(string raw)
    {
        ArgumentNullException.ThrowIfNull(raw);
        if (raw.Length > MaxNumericTextLength)
            throw new FormatException("Equipment numeric value is too long.");
        return raw.Trim().Replace('～', '~');
    }

    private static string Format(decimal value) => value.ToString("0.############################", Invariant);
}
