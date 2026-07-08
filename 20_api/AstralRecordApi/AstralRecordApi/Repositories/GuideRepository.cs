using AstralRecordApi.Data;
using AstralRecordApi.Models;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordApi.Repositories;

/// <summary>MasterDataDB の <c>master_type = guide</c> からガイドマスターを取得します。</summary>
public class GuideRepository(MasterDataDbContext dbContext) : IGuideRepository
{
    private const string MasterTypeGuide = "guide";

    public IReadOnlyList<GuideResponse> GetAll()
    {
        var payloads = dbContext.Entries
            .AsNoTracking()
            .Where(entry => !entry.IsDeleted && entry.MasterType == MasterTypeGuide)
            .OrderBy(entry => entry.MasterId)
            .Select(entry => entry.PayloadJson)
            .ToArray();

        return payloads
            .Select(MasterDataPayloadJson.Deserialize<GuideResponse>)
            .Where(guide => guide is not null)
            .Select(guide => guide!)
            .OrderBy(guide => CategoryOrder(guide.Category))
            .ThenBy(guide => guide.DisplayOrder)
            .ThenBy(guide => guide.Id)
            .ToArray();
    }

    public GuideResponse? GetById(string guideId)
    {
        var payload = dbContext.Entries
            .AsNoTracking()
            .Where(entry => !entry.IsDeleted
                && entry.MasterType == MasterTypeGuide
                && entry.MasterId == guideId)
            .Select(entry => entry.PayloadJson)
            .FirstOrDefault();

        return payload is null
            ? null
            : MasterDataPayloadJson.Deserialize<GuideResponse>(payload);
    }

    private static int CategoryOrder(string? category)
    {
        return (category ?? string.Empty).Trim().ToLowerInvariant() switch
        {
            "beginner" => 10,
            "equipment" => 20,
            "skill" => 30,
            "world" => 40,
            _ => 100,
        };
    }
}
