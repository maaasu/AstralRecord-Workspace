using System.Text.Json.Nodes;
using AstralRecordApi.Data;
using AstralRecordApi.Models;
using Microsoft.EntityFrameworkCore;
using AstralRecordApi.Services;

namespace AstralRecordApi.Repositories;

/// <summary>
/// MasterDataDB の <c>master_data_entry</c>（<c>master_type = item</c>）から
/// アイテムマスタを取得する。
/// </summary>
public class ItemRepository(MasterDataDbContext dbContext, MasterDataCache? cache = null) : IItemRepository
{
    private readonly MasterDataCache masterCache = cache ?? new();
    private const string MasterType = "item";
    private const string SigilCategory = "sigil";
    private static readonly StringComparer KeyComparer = StringComparer.OrdinalIgnoreCase;
    private static readonly HashSet<string> SupportedCategories = new(KeyComparer)
    {
        "bundle",
        "consumable",
        "currency",
        "equipment",
        "material",
        "orb",
        "rune",
        "sigil",
        "pet",
        "pet_egg",
    };

    public IReadOnlyList<ItemSummaryResponse> GetAllSummaries() => GetSnapshot().Value.Summaries;
    public ItemResponse? GetById(string itemId) => GetSnapshot().Value.ById.GetValueOrDefault(itemId);
    public async Task<IReadOnlyList<ItemSummaryResponse>> GetAllSummariesAsync(CancellationToken cancellationToken = default)
        => (await GetSnapshotAsync(cancellationToken)).Value.Summaries;
    public async Task<ItemResponse?> GetByIdAsync(string id, CancellationToken cancellationToken = default)
        => (await GetSnapshotAsync(cancellationToken)).Value.ById.GetValueOrDefault(id);
    public async Task<MasterDataPageResponse<ItemResponse>> GetDetailsAsync(int offset, int limit, CancellationToken cancellationToken = default)
    {
        var snapshot = await GetSnapshotAsync(cancellationToken);
        return new(snapshot.Generation, snapshot.Value.Active.Count, snapshot.Value.Active.Skip(offset).Take(limit).ToArray());
    }

    private MasterDataCacheValue<Snapshot> GetSnapshot() => masterCache.Get(MasterType, () => ConvertEntries(Query().ToArray()));
    private Task<MasterDataCacheValue<Snapshot>> GetSnapshotAsync(CancellationToken cancellationToken)
        => masterCache.GetAsync(MasterType, async () => ConvertEntries(await Query().ToArrayAsync(cancellationToken)), cancellationToken);
    private IQueryable<Payload> Query() => dbContext.Entries.AsNoTracking()
        .Where(entry => entry.MasterType == MasterType && entry.Category != null
            && SupportedCategories.Contains(entry.Category) && (!entry.IsDeleted || entry.Category == SigilCategory))
        .OrderBy(entry => entry.Category).ThenBy(entry => entry.MasterId)
        .Select(entry => new Payload(entry.MasterId, entry.Category!, entry.PayloadJson, entry.IsDeleted));

    private static Snapshot ConvertEntries(Payload[] entries)
    {
        var active = new List<ItemResponse>();
        var summaries = new List<ItemSummaryResponse>();
        var byId = new Dictionary<string, ItemResponse>(KeyComparer);
        // 同じIDのtombstoneが残る場合、有効レコードを優先する。
        foreach (var entry in entries.OrderByDescending(entry => entry.Deleted))
        {
            var node = JsonNode.Parse(entry.Json)?.AsObject();
            if (node is null) continue;
            var name = node["name"]?.GetValue<string>()
                ?? throw new System.Text.Json.JsonException($"Item master '{entry.Id}' has no name.");
            node["category"] = entry.Category;
            var detail = MasterDataPayloadJson.Deserialize<ItemResponse>(node.ToJsonString());
            if (detail is null) continue;
            byId[entry.Id] = detail;
            if (!entry.Deleted)
            {
                active.Add(detail);
                summaries.Add(new() { Id = entry.Id, Category = entry.Category, Name = name });
            }
        }
        return new(active, summaries, byId);
    }
    private sealed record Payload(string Id, string Category, string Json, bool Deleted);
    private sealed record Snapshot(IReadOnlyList<ItemResponse> Active, IReadOnlyList<ItemSummaryResponse> Summaries,
        IReadOnlyDictionary<string, ItemResponse> ById);
}
