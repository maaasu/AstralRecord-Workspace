using System.Security.Claims;
using AstralRecordWeb.Authorization;
using AstralRecordWeb.Models;
using AstralRecordWeb.Services;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Authentication.Cookies;
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
public class LoginSettingsModel(WebAuthApiClient api, TimeProvider clock) : PageModel
{
    public WebCredentialState? Credentials { get; private set; }
    public bool HasRecentCode => WebSession.RecentCodeTime(User, clock).HasValue;
    [BindProperty] public string? CurrentPassword { get; set; }
    [BindProperty] public string? NewPassword { get; set; }
    [BindProperty] public string? ConfirmPassword { get; set; }
    [TempData] public string? StatusMessage { get; set; }

    public async Task<IActionResult> OnGetAsync(CancellationToken ct)
    {
        if (!Guid.TryParse(User.FindFirstValue(ClaimTypes.NameIdentifier), out var uuid)) return Challenge();
        Credentials = await api.GetCredentialsAsync(uuid, ct);
        if (Credentials is null) ModelState.AddModelError(string.Empty, "ログイン設定を取得できませんでした。時間をおいて再度お試しください。");
        return Page();
    }

    public async Task<IActionResult> OnPostAsync(string action, CancellationToken ct)
    {
        if (!Guid.TryParse(User.FindFirstValue(ClaimTypes.NameIdentifier), out var uuid) ||
            !Guid.TryParse(User.FindFirstValue(WebSession.VersionClaim), out var version)) return Challenge();
        var current = CurrentPassword;
        var password = NewPassword;
        var confirmation = ConfirmPassword;
        CurrentPassword = NewPassword = ConfirmPassword = null;
        ModelState.Remove(nameof(CurrentPassword));
        ModelState.Remove(nameof(NewPassword));
        ModelState.Remove(nameof(ConfirmPassword));

        if (action is not ("enable" or "change" or "disable")) return BadRequest();
        if (action == "enable" && !HasRecentCode)
            ModelState.AddModelError(string.Empty, "有効化にはMinecraftでの本人確認が必要です。");
        if (!HasRecentCode && string.IsNullOrEmpty(current))
            ModelState.AddModelError(string.Empty, "現在のパスワードを入力するか、Minecraftで本人確認をしてください。");
        if (current?.Length > 256) ModelState.AddModelError(string.Empty, "現在のパスワードを確認してください。");
        if (action != "disable" && (string.IsNullOrEmpty(password) || password.Length > 256 || password != confirmation))
            ModelState.AddModelError(string.Empty, "新しいパスワードを確認用と同じ内容で入力してください。");
        if (!ModelState.IsValid) return await OnGetAsync(ct);

        // 本人UUID、失効世代、コード認証日時はフォームではなく保護Cookieを正本とする。
        var result = await api.UpdateCredentialsAsync(uuid, new WebCredentialUpdateRequest
        {
            SessionVersion = version,
            Action = action,
            CurrentPassword = current,
            NewPassword = action == "disable" ? null : password,
            CodeAuthenticationProof = HasRecentCode ? User.FindFirstValue(WebSession.CodeProofClaim) : null,
        }, ct);
        if (result.Stale)
        {
            await HttpContext.SignOutAsync(CookieAuthenticationDefaults.AuthenticationScheme);
            return RedirectToPage("/Login");
        }
        if (result.State is not { } state || state.SessionVersion == Guid.Empty)
        {
            ModelState.AddModelError(string.Empty, result.Unavailable
                ? "設定の更新結果を確認できませんでした。再ログイン後に設定を確認してください。"
                : "設定を更新できませんでした。本人確認・現在のパスワード・新しいパスワードの条件を確認してください。試行回数の制限中は時間をおいてお試しください。");
            return await OnGetAsync(ct);
        }
        await WebSession.RenewVersionAsync(HttpContext, state);
        StatusMessage = state.Enabled
            ? "ログイン設定を保存しました。他の端末はログアウトされます。"
            : "ID・パスワードでのログインを無効にしました。他の端末はログアウトされます。ログインIDは引き続きあなた専用に保持されます。";
        return RedirectToPage();
    }
}
