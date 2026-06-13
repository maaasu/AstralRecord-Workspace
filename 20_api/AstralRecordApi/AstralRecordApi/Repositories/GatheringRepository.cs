using AstralRecordApi.Data;
using AstralRecordApi.Models;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordApi.Repositories;

/// <summary>
/// MasterDataDB の <c>master_data_entry</c>（<c>master_type IN ('gathering.mining', 'gathering.harvesting')</c>）から
/// 採集オブジェクト master data を取得します。
/// </summary>
public class GatheringRepository(MasterDataDbContext dbContext) : IGatheringRepository
{
    private const string MasterTypeMining = "gathering.mining";
    private const string MasterTypeHarvesting = "gathering.harvesting";

    private static readonly IReadOnlyList<string> AllMasterTypes =
        [MasterTypeMining, MasterTypeHarvesting];

    public IReadOnlyList<GatheringSummaryResponse> GetAllSummaries(string? category = null)
    {
        var masterTypes = ResolveMasterTypes(category);

        var payloads = dbContext.Entries
            .AsNoTracking()
            .Where(entry => !entry.IsDeleted && masterTypes.Contains(entry.MasterType))
            .OrderBy(entry => entry.MasterType)
            .ThenBy(entry => entry.MasterId)
            .Select(entry => entry.PayloadJson)
            .ToArray();

        return payloads
            .Select(MasterDataPayloadJson.Deserialize<GatheringResponse>)
            .Where(gathering => gathering is not null)
            .Select(gathering => new GatheringSummaryResponse
            {
                Id = gathering!.Id,
                Category = gathering.Category,
                Name = gathering.Name,
                MaxHealth = gathering.MaxHealth,
                DisplayBlock = gathering.DisplayBlock,
                RequiredToolTags = gathering.RequiredToolTags,
            })
            .ToArray();
    }

    public GatheringResponse? GetById(string gatheringId)
    {
        var payload = dbContext.Entries
            .AsNoTracking()
            .Where(entry => !entry.IsDeleted
                && AllMasterTypes.Contains(entry.MasterType)
                && entry.MasterId == gatheringId)
            .Select(entry => entry.PayloadJson)
            .FirstOrDefault();

        return payload is null
            ? null
            : MasterDataPayloadJson.Deserialize<GatheringResponse>(payload);
    }

    private static IReadOnlyList<string> ResolveMasterTypes(string? category)
    {
        if (string.IsNullOrWhiteSpace(category))
            return AllMasterTypes;

        return category.Trim().ToUpperInvariant() switch
        {
            "MINING" => [MasterTypeMining],
            "HARVESTING" => [MasterTypeHarvesting],
            _ => throw new ArgumentException(
                $"Unsupported category: {category}. Expected one of MINING, HARVESTING.",
                nameof(category)),
        };
    }
}
