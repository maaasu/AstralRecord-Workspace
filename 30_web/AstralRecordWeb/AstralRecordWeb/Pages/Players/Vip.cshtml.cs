using AstralRecordWeb.Models;
using AstralRecordWeb.Services;
using Microsoft.AspNetCore.Authorization;
using System.Security.Claims;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;

namespace AstralRecordWeb.Pages.Players;

[Authorize(Policy = "WebAdminVisible")]
[ResponseCache(NoStore = true, Location = ResponseCacheLocation.None)]
public sealed class VipModel(PaidServicesApiClient paid) : PageModel
{
    public IReadOnlyList<VipSupporter> Supporters { get; private set; } = [];
    public string? ErrorMessage { get; private set; }

    public async Task<IActionResult> OnGetAsync(CancellationToken ct)
    {
        if (!Guid.TryParse(User.FindFirstValue(ClaimTypes.NameIdentifier), out var viewer)) return Challenge();
        var result = await paid.VipSupportersAsync(viewer, ct);
        if (result.Succeeded) Supporters = result.Value!;
        else ErrorMessage = "VIP一覧を取得できませんでした。時間をおいて再読み込みしてください。";
        return Page();
    }
}
