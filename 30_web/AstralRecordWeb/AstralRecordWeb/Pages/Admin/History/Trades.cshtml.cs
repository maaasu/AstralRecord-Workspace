using AstralRecordWeb.Models;
using AstralRecordWeb.Services;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;

namespace AstralRecordWeb.Pages.Admin.History;

[Authorize(Policy = "WebAdminOnly")]
[ResponseCache(NoStore = true, Location = ResponseCacheLocation.None)]
public sealed class TradesModel(ActivityHistoryApiClient api) : HistoryPageModel
{
    [BindProperty(SupportsGet = true)] public Guid? UserUuid { get; set; }
    [BindProperty(SupportsGet = true)] public Guid? OtherUserUuid { get; set; }
    [BindProperty(SupportsGet = true)] public Guid? AccountId { get; set; }
    public PagedPlayerActivityResponse<PlayerTradeActivityResponse>? Results { get; private set; }
    public async Task<IActionResult> OnGetAsync(CancellationToken ct)
    {
        if (!Prepare(out var actor)) return Page();
        var result = await api.GetAsync<PagedPlayerActivityResponse<PlayerTradeActivityResponse>>("trades", actor, FromUtc, ToUtc, PageNumber, Query, ct,
            ("userUuid", UserUuid?.ToString()), ("otherUserUuid", OtherUserUuid?.ToString()), ("accountId", AccountId?.ToString()));
        if (result.Status is System.Net.HttpStatusCode.Forbidden or System.Net.HttpStatusCode.Unauthorized) return Forbid();
        Results = result.Value;
        if (!result.Succeeded) ErrorMessage = "トレード履歴を取得できません。時間をおいて再試行してください。";
        return Page();
    }
}
