using AstralRecordWeb.Models;
using AstralRecordWeb.Services;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;

namespace AstralRecordWeb.Pages.Admin.History;

[Authorize(Policy = "WebAdminOnly")]
[ResponseCache(NoStore = true, Location = ResponseCacheLocation.None)]
public sealed class MobsModel(ActivityHistoryApiClient api) : HistoryPageModel
{
    [BindProperty(SupportsGet = true)] public string? MobId { get; set; }
    [BindProperty(SupportsGet = true)] public string? View { get; set; } = "ranking";
    public PagedPlayerActivityResponse<MobRankingResponse>? Results { get; private set; }
    public PagedPlayerActivityResponse<MobPlayerSummaryResponse>? Players { get; private set; }
    public PagedPlayerActivityResponse<MobPlayerDeathResponse>? Deaths { get; private set; }
    public static string FormatMobName(string mobName) => MinecraftTextFormatter.ToHtml(mobName);

    public async Task<IActionResult> OnGetAsync(CancellationToken ct)
    {
        if (!Prepare(out var actor)) return Page();
        MobId = MobId?.Trim();
        if (MobId?.Length > 128) return BadRequest();
        var endpoint = "mobs/" + Uri.EscapeDataString(MobId ?? string.Empty);
        if (!string.IsNullOrEmpty(MobId) && View == "players")
        {
            var result = await api.GetAsync<PagedPlayerActivityResponse<MobPlayerSummaryResponse>>(endpoint + "/players", actor, FromUtc, ToUtc, PageNumber, Query, ct);
            if (result.Status is System.Net.HttpStatusCode.Forbidden or System.Net.HttpStatusCode.Unauthorized) return Forbid();
            Players = result.Value;
            if (!result.Succeeded) ErrorMessage = "モブの被害者別集計を取得できません。時間をおいて再試行してください。";
        }
        else if (!string.IsNullOrEmpty(MobId) && View == "deaths")
        {
            var result = await api.GetAsync<PagedPlayerActivityResponse<MobPlayerDeathResponse>>(endpoint + "/kills", actor, FromUtc, ToUtc, PageNumber, Query, ct);
            if (result.Status is System.Net.HttpStatusCode.Forbidden or System.Net.HttpStatusCode.Unauthorized) return Forbid();
            Deaths = result.Value;
            if (!result.Succeeded) ErrorMessage = "モブのプレイヤー撃破記録を取得できません。時間をおいて再試行してください。";
        }
        else
        {
            View = "ranking";
            var result = await api.GetAsync<PagedPlayerActivityResponse<MobRankingResponse>>("mobs", actor, FromUtc, ToUtc, PageNumber, Query, ct, ("mobId", MobId));
            if (result.Status is System.Net.HttpStatusCode.Forbidden or System.Net.HttpStatusCode.Unauthorized) return Forbid();
            Results = result.Value;
            if (!result.Succeeded) ErrorMessage = "モブ撃破ランキングを取得できません。時間をおいて再試行してください。";
        }
        return Page();
    }
}
