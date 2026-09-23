using System.Globalization;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordApi.Repositories;

/// <summary>本人専用の Web Mob 図鑑を、討伐記録とマスタから読み取り専用で合成します。</summary>
public sealed class WebBestiaryRepository(AstralRecordDbContext gameDb, MasterDataDbContext masterDb) : IWebBestiaryRepository
{
    private static readonly string[] MobMasterTypes = ["mob.enemy", "mob.boss"];

    public async Task<WebBestiaryListResponse?> GetListAsync(Guid viewerUserUuid, Guid? accountId)
    {
        var selection = await ResolveAccountAsync(viewerUserUuid, accountId);
        if (selection is null) return null;
        if (selection.Current is null) return Empty(selection.Accounts);

        var records = await gameDb.AccountMobRecords.AsNoTracking()
            .Where(record => record.AccountId == selection.Current.Uuid && !record.IsDeleted)
            .OrderByDescending(record => record.LastDefeatedAt).ThenBy(record => record.MobId)
            .ToListAsync();
        var mobs = await LoadMobsAsync(records.Select(record => record.MobId));
        return new WebBestiaryListResponse
        {
            CurrentAccount = MapAccount(selection.Current),
            Accounts = selection.Accounts.Select(MapAccount).ToList(),
            Mobs = records.Where(record => mobs.TryGetValue(record.MobId, out _)).Select(record =>
            {
                var mob = ResolveLevels(mobs[record.MobId])[0];
                return new WebBestiaryMobSummaryResponse
                {
                    MobId = mob.Id, Category = mob.Category, Name = StripLegacyColors(mobs[record.MobId].Name), Level = mob.Level,
                    EntityType = mob.EntityType, Icon = mob.Icon, IconTexture = mob.IconTexture,
                    DefeatCount = record.DefeatCount, LastDefeatedAt = Utc(record.LastDefeatedAt),
                };
            }).ToList(),
            TotalDefeats = records.Sum(record => record.DefeatCount),
        };
    }

    public async Task<WebBestiaryDetailResponse?> GetDetailAsync(Guid viewerUserUuid, Guid? accountId, string mobId)
    {
        var selection = await ResolveAccountAsync(viewerUserUuid, accountId);
        if (selection?.Current is null) return null;
        var normalizedId = mobId.Trim();
        var record = await gameDb.AccountMobRecords.AsNoTracking().SingleOrDefaultAsync(candidate =>
            candidate.AccountId == selection.Current.Uuid && !candidate.IsDeleted && candidate.MobId == normalizedId);
        if (record is null) return null;

        var mob = (await LoadMobsAsync([normalizedId])).GetValueOrDefault(normalizedId);
        if (mob is null) return null;
        var levels = ResolveLevels(mob);
        var items = await LoadItemsAsync(levels.SelectMany(level => level.Drops?.Items.Where(item => !item.Hidden).Select(item => item.ItemId) ?? []));
        var totalDefeats = await gameDb.AccountMobRecords.AsNoTracking()
            .Where(candidate => candidate.AccountId == selection.Current.Uuid && !candidate.IsDeleted)
            .SumAsync(candidate => (long?)candidate.DefeatCount) ?? 0L;

        return new WebBestiaryDetailResponse
        {
            CurrentAccount = MapAccount(selection.Current),
            Accounts = selection.Accounts.Select(MapAccount).ToList(),
            TotalDefeats = totalDefeats,
            Mob = MapDetail(levels[0], levels, record, items),
        };
    }

    private async Task<AccountSelection?> ResolveAccountAsync(Guid userId, Guid? requestedAccountId)
    {
        var user = await gameDb.Users.AsNoTracking().SingleOrDefaultAsync(candidate => candidate.Uuid == userId && !candidate.IsDeleted);
        if (user is null) return null;
        var accounts = await gameDb.Accounts.AsNoTracking().Where(candidate => candidate.UserId == userId && !candidate.IsDeleted)
            .OrderBy(candidate => candidate.SlotIndex).ThenBy(candidate => candidate.Uuid).ToListAsync();
        var current = requestedAccountId.HasValue
            ? accounts.SingleOrDefault(candidate => candidate.Uuid == requestedAccountId.Value)
            : user.AccountId is Guid active ? accounts.SingleOrDefault(candidate => candidate.Uuid == active) : null;
        return requestedAccountId.HasValue && current is null ? null : new AccountSelection(accounts, current);
    }

