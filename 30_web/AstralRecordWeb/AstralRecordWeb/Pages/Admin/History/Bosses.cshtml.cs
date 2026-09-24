using AstralRecordWeb.Models;
using AstralRecordWeb.Services;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;

namespace AstralRecordWeb.Pages.Admin.History;

[Authorize(Policy = "WebAdminOnly")]
[ResponseCache(NoStore = true, Location = ResponseCacheLocation.None)]
public sealed class BossesModel(ActivityHistoryApiClient api) : HistoryPageModel
{
    [BindProperty(SupportsGet = true)] public Guid? UserUuid { get; set; }
    [BindProperty(SupportsGet = true)] public Guid? AccountId { get; set; }
    [BindProperty(SupportsGet = true)] public string? BossId { get; set; }
    [BindProperty(SupportsGet = true)] public string? View { get; set; } = "runs";
    [BindProperty(SupportsGet = true)] public string? Sort { get; set; }
    public PagedPlayerActivityResponse<BossClearActivityResponse>? Results { get; private set; }
    public PagedPlayerActivityResponse<BossPlayerSummaryResponse>? Players { get; private set; }

    public async Task<IActionResult> OnGetAsync(CancellationToken ct)
    {
        if (!Prepare(out var actor)) return Page();
        if (BossId?.Length > 128) return BadRequest();
        View = View == "players" ? "players" : "runs";
        Sort ??= View == "players" ? "count" : "recent";
        if (Sort is not ("recent" or "count" or "fastest" or "slowest")) return BadRequest();
        if (View == "players" && Sort == "recent") Sort = "count";
        if (View == "runs" && Sort == "count") Sort = "recent";
        var filters = new (string, string?)[] { ("userUuid", UserUuid?.ToString()), ("accountId", AccountId?.ToString()), ("bossId", BossId?.Trim()), ("sort", Sort) };
        if (View == "players")
        {
            var result = await api.GetAsync<PagedPlayerActivityResponse<BossPlayerSummaryResponse>>("bosses/players", actor, FromUtc, ToUtc, PageNumber, Query, ct, filters);
            if (result.Status is System.Net.HttpStatusCode.Forbidden or System.Net.HttpStatusCode.Unauthorized) return Forbid();
            Players = result.Value;
            if (!result.Succeeded) ErrorMessage = "プレイヤー別のボス攻略集計を取得できません。時間をおいて再試行してください。";
        }
        else
        {
            var result = await api.GetAsync<PagedPlayerActivityResponse<BossClearActivityResponse>>("bosses", actor, FromUtc, ToUtc, PageNumber, Query, ct, filters);
            if (result.Status is System.Net.HttpStatusCode.Forbidden or System.Net.HttpStatusCode.Unauthorized) return Forbid();
            Results = result.Value;
            if (!result.Succeeded) ErrorMessage = "ボス攻略履歴を取得できません。時間をおいて再試行してください。";
        }
        return Page();
    }
}
