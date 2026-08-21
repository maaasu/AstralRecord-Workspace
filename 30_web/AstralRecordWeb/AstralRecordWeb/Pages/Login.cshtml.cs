using System.ComponentModel.DataAnnotations;
using System.Security.Claims;
using AstralRecordWeb.Models;
using AstralRecordWeb.Services;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Authentication.Cookies;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;

namespace AstralRecordWeb.Pages;

[AllowAnonymous]
public class LoginModel(WebAuthApiClient webAuthApiClient) : PageModel
{
    private static readonly bool LoginEnabled = false;

    [BindProperty]
    [Required(ErrorMessage = "ログインコードを入力してください。")]
    [Display(Name = "ログインコード")]
    public string LoginCode { get; set; } = string.Empty;

    public void OnGet()
    {
    }

    public async Task<IActionResult> OnPostAsync(CancellationToken cancellationToken)
    {
        if (!LoginEnabled)
        {
            ModelState.AddModelError(string.Empty, "Webログインは現在未実装です。");
            return Page();
        }

        if (!ModelState.IsValid)
            return Page();

        var consumeResult = await webAuthApiClient.ConsumeAsync(LoginCode, cancellationToken);
        if (consumeResult.Status == WebLoginChallengeConsumeStatus.ServiceUnavailable)
        {
            ModelState.AddModelError(string.Empty, "認証APIに接続できませんでした。しばらくしてからもう一度お試しください。");
            return Page();
        }

        var consumed = consumeResult.Response;
        if (consumeResult.Status != WebLoginChallengeConsumeStatus.Success || consumed is null)
        {
            ModelState.AddModelError(string.Empty, "ログインコードが無効、期限切れ、または使用済みです。");
            return Page();
        }

        var claims = new List<Claim>
        {
            new(ClaimTypes.NameIdentifier, consumed.UserUuid.ToString()),
            new(ClaimTypes.Name, consumed.Mcid),
            new("permission", consumed.Permission.ToString()),
        };

        if (consumed.CurrentAccountId.HasValue)
            claims.Add(new Claim("currentAccountId", consumed.CurrentAccountId.Value.ToString()));

        foreach (var accountId in consumed.AccountIds)
            claims.Add(new Claim("accountId", accountId.ToString()));

        var identity = new ClaimsIdentity(claims, CookieAuthenticationDefaults.AuthenticationScheme);
        await HttpContext.SignInAsync(
            CookieAuthenticationDefaults.AuthenticationScheme,
            new ClaimsPrincipal(identity));

        return RedirectToPage("/MyPage");
    }
}