    private async Task<Dictionary<string, MobResponse>> LoadMobsAsync(IEnumerable<string> ids)
    {
        var requested = ids.Where(id => !string.IsNullOrWhiteSpace(id)).Distinct(StringComparer.Ordinal).ToArray();
        if (requested.Length == 0) return new(StringComparer.Ordinal);
        var entries = await masterDb.Entries.AsNoTracking().Where(entry => !entry.IsDeleted
                && MobMasterTypes.Contains(entry.MasterType) && requested.Contains(entry.MasterId))
            .Select(entry => entry.PayloadJson).ToListAsync();
        return entries.Select(MasterDataPayloadJson.Deserialize<MobResponse>).Where(mob => mob is not null)
            .Cast<MobResponse>().ToDictionary(mob => mob.Id, StringComparer.Ordinal);
    }

    private async Task<Dictionary<string, ItemResponse>> LoadItemsAsync(IEnumerable<string> ids)
    {
        var requested = ids.Select(NormalizeDropItemId).Where(id => id.Length > 0).Distinct(StringComparer.Ordinal).ToArray();
        if (requested.Length == 0) return new(StringComparer.Ordinal);
        var entries = await masterDb.Entries.AsNoTracking().Where(entry => !entry.IsDeleted
                && entry.MasterType == "item" && requested.Contains(entry.MasterId))
            .Select(entry => entry.PayloadJson).ToListAsync();
        return entries.Select(MasterDataPayloadJson.Deserialize<ItemResponse>).Where(item => item is not null)
            .Cast<ItemResponse>().ToDictionary(item => item.Id, StringComparer.Ordinal);
    }

    private static WebBestiaryListResponse Empty(IReadOnlyList<AccountEntity> accounts) => new()
    {
        CurrentAccount = null, Accounts = accounts.Select(MapAccount).ToList(), Mobs = [], TotalDefeats = 0,
    };

    private static WebBestiaryAccountResponse MapAccount(AccountEntity account) => new()
    {
        AccountId = account.Uuid, AccountName = account.AccountName, SlotIndex = account.SlotIndex,
    };

    private static WebBestiaryMobDetailResponse MapDetail(
        MobResponse mob, IReadOnlyList<MobResponse> levels, AccountMobRecordEntity record, IReadOnlyDictionary<string, ItemResponse> items)
    {
        return new WebBestiaryMobDetailResponse
        {
            MobId = mob.Id, Category = mob.Category, Name = StripLegacyColors(mob.Name), Title = StripOrNull(mob.Title),
            Level = mob.Level, EntityType = mob.EntityType, Icon = mob.Icon, IconTexture = mob.IconTexture,
            Lore = mob.Lore.Select(StripLegacyColors).ToList(), Variant = mob.Variant,
            BaseStats = mob.BaseStats.Where(stat => StatusTypes.TryGet(stat.Status, out _)).Select(MapStatus).ToList(),
            Drops = MapDrops(mob.Drops, items),
            Levels = levels.Select(level => MapLevel(level, items)).ToList(),
            DefeatCount = record.DefeatCount, FirstDefeatedAt = Utc(record.FirstDefeatedAt), LastDefeatedAt = Utc(record.LastDefeatedAt),
        };
    }

    private static WebBestiaryMobLevelResponse MapLevel(MobResponse mob, IReadOnlyDictionary<string, ItemResponse> items) => new()
    {
        Level = mob.Level, Name = StripLegacyColors(mob.Name), Title = StripOrNull(mob.Title),
        Icon = mob.Icon, IconTexture = mob.IconTexture, Lore = mob.Lore.Select(StripLegacyColors).ToList(),
        Variant = mob.Variant, BaseStats = mob.BaseStats.Where(stat => StatusTypes.TryGet(stat.Status, out _)).Select(MapStatus).ToList(),
        Drops = MapDrops(mob.Drops, items),
    };

