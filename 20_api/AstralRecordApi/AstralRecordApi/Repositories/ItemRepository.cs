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
    private const string SkillMasterType = "skill";
    private const string SkillGemCategory = "skill_gem";
    private const string SigilCategory = "sigil";
    private const string SkillGemIdPrefix = "00_skill_gem_";
    private static readonly StringComparer KeyComparer = StringComparer.OrdinalIgnoreCase;
    private static readonly HashSet<string> SupportedCategories = new(KeyComparer)
    {
        "bundle",
        "consumable",
        "currency",
        "equipment",
        "material",
        "enhancement_material",
        "rune",
        "sigil",
    };

    public IReadOnlyList<ItemSummaryResponse> GetAllSummaries()
    {
        var items = dbContext.Entries
            .AsNoTracking()
            .Where(entry => !entry.IsDeleted
                && entry.MasterType == MasterType
                && entry.Category != null
                && SupportedCategories.Contains(entry.Category))
            .OrderBy(entry => entry.Category)
            .ThenBy(entry => entry.MasterId)
            .Select(entry => new ItemSummaryResponse
            {
                Id = entry.MasterId,
                Category = entry.Category ?? string.Empty,
            })
            .ToList();

        var skillGemIds = dbContext.Entries
            .AsNoTracking()
            .Where(entry => !entry.IsDeleted && entry.MasterType == SkillMasterType)
            .OrderBy(entry => entry.MasterId)
            .Select(entry => entry.MasterId)
            .ToArray();
        items.AddRange(skillGemIds.Select(skillId => new ItemSummaryResponse
        {
            Id = SkillGemIdPrefix + skillId,
            Category = SkillGemCategory,
        }));
        return items;
    }

    public ItemResponse? GetById(string itemId)
    {
        if (itemId.StartsWith(SkillGemIdPrefix, StringComparison.Ordinal))
            return GetSkillGem(itemId);

        var entry = dbContext.Entries
            .AsNoTracking()
            .Where(entry => entry.MasterType == MasterType
                && entry.Category != null
                && SupportedCategories.Contains(entry.Category)
                && entry.MasterId == itemId
                && (!entry.IsDeleted || entry.Category == SigilCategory))
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

    private ItemResponse? GetSkillGem(string itemId)
    {
        var skillId = itemId[SkillGemIdPrefix.Length..];
        if (string.IsNullOrWhiteSpace(skillId))
            return null;

        var payload = dbContext.Entries
            .AsNoTracking()
            .Where(entry => !entry.IsDeleted
                && entry.MasterType == SkillMasterType
                && entry.MasterId == skillId)
            .Select(entry => entry.PayloadJson)
            .FirstOrDefault();
        var skill = payload is null ? null : MasterDataPayloadJson.Deserialize<SkillResponse>(payload);
        if (skill is null)
            return null;

        return new ItemResponse
        {
            SchemaVersion = skill.SchemaVersion,
            Id = itemId,
            Category = SkillGemCategory,
            Name = skill.Name + "ジェム",
            Icon = skill.Gem.Icon ?? skill.Icon ?? "EMERALD",
            Rarity = skill.Gem.Rarity,
            SaleValue = 0,
            MaxStack = 0,
            Lore = ["&7左クリックで習得", "&8習得時にジェムを1個消費します。"],
            UnTradeable = !skill.Gem.Tradeable,
            UnSellable = !skill.Gem.Sellable,
            SkillGem = new ItemSkillGemResponse { SkillId = skill.Id },
        };
    }
}
