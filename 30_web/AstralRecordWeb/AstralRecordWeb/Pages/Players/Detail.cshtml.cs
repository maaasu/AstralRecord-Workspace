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
    public WebPlayerProfileResponse? Profile { get; private set; }
    public bool IsPrivateView { get; private set; }
    public string? ErrorMessage { get; private set; }

    public async Task<IActionResult> OnGetAsync(Guid userUuid, bool includePrivate, CancellationToken ct)
    {
        if (userUuid == Guid.Empty) return NotFound();
        if (includePrivate && !(await authorization.AuthorizeAsync(User, null, "WebAdminOnly")).Succeeded) return Forbid();
        Guid? viewer = Guid.TryParse(User.FindFirstValue(ClaimTypes.NameIdentifier), out var id) ? id : null;
        var result = await profiles.GetProfileAsync(userUuid, viewer, includePrivate, ct);
        if (result.Status == System.Net.HttpStatusCode.NotFound) return NotFound();
        if (result.Status == System.Net.HttpStatusCode.Forbidden) return Forbid();
        Profile = result.Value;
        IsPrivateView = Profile is { IsPublic: false };
        if (!result.Succeeded) ErrorMessage = "プレイヤー情報を取得できませんでした。時間をおいて再読み込みしてください。";
        return Page();
    }
}
