using System.Text.Json.Nodes;
using AstralRecordApi.Data;
using AstralRecordApi.Models;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordApi.Repositories;

/// <summary>
/// MasterDataDB の <c>master_data_entry</c>（<c>master_type = item</c>）から
/// アイテムマスタを取得する。
/// </summary>
public class ItemRepository(MasterDataDbContext dbContext) : IItemRepository
{
    private const string MasterType = "item";
    private static readonly StringComparer KeyComparer = StringComparer.OrdinalIgnoreCase;

    public IReadOnlyList<ItemSummaryResponse> GetAllSummaries()
        => dbContext.Entries
            .AsNoTracking()
            .Where(entry => !entry.IsDeleted && entry.MasterType == MasterType)
            .OrderBy(entry => entry.Category)
            .ThenBy(entry => entry.MasterId)
            .Select(entry => new ItemSummaryResponse
            {
                Id = entry.MasterId,
                Category = entry.Category ?? string.Empty,
            })
            .ToArray();

    public ItemResponse? GetById(string itemId)
    {
        var entry = dbContext.Entries
            .AsNoTracking()
            .Where(entry => !entry.IsDeleted
                && entry.MasterType == MasterType
                && entry.MasterId == itemId)
            .Select(entry => new { entry.PayloadJson, entry.Category })
            .FirstOrDefault();

        if (entry?.PayloadJson is null)
            return null;

        // category は YAML 本文に含まれず、Seeder がサブディレクトリ名から
        // master_data_entry.category 列に書き込んでいるため、ここで JSON にマージする。
        var node = JsonNode.Parse(entry.PayloadJson)?.AsObject();
        if (node is null)
            return null;
        node["category"] = entry.Category ?? string.Empty;
        return MasterDataPayloadJson.Deserialize<ItemResponse>(node.ToJsonString());
    }
}