    private static WebBestiaryDropsResponse MapDrops(MobDropsResponse? drops, IReadOnlyDictionary<string, ItemResponse> items) => new()
    {
        Exp = drops?.Exp ?? 0,
        Money = drops?.Money is { } money ? new WebBestiaryMoneyDropResponse { Min = money.Min, Max = money.Max } : null,
        Items = drops?.Items.Where(item => !item.Hidden).Select(item => MapDrop(item, items)).ToList() ?? [],
        HasAdditionalDrops = !string.IsNullOrWhiteSpace(drops?.LootTable),
    };

    private static WebBestiaryStatusResponse MapStatus(MobBaseStatResponse stat)
    {
        StatusTypes.TryGet(stat.Status, out var definition);
        return new WebBestiaryStatusResponse
        {
            Status = stat.Status, DisplayName = definition!.DisplayName, Value = stat.Value,
            DisplayValue = stat.Value.ToString($"F{definition.DecimalPlaces}", CultureInfo.InvariantCulture) + definition.Suffix,
        };
    }

    private static WebBestiaryDropItemResponse MapDrop(MobDropItemResponse drop, IReadOnlyDictionary<string, ItemResponse> items)
    {
        var itemId = NormalizeDropItemId(drop.ItemId);
        var item = items.GetValueOrDefault(itemId);
        return new WebBestiaryDropItemResponse
        {
            ItemId = itemId, Name = item is null ? "未登録のアイテム" : StripLegacyColors(item.Name),
            Icon = item?.Icon ?? "BARRIER", IconTexture = item?.IconTexture, Rate = drop.Rate,
            Amount = drop.Amount, LuckAffected = drop.LuckAffected,
        };
    }

    // The shared payload reader unwraps {"ref":"item:..."} but keeps its namespace.
    // Item masters are keyed by the bare ID; legacy bare-ID drops remain supported.
    private static string NormalizeDropItemId(string? value)
    {
        var reference = value?.Trim() ?? string.Empty;
        const string prefix = "item:";
        return reference.StartsWith(prefix, StringComparison.OrdinalIgnoreCase)
            ? reference[prefix.Length..].Trim() : reference;
    }

    /// <summary>有効な各レベルプロファイルを共通定義へ上書きし、最小レベル順で返します。</summary>
    private static IReadOnlyList<MobResponse> ResolveLevels(MobResponse mob)
    {
        var profiles = mob.Levels.Where(element => element.ValueKind == JsonValueKind.Object)
            .Select(element => new { Element = element, Level = ReadPositiveInt(element, "level") })
            .Where(candidate => candidate.Level.HasValue)
            .GroupBy(candidate => candidate.Level!.Value)
            .Select(group => group.First())
            .OrderBy(candidate => candidate.Level!.Value)
            .ToList();
        return profiles.Count == 0 ? [mob] : profiles.Select(profile => ResolveLevelProfile(mob, profile.Element, profile.Level!.Value)).ToList();
    }

    private static MobResponse ResolveLevelProfile(MobResponse mob, JsonElement node, int level)
    {
        return new MobResponse
        {
            SchemaVersion = mob.SchemaVersion, Id = mob.Id, Type = mob.Type, Category = mob.Category,
            Name = ReadString(node, "name") ?? mob.Name, Title = ReadString(node, "title") ?? mob.Title,
            Level = level, EntityType = mob.EntityType,
            NameVisible = ReadBool(node, "nameVisible") ?? mob.NameVisible,
            DamageImmune = mob.DamageImmune, Icon = ReadString(node, "icon") ?? mob.Icon,
            IconTexture = ReadString(node, "iconTexture") ?? mob.IconTexture,
            Lore = ReadStrings(node, "lore") ?? mob.Lore, Tags = ReadStrings(node, "tags") ?? mob.Tags,
            Skin = mob.Skin, Variant = ReadMergedJson(node, "variant", mob.Variant),
            Equipment = mob.Equipment, BaseStats = ReadStats(node, "baseStats") ?? mob.BaseStats,
            Shield = mob.Shield, Ai = mob.Ai, Interactions = mob.Interactions,
            Drops = ReadDrops(node, "drops", mob.Drops), Challenge = mob.Challenge, Levels = mob.Levels,
        };
    }

