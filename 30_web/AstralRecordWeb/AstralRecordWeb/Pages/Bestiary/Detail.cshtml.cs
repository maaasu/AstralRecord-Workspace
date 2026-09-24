using System.Net;
using System.Security.Claims;
using AstralRecordWeb.Models;
using AstralRecordWeb.Services;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;

namespace AstralRecordWeb.Pages.Bestiary;

[Authorize]
[ResponseCache(NoStore = true, Location = ResponseCacheLocation.None)]
public sealed class DetailModel(BestiaryApiClient bestiary) : PageModel
{
    [BindProperty(SupportsGet = true)] public Guid? AccountId { get; set; }
    public WebBestiaryDetailResponse? Record { get; private set; }
    public Guid ViewerUserUuid { get; private set; }
    public string? ErrorMessage { get; private set; }

    public async Task<IActionResult> OnGetAsync(string mobId, CancellationToken ct)
    {
        if (!Guid.TryParse(User.FindFirstValue(ClaimTypes.NameIdentifier), out var viewer)) return Challenge();
        ViewerUserUuid = viewer;
        if (!ModelState.IsValid || string.IsNullOrWhiteSpace(mobId) || mobId.Length > 128) return BadRequest();
        var result = await bestiary.GetDetailAsync(viewer, AccountId, mobId, ct);
        if (result.Status == HttpStatusCode.NotFound) return NotFound();
        if (!result.Succeeded) ErrorMessage = "モブの記録を取得できませんでした。時間をおいて再読み込みしてください。";
        else { Record = result.Value!; AccountId = Record.AccountId; }
        return Page();
    }
}
