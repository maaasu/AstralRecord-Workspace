using System.Security.Claims;
using AstralRecordWeb.Authorization;
using AstralRecordWeb.Models;
using AstralRecordWeb.Services;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;
using Microsoft.AspNetCore.RateLimiting;

namespace AstralRecordWeb.Pages;

[Authorize]
[EnableRateLimiting("WebAuthentication")]
[ResponseCache(NoStore = true, Location = ResponseCacheLocation.None)]
[RequestSizeLimit(16384)]
[RequestFormLimits(ValueLengthLimit = 1024, ValueCountLimit = 16)]
public class ReauthenticateModel(WebAuthApiClient api) : PageModel
{
    [BindProperty] public string? LoginCode { get; set; }
    [BindProperty(SupportsGet = true)] public string? ReturnUrl { get; set; }

    public void OnGet() { }

    public async Task<IActionResult> OnPostAsync(CancellationToken ct)
    {
        if (!Guid.TryParse(User.FindFirstValue(ClaimTypes.NameIdentifier), out var uuid)) return Challenge();
        if (!ModelState.IsValid || string.IsNullOrWhiteSpace(LoginCode) || LoginCode.Length > 32)
        {
            ModelState.AddModelError(string.Empty, "ログインコードを入力してください。");
            return Page();
        }
        var result = await api.ConsumeAsync(LoginCode, ct, uuid);
        if (result.Status == WebLoginChallengeConsumeStatus.Success && result.Response is { } session &&
            session.UserUuid == uuid && session.SessionVersion != Guid.Empty &&
            session.CodeAuthenticatedAt.HasValue && !string.IsNullOrEmpty(session.CodeAuthenticationProof))
        {
            await WebSession.SignInAsync(HttpContext, session, session.CodeAuthenticatedAt);
            return LocalRedirect(Url.IsLocalUrl(ReturnUrl) ? ReturnUrl! : Url.Page("/LoginSettings")!);
        }
        ModelState.AddModelError(string.Empty, result.Status == WebLoginChallengeConsumeStatus.ServiceUnavailable
            ? "認証APIに接続できませんでした。時間をおいて再度お試しください。"
            : "本人の有効なログインコードを入力してください。期限切れ・使用済みのコードは利用できません。");
        return Page();
    }
}