    private static IReadOnlyList<MobBaseStatResponse>? ReadStats(JsonElement element, string property)
    {
        if (!element.TryGetProperty(property, out var values) || values.ValueKind != JsonValueKind.Array) return null;
        return values.EnumerateArray().Where(value => value.ValueKind == JsonValueKind.Object)
            .Select(value => new MobBaseStatResponse
            {
                Status = ReadString(value, "status") ?? string.Empty,
                Value = value.TryGetProperty("value", out var raw) && raw.TryGetDouble(out var number) ? number : 0,
            }).ToList();
    }

    private static MobDropsResponse? ReadDrops(JsonElement element, string property, MobDropsResponse? baseline)
    {
        if (!element.TryGetProperty(property, out var drops)) return baseline;
        if (drops.ValueKind == JsonValueKind.Null) return null;
        if (drops.ValueKind != JsonValueKind.Object) return baseline;
        return MergeJson(baseline, drops);
    }

    private static T? ReadMergedJson<T>(JsonElement element, string property, T? baseline) where T : class
    {
        if (!element.TryGetProperty(property, out var raw)) return baseline;
        if (raw.ValueKind == JsonValueKind.Null) return null;
        return raw.ValueKind == JsonValueKind.Object ? MergeJson(baseline, raw) : baseline;
    }

    /// <summary>Plugin の profile merge と同じく、オブジェクトだけを再帰マージし、配列とスカラは差し替えます。</summary>
    private static T? MergeJson<T>(T? baseline, JsonElement overrideValue) where T : class
    {
        JsonNode node = baseline is null
            ? new JsonObject()
            : JsonNode.Parse(JsonSerializer.Serialize(baseline, MasterDataPayloadJson.Options))!;
        MergeObjects((JsonObject)node, JsonNode.Parse(overrideValue.GetRawText())!.AsObject());
        return JsonSerializer.Deserialize<T>(node.ToJsonString(), MasterDataPayloadJson.Options);
    }

    private static void MergeObjects(JsonObject baseline, JsonObject overrideValue)
    {
        foreach (var pair in overrideValue)
        {
            if (baseline[pair.Key] is JsonObject current && pair.Value is JsonObject nested)
                MergeObjects(current, nested);
            else
                baseline[pair.Key] = pair.Value?.DeepClone();
        }
    }
    private static string? ReadString(JsonElement element, string property) => element.TryGetProperty(property, out var raw) && raw.ValueKind == JsonValueKind.String ? raw.GetString() : null;
    private static bool? ReadBool(JsonElement element, string property) => element.TryGetProperty(property, out var raw) && raw.ValueKind is JsonValueKind.True or JsonValueKind.False ? raw.GetBoolean() : null;
    private static int? ReadPositiveInt(JsonElement element, string property) => element.TryGetProperty(property, out var raw) && raw.TryGetInt32(out var value) && value > 0 ? value : null;
    private static IReadOnlyList<string>? ReadStrings(JsonElement element, string property) => element.TryGetProperty(property, out var raw) && raw.ValueKind == JsonValueKind.Array ? raw.EnumerateArray().Where(value => value.ValueKind == JsonValueKind.String).Select(value => value.GetString() ?? string.Empty).ToList() : null;
    private static DateTime Utc(DateTime value) => DateTime.SpecifyKind(value, DateTimeKind.Utc);
    private static string? StripOrNull(string? value) => string.IsNullOrWhiteSpace(value) ? null : StripLegacyColors(value);
    private static string StripLegacyColors(string value) => Regex.Replace(value, "[&§][0-9A-FK-ORX]", string.Empty, RegexOptions.IgnoreCase);
    private sealed record AccountSelection(IReadOnlyList<AccountEntity> Accounts, AccountEntity? Current);
}
