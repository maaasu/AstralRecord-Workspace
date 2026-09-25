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
    [BindProperty(Name = "dungeonId", SupportsGet = true)] public string? DungeonSearch { get; set; }
    [BindProperty(SupportsGet = true)] public string? View { get; set; } = "runs";
    [BindProperty(SupportsGet = true)] public string? Sort { get; set; }
    public PagedPlayerActivityResponse<DungeonClearActivityResponse>? Results { get; private set; }
    public PagedPlayerActivityResponse<DungeonPlayerSummaryResponse>? Players { get; private set; }
    public async Task<IActionResult> OnGetAsync(CancellationToken ct)
    {
        if (!Prepare(out var actor)) return Page();
        if (DungeonSearch?.Length > 128) return BadRequest();
        Sort ??= View == "players" ? "count" : "recent";
        if (Sort is not ("recent" or "count" or "fastest" or "slowest")) return BadRequest();
        if (View == "players" && Sort == "recent") Sort = "count";
        if (View != "players" && Sort == "count") Sort = "recent";
        var filters = new (string, string?)[] { ("userUuid", UserUuid?.ToString()), ("accountId", AccountId?.ToString()), ("dungeonSearch", DungeonSearch?.Trim()), ("sort", Sort) };
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

    public async Task<IActionResult> OnGetSuggestionsAsync(string? term, CancellationToken ct)
    {
        var searchTerm = term?.Trim() ?? string.Empty;
        if (searchTerm.Length > 128) return BadRequest(new { message = "検索語は128文字以内で入力してください。" });
        if (searchTerm.Length < 2) return new JsonResult(new { suggestions = Array.Empty<object>() });
        if (!Prepare(out var actor)) return new JsonResult(new { message = ErrorMessage ?? "検索条件を確認してください。" }) { StatusCode = 400 };

        var result = await api.GetAsync<IReadOnlyList<ActivityTargetSuggestionResponse>>(
            "dungeons/suggestions", actor, FromUtc, ToUtc, 1, Query, ct,
            ("userUuid", UserUuid?.ToString()), ("accountId", AccountId?.ToString()), ("dungeonSearch", searchTerm));
        if (result.Status is System.Net.HttpStatusCode.Forbidden or System.Net.HttpStatusCode.Unauthorized) return Forbid();
        if (!result.Succeeded)
            return new JsonResult(new { message = "候補を取得できませんでした。" }) { StatusCode = 503 };

        var suggestions = result.Value!
            .Take(10)
            .Select(suggestion => new { id = suggestion.Id, name = suggestion.Name })
            .ToArray();
        return new JsonResult(new { suggestions });
    }
}
