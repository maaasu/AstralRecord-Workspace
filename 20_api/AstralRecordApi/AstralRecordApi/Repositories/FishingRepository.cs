using AstralRecordApi.Data;
using AstralRecordApi.Models;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordApi.Repositories;

/// <summary>MasterDataDB に同期済みの釣りマスタを取得する。</summary>
public sealed class FishingRepository(MasterDataDbContext db) : IFishingRepository
{
    public IReadOnlyList<FishingFishResponse> GetFish() => List<FishingFishResponse>("fishing_fish");
    public FishingFishResponse? GetFish(string id) => One<FishingFishResponse>("fishing_fish", id);
    public IReadOnlyList<FishingAreaResponse> GetAreas() => List<FishingAreaResponse>("fishing_area");
    public FishingAreaResponse? GetArea(string id) => One<FishingAreaResponse>("fishing_area", id);
    public IReadOnlyList<FishingRulesResponse> GetRules() => List<FishingRulesResponse>("fishing_rules");
    public FishingRulesResponse? GetRules(string id) => One<FishingRulesResponse>("fishing_rules", id);

    private IReadOnlyList<T> List<T>(string masterType)
        => db.Entries.AsNoTracking()
            .Where(entry => !entry.IsDeleted && entry.MasterType == masterType)
            .OrderBy(entry => entry.MasterId)
            .Select(entry => entry.PayloadJson)
            .ToArray()
            .Select(MasterDataPayloadJson.Deserialize<T>)
            .OfType<T>()
            .ToArray();

    private T? One<T>(string masterType, string id)
    {
        var payload = db.Entries.AsNoTracking()
            .Where(entry => !entry.IsDeleted && entry.MasterType == masterType && entry.MasterId == id)
            .Select(entry => entry.PayloadJson)
            .FirstOrDefault();
        return payload is null ? default : MasterDataPayloadJson.Deserialize<T>(payload);
    }
}
