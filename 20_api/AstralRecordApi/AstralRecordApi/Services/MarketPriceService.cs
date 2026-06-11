using System.Globalization;
using AstralRecordApi.Data;
using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordApi.Services;

public class MarketPriceService(
    AstralRecordDbContext dbContext,
    IItemRepository itemRepository
) : IMarketPriceService
{
    private static readonly StringComparer KeyComparer = StringComparer.OrdinalIgnoreCase;

    public async Task<MarketPriceQuoteResponse?> CreateQuoteAsync(MarketPriceQuoteRequest request)
    {
        var item = itemRepository.GetById(request.ItemId);
        if (item is null || !KeyComparer.Equals(item.Category, request.ItemCategory))
            return null;

        var now = DateTime.UtcNow;
        var valuation = await BuildValuationAsync(request, item);
        var sample = await FindMarketSampleAsync(request, valuation.Signature, now);
        var sellPrice = Math.Max(0, item.SaleValue);
        var suggestedUnitPrice = Math.Max(sellPrice, ApplyValuationFactor(sellPrice, valuation.RollQualityScore));
        var referenceUnitPrice = sample.Prices.Count > 0
            ? Median(sample.Prices)
            : null;

        var confidence = ResolveConfidence(sample.Prices.Count);
        var allowed = ResolveAllowedRange(sellPrice, suggestedUnitPrice, sample.Prices, confidence);
        var judgement = ResolveJudgement(request.UnitPrice, sellPrice, allowed.Min, allowed.Max, confidence);

        return new MarketPriceQuoteResponse
        {
            ItemCategory = request.ItemCategory,
            ItemId = request.ItemId,
            InstanceType = request.InstanceType,
            InstanceId = request.InstanceId,
            SellPrice = sellPrice,
            SuggestedUnitPrice = suggestedUnitPrice,
            ReferenceUnitPrice = referenceUnitPrice,
            SampleCount = sample.Prices.Count,
            ReferenceScope = sample.Scope,
            Confidence = confidence,
            AllowedMinUnitPrice = allowed.Min,
            AllowedMaxUnitPrice = allowed.Max,
            Judgement = judgement,
            ValuationSignature = valuation.Signature,
            RollQualityScore = valuation.RollQualityScore,
            RollQualityBucket = valuation.RollQualityBucket,
            EvaluatedAt = now,
        };
    }

    private async Task<MarketValuation> BuildValuationAsync(MarketPriceQuoteRequest request, ItemResponse item)
    {
        if (!request.InstanceId.HasValue || string.IsNullOrWhiteSpace(request.InstanceType))
        {
            return new MarketValuation(
                $"{request.ItemCategory}|{request.ItemId}|STACK",
                null,
                null
            );
        }

        if (KeyComparer.Equals(request.InstanceType, "EQUIPMENT"))
        {
            var equipment = await dbContext.EquipmentInstances
                .AsNoTracking()
                .FirstOrDefaultAsync(e => e.EquipmentInstanceId == request.InstanceId.Value && !e.IsDeleted);
            if (equipment is null)
                return new MarketValuation($"{request.ItemCategory}|{request.ItemId}|EQUIPMENT|missing", null, null);

            var rolls = await dbContext.EquipmentInstanceStatRolls
                .AsNoTracking()
                .Where(roll => roll.EquipmentInstanceId == equipment.EquipmentInstanceId)
                .ToListAsync();
            var quality = CalculateEquipmentRollQuality(item, rolls);
            var bucket = ToBucket(quality);
            var signature = string.Join('|',
                request.ItemCategory,
                request.ItemId,
                "EQUIPMENT",
                equipment.EnhanceLevel,
                equipment.TranscendenceRank,
                equipment.RuneMaxSlots,
                bucket);

            return new MarketValuation(signature, quality, bucket);
        }

        if (KeyComparer.Equals(request.InstanceType, "RUNE"))
        {
            var rune = await dbContext.RuneInstances
                .AsNoTracking()
                .FirstOrDefaultAsync(r => r.RuneInstanceId == request.InstanceId.Value && !r.IsDeleted);
            if (rune is null)
                return new MarketValuation($"{request.ItemCategory}|{request.ItemId}|RUNE|missing", null, null);

            var rolls = await dbContext.RuneInstanceStatRolls
                .AsNoTracking()
                .Where(roll => roll.RuneInstanceId == rune.RuneInstanceId && !roll.IsDeleted)
                .ToListAsync();
            var quality = CalculateRuneRollQuality(rolls);
            var bucket = ToBucket(quality);
            var signature = string.Join('|',
                request.ItemCategory,
                request.ItemId,
                "RUNE",
                bucket);

            return new MarketValuation(signature, quality, bucket);
        }

        return new MarketValuation(
            $"{request.ItemCategory}|{request.ItemId}|{request.InstanceType}",
            null,
            null
        );
    }

    private async Task<MarketPriceSample> FindMarketSampleAsync(
        MarketPriceQuoteRequest request,
        string? valuationSignature,
        DateTime now
    )
    {
        var from = now.AddDays(-30);
        if (!string.IsNullOrWhiteSpace(valuationSignature))
        {
            var exact = await dbContext.MarketTransactions
                .AsNoTracking()
                .Where(transaction => transaction.CompletedAt >= from
                    && transaction.ValuationSignature == valuationSignature)
                .Select(transaction => transaction.UnitPrice)
                .ToListAsync();
            if (exact.Count >= 5)
                return new MarketPriceSample("EXACT_SIGNATURE", exact);
        }

        var itemPrices = await dbContext.MarketTransactions
            .AsNoTracking()
            .Where(transaction => transaction.CompletedAt >= from
                && transaction.ItemCategory == request.ItemCategory
                && transaction.ItemId == request.ItemId)
            .Select(transaction => transaction.UnitPrice)
            .ToListAsync();

        return itemPrices.Count > 0
            ? new MarketPriceSample("ITEM_ONLY", itemPrices)
            : new MarketPriceSample("SUGGESTED_ONLY", []);
    }

    private static decimal? CalculateEquipmentRollQuality(
        ItemResponse item,
        IEnumerable<Data.Entities.EquipmentInstanceStatRollEntity> rolls
    )
    {
        var masterRanges = item.Equipment?.Stats
            .Where(stat => !string.IsNullOrWhiteSpace(stat.Status) && stat.Value is not null)
            .ToDictionary(
                stat => stat.Status!,
                stat => (Min: ParseDecimal(stat.Value!.Min), Max: ParseDecimal(stat.Value!.Max)),
                KeyComparer);

        if (masterRanges is null || masterRanges.Count == 0)
            return null;

        var normalized = new List<decimal>();
        foreach (var roll in rolls)
        {
            if (!masterRanges.TryGetValue(roll.Status, out var range))
                continue;

            var actual = Average(ParseDecimal(roll.RandomMin), ParseDecimal(roll.RandomMax));
            if (!actual.HasValue || !range.Min.HasValue || !range.Max.HasValue || range.Max == range.Min)
                continue;

            normalized.Add(Clamp((actual.Value - range.Min.Value) / (range.Max.Value - range.Min.Value) * 100m, 0m, 100m));
        }

        return normalized.Count == 0 ? null : Math.Round(normalized.Average(), 4);
    }

    private static decimal? CalculateRuneRollQuality(IEnumerable<Data.Entities.RuneInstanceStatRollEntity> rolls)
    {
        var values = rolls
            .Select(roll => ParseDecimal(roll.RandomValue))
            .Where(value => value.HasValue)
            .Select(value => Math.Abs(value!.Value))
            .ToList();
        if (values.Count == 0)
            return null;

        var max = values.Max();
        if (max <= 0)
            return null;

        return Math.Round(values.Select(value => Clamp(value / max * 100m, 0m, 100m)).Average(), 4);
    }

    private static long ApplyValuationFactor(long sellPrice, decimal? rollQualityScore)
    {
        if (!rollQualityScore.HasValue)
            return sellPrice;

        var factor = 1m + rollQualityScore.Value / 100m;
        return (long)Math.Ceiling(sellPrice * factor);
    }

    private static string ResolveConfidence(int sampleCount)
    {
        if (sampleCount >= 20)
            return "HIGH";
        return sampleCount >= 5 ? "MEDIUM" : "LOW";
    }

    private static (long Min, long Max) ResolveAllowedRange(
        long sellPrice,
        long suggestedPrice,
        IReadOnlyList<long> prices,
        string confidence
    )
    {
        if (prices.Count == 0 || confidence == "LOW")
            return (sellPrice, Math.Max(sellPrice * 50L, suggestedPrice * 5L));

        var median = Median(prices) ?? suggestedPrice;
        var p25 = Percentile(prices, 0.25m);
        var p75 = Percentile(prices, 0.75m);
        var iqr = p75 - p25;

        if (confidence == "HIGH")
        {
            return (
                Math.Max(sellPrice, (long)Math.Floor(Math.Max(median * 0.50m, p25 - 1.5m * iqr))),
                (long)Math.Ceiling(Math.Min(median * 3.00m, p75 + 2.0m * iqr))
            );
        }

        return (
            Math.Max(sellPrice, (long)Math.Floor(Math.Max(median * 0.40m, p25 - 2.0m * iqr))),
            (long)Math.Ceiling(Math.Min(median * 4.00m, p75 + 3.0m * iqr))
        );
    }

    private static string ResolveJudgement(long? unitPrice, long sellPrice, long allowedMin, long allowedMax, string confidence)
    {
        if (!unitPrice.HasValue)
            return "ALLOW";
        if (sellPrice <= 0)
            return "BLOCK_BELOW_SELL_VALUE";
        if (unitPrice.Value < sellPrice)
            return "BLOCK_BELOW_SELL_VALUE";
        if (unitPrice.Value < allowedMin || unitPrice.Value > allowedMax)
            return confidence == "LOW" ? "LOW_CONFIDENCE_ALLOW" : "BLOCK_OUT_OF_MARKET_RANGE";

        return "ALLOW";
    }

    private static long? Median(IReadOnlyList<long> values)
    {
        if (values.Count == 0)
            return null;

        var sorted = values.Order().ToArray();
        var middle = sorted.Length / 2;
        return sorted.Length % 2 == 0
            ? (sorted[middle - 1] + sorted[middle]) / 2L
            : sorted[middle];
    }

    private static decimal Percentile(IReadOnlyList<long> values, decimal percentile)
    {
        var sorted = values.Order().Select(value => (decimal)value).ToArray();
        if (sorted.Length == 0)
            return 0m;
        if (sorted.Length == 1)
            return sorted[0];

        var index = (sorted.Length - 1) * percentile;
        var lower = (int)Math.Floor(index);
        var upper = (int)Math.Ceiling(index);
        if (lower == upper)
            return sorted[lower];

        return sorted[lower] + (sorted[upper] - sorted[lower]) * (index - lower);
    }

    private static decimal? Average(decimal? left, decimal? right)
    {
        if (left.HasValue && right.HasValue)
            return (left.Value + right.Value) / 2m;
        return left ?? right;
    }

    private static decimal? ParseDecimal(string? value)
    {
        if (string.IsNullOrWhiteSpace(value))
            return null;

        var normalized = value.Trim().TrimEnd('%');
        return decimal.TryParse(normalized, NumberStyles.Number, CultureInfo.InvariantCulture, out var result)
            ? result
            : null;
    }

    private static decimal Clamp(decimal value, decimal min, decimal max)
        => Math.Min(max, Math.Max(min, value));

    private static string? ToBucket(decimal? score)
    {
        if (!score.HasValue)
            return null;
        return score.Value switch
        {
            < 20m => "D",
            < 40m => "C",
            < 60m => "B",
            < 80m => "A",
            _ => "S",
        };
    }

    private sealed record MarketValuation(string? Signature, decimal? RollQualityScore, string? RollQualityBucket);

    private sealed record MarketPriceSample(string Scope, IReadOnlyList<long> Prices);
}
