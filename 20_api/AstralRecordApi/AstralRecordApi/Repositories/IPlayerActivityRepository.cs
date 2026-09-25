using AstralRecordApi.Models;

namespace AstralRecordApi.Repositories;

public interface IPlayerActivityRepository
{
    Task<PlayerActivityBatchResponse> RecordBatchAsync(PlayerActivityBatchRequest request);
    Task<PagedPlayerActivityResponse<SameIpActivityResponse>> GetSameIpAsync(PlayerActivityQuery query);
    Task<PagedPlayerActivityResponse<PlayerTradeActivityResponse>> GetTradesAsync(PlayerActivityQuery query);
    Task<PagedPlayerActivityResponse<DungeonClearActivityResponse>> GetDungeonsAsync(PlayerActivityQuery query);
    Task<IReadOnlyList<ActivityTargetSuggestionResponse>> GetDungeonSuggestionsAsync(PlayerActivityQuery query);
    Task<PagedPlayerActivityResponse<DungeonPlayerSummaryResponse>> GetDungeonPlayersAsync(PlayerActivityQuery query);
    Task<PagedPlayerActivityResponse<BossClearActivityResponse>> GetBossesAsync(PlayerActivityQuery query);
    Task<IReadOnlyList<ActivityTargetSuggestionResponse>> GetBossSuggestionsAsync(PlayerActivityQuery query);
    Task<PagedPlayerActivityResponse<BossPlayerSummaryResponse>> GetBossPlayersAsync(PlayerActivityQuery query);
    Task<PagedPlayerActivityResponse<UserActivityEventResponse>> GetEventsAsync(PlayerActivityQuery query);
    Task<PagedPlayerActivityResponse<MobRankingResponse>> GetMobsAsync(PlayerActivityQuery query);
    Task<PagedPlayerActivityResponse<MobPlayerSummaryResponse>> GetMobPlayersAsync(string mobId, PlayerActivityQuery query);
    Task<PagedPlayerActivityResponse<MobPlayerDeathResponse>> GetMobDeathsAsync(string mobId, PlayerActivityQuery query);
}
