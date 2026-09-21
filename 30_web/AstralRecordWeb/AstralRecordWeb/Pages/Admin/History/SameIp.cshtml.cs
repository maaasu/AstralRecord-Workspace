using AstralRecordWeb.Models;
using AstralRecordWeb.Services;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;

namespace AstralRecordWeb.Pages.Admin.History;

[Authorize(Policy = "WebAdminOnly")]
[ResponseCache(NoStore = true, Location = ResponseCacheLocation.None)]
public sealed class SameIpModel(ActivityHistoryApiClient api) : HistoryPageModel
{
    public PagedPlayerActivityResponse<SameIpActivityResponse>? Results { get; private set; }
    public async Task<IActionResult> OnGetAsync(CancellationToken ct)
    {
        if (!Prepare(out var actor)) return Page();
        var result = await api.GetAsync<PagedPlayerActivityResponse<SameIpActivityResponse>>("same-ip", actor, FromUtc, ToUtc, PageNumber, Query, ct);
        if (result.Status is System.Net.HttpStatusCode.Forbidden or System.Net.HttpStatusCode.Unauthorized) return Forbid();
        Results = result.Value;
        if (!result.Succeeded) ErrorMessage = "同IPアカウントの履歴を取得できません。時間をおいて再試行してください。";
        return Page();
    }
}
