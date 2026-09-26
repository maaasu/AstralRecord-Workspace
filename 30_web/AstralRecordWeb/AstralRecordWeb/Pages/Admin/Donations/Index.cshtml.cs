using System.Security.Claims;
using AstralRecordWeb.Models;
using AstralRecordWeb.Services;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;

namespace AstralRecordWeb.Pages.Admin.Donations;

[Authorize(Policy = "WebAdminOnly")]
[ResponseCache(NoStore = true, Location = ResponseCacheLocation.None)]
public sealed class IndexModel(DonationApiClient api) : PageModel
{
    [BindProperty(SupportsGet = true)] public int PageNumber { get; set; } = 1;
    public DonationList? History { get; private set; }
    public string? ErrorMessage { get; private set; }
    public bool HasNext => History is { } h && (long)PageNumber * 20 < h.TotalCount;
    public async Task<IActionResult> OnGetAsync(CancellationToken ct)
    {
        if (!Guid.TryParse(User.FindFirstValue(ClaimTypes.NameIdentifier), out var actor)) return Challenge();
        PageNumber = Math.Clamp(PageNumber, 1, 1000000);
        var result = await api.ListAsync(actor, true, PageNumber, ct);
        if (result.Status == System.Net.HttpStatusCode.Forbidden) return Forbid();
        if (result.Succeeded) History = result.Value;
        else ErrorMessage = result.ErrorMessage ?? "寄付申請一覧を取得できませんでした。";
        return Page();
    }
}
