using AstralRecordWeb.Models;
using AstralRecordWeb.Services;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;

namespace AstralRecordWeb.Pages.Admin.History;

[Authorize(Policy = "WebAdminOnly")]
[ResponseCache(NoStore = true, Location = ResponseCacheLocation.None)]
public sealed class DungeonsModel(ActivityHistoryApiClient api) : HistoryPageModel
{
    [BindProperty(SupportsGet = true)] public Guid? UserUuid { get; set; }
    [BindProperty(SupportsGet = true)] public Guid? AccountId { get; set; }
    [BindProperty(SupportsGet = true)] public string? DungeonId { get; set; }
    [BindProperty(SupportsGet = true)] public string? View { get; set; } = "runs";
    public PagedPlayerActivityResponse<DungeonClearActivityResponse>? Results { get; private set; }
    public PagedPlayerActivityResponse<DungeonPlayerSummaryResponse>? Players { get; private set; }
    public async Task<IActionResult> OnGetAsync(CancellationToken ct)
    {
        if (!Prepare(out var actor)) return Page();
        if (DungeonId?.Length > 128) return BadRequest();
        var filters = new (string, string?)[] { ("userUuid", UserUuid?.ToString()), ("accountId", AccountId?.ToString()), ("dungeonId", DungeonId?.Trim()) };
        if (View == "players")
        {
            var result = await api.GetAsync<PagedPlayerActivityResponse<DungeonPlayerSummaryResponse>>("dungeons/players", actor, FromUtc, ToUtc, PageNumber, Query, ct, filters);
            if (result.Status is System.Net.HttpStatusCode.Forbidden or System.Net.HttpStatusCode.Unauthorized) return Forbid();
            Players = result.Value;
            if (!result.Succeeded) ErrorMessage = "プレイヤー別の攻略集計を取得できません。時間をおいて再試行してください。";
        }
        else
        {
            View = "runs";
            var result = await api.GetAsync<PagedPlayerActivityResponse<DungeonClearActivityResponse>>("dungeons", actor, FromUtc, ToUtc, PageNumber, Query, ct, filters);
            if (result.Status is System.Net.HttpStatusCode.Forbidden or System.Net.HttpStatusCode.Unauthorized) return Forbid();
            Results = result.Value;
            if (!result.Succeeded) ErrorMessage = "ダンジョン攻略履歴を取得できません。時間をおいて再試行してください。";
        }
        return Page();
    }
}
