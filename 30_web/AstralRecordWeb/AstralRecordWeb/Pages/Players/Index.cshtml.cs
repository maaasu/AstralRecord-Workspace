using System.Security.Claims;
using AstralRecordWeb.Models;
using AstralRecordWeb.Services;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;

namespace AstralRecordWeb.Pages.Players;

[Authorize]
[ResponseCache(NoStore = true, Location = ResponseCacheLocation.None)]
public class IndexModel(PlayerProfileApiClient profiles, IAuthorizationService authorization) : PageModel
{
    [BindProperty(SupportsGet = true)] public string? Mcid { get; set; }
    [BindProperty(SupportsGet = true)] public string? ClassId { get; set; }
    [BindProperty(SupportsGet = true)] public string Sort { get; set; } = "level_desc";
    [BindProperty(SupportsGet = true)] public int PageNumber { get; set; } = 1;
    [BindProperty(SupportsGet = true)] public bool IncludePrivate { get; set; }
    public bool IsAdmin { get; private set; }
    public WebPlayerProfileSearchResponse? Results { get; private set; }
    public string? ErrorMessage { get; private set; }
    public int TotalPages => Results is null ? 0 : (int)Math.Ceiling(Results.TotalCount / (double)Results.PageSize);

    public async Task<IActionResult> OnGetAsync(CancellationToken ct)
    {
        IsAdmin = (await authorization.AuthorizeAsync(User, null, "WebAdminOnly")).Succeeded;
        if (IncludePrivate && !IsAdmin) return Forbid();
        Guid? viewer = Guid.TryParse(User.FindFirstValue(ClaimTypes.NameIdentifier), out var id) ? id : null;
        PageNumber = Math.Clamp(PageNumber, 1, 100000);
        Sort = Sort == "level_asc" ? "level_asc" : "level_desc";
        Mcid = Mcid?.Trim();
        if (Mcid?.Length > 64 || ClassId?.Length > 128) return BadRequest();
        var result = await profiles.SearchAsync(viewer, Mcid, ClassId, Sort, PageNumber, IncludePrivate, ct);
        if (result.Status == System.Net.HttpStatusCode.Forbidden) return Forbid();
        Results = result.Value;
        if (!result.Succeeded) ErrorMessage = "プレイヤー一覧を取得できませんでした。時間をおいて再読み込みしてください。";
        return Page();
    }
}
