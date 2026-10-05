using AstralRecordApi.Models;

namespace AstralRecordApi.Repositories;

public interface IFishingRepository
{
    IReadOnlyList<FishingFishResponse> GetFish();
    FishingFishResponse? GetFish(string id);
    IReadOnlyList<FishingAreaResponse> GetAreas();
    FishingAreaResponse? GetArea(string id);
    IReadOnlyList<FishingRulesResponse> GetRules();
    FishingRulesResponse? GetRules(string id);
}
