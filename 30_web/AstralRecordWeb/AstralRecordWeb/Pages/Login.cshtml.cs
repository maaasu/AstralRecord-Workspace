using AstralRecordWeb.Authorization;
using AstralRecordWeb.Models;
using AstralRecordWeb.Services;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;
using Microsoft.AspNetCore.RateLimiting;

namespace AstralRecordWeb.Pages;

[AllowAnonymous]
[EnableRateLimiting("WebAuthentication")]
[ResponseCache(NoStore = true, Location = ResponseCacheLocation.None)]
[RequestSizeLimit(16384)]
[RequestFormLimits(ValueLengthLimit = 1024, ValueCountLimit = 16)]
public class LoginModel(WebAuthApiClient webAuthApiClient) : PageModel
{
    [BindProperty] public string? LoginCode { get; set; }
    [BindProperty] public string? LoginId { get; set; }
    [BindProperty] public string? Password { get; set; }
    public bool PasswordMode { get; private set; }

    public void OnGet() { }

    public async Task<IActionResult> OnPostAsync(CancellationToken ct)
    {
        if (!ModelState.IsValid || string.IsNullOrWhiteSpace(LoginCode) || LoginCode.Length > 32)
        {
            ModelState.AddModelError(string.Empty, "ログインコードを入力してください。");
            return Page();
        }
        return await CompleteAsync(await webAuthApiClient.ConsumeAsync(LoginCode, ct), true);
    }

    public async Task<IActionResult> OnPostPasswordAsync(CancellationToken ct)
    {
        PasswordMode = true;
        var password = Password;
        Password = null;
        ModelState.Remove(nameof(Password));
        if (!ModelState.IsValid || string.IsNullOrWhiteSpace(LoginId) || LoginId.Length > 64 || string.IsNullOrEmpty(password) || password.Length > 256)
        {
            ModelState.AddModelError(string.Empty, "ログインIDとパスワードを入力してください。");
            return Page();
        }
        return await CompleteAsync(await webAuthApiClient.LoginWithPasswordAsync(LoginId, password, ct), false);
    }

    private async Task<IActionResult> CompleteAsync(WebLoginChallengeConsumeResult result, bool code)
    {
        if (result.Status == WebLoginChallengeConsumeStatus.ServiceUnavailable)
            ModelState.AddModelError(string.Empty, "認証APIに接続できませんでした。しばらくしてからもう一度お試しください。");
        else if (result.Status != WebLoginChallengeConsumeStatus.Success || result.Response is not { SessionVersion: var version } session || version == Guid.Empty)
            ModelState.AddModelError(string.Empty, code ? "ログインコードが無効、期限切れ、または使用済みです。" : "ログインIDまたはパスワードが正しくないか、試行回数の制限中です。");
        else
        {
            await WebSession.SignInAsync(HttpContext, session, code ? session.CodeAuthenticatedAt : null);
            return RedirectToPage("/MyPage");
        }
        return Page();
    }
}
