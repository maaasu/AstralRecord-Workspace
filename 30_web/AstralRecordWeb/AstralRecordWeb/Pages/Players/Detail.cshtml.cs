using System.Security.Claims;
using AstralRecordWeb.Models;
using AstralRecordWeb.Services;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;

namespace AstralRecordWeb.Pages.Players;

[ResponseCache(NoStore = true, Location = ResponseCacheLocation.None)]
public class DetailModel(PlayerProfileApiClient profiles, IAuthorizationService authorization) : PageModel
{
    [BindProperty(SupportsGet = true)] public string? Mcid { get; set; }
    [BindProperty(SupportsGet = true)] public string? ClassId { get; set; }
    [BindProperty(SupportsGet = true)] public string Sort { get; set; } = "level_desc";
    [BindProperty(SupportsGet = true)] public int PageNumber { get; set; } = 1;
    [BindProperty(SupportsGet = true)] public bool IncludePrivate { get; set; }
    [BindProperty(SupportsGet = true)] public Guid? AccountId { get; set; }
    public WebPlayerProfileResponse? Profile { get; private set; }
    public bool IsPrivateView { get; private set; }
    public string? ErrorMessage { get; private set; }

    public async Task<IActionResult> OnGetAsync(Guid userUuid, CancellationToken ct)
    {
        if (userUuid == Guid.Empty) return NotFound();
        if (IncludePrivate && !(await authorization.AuthorizeAsync(User, null, "WebAdminOnly")).Succeeded) return Forbid();
        Guid? viewer = Guid.TryParse(User.FindFirstValue(ClaimTypes.NameIdentifier), out var id) ? id : null;
        var result = await profiles.GetProfileAsync(userUuid, viewer, IncludePrivate, AccountId, ct);
        if (result.Status == System.Net.HttpStatusCode.NotFound) return NotFound();
        if (result.Status == System.Net.HttpStatusCode.Forbidden) return Forbid();
        Profile = result.Value;
        IsPrivateView = Profile is { IsPublic: false };
        if (!result.Succeeded) ErrorMessage = "プレイヤー情報を取得できませんでした。時間をおいて再読み込みしてください。";
        return Page();
    }
}
