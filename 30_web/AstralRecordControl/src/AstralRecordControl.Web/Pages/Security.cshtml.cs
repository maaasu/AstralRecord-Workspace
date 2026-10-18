using System.Security.Claims;
using AstralRecordControl.Web.Authentication;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;

namespace AstralRecordControl.Web.Pages;

public sealed class SecurityModel(OwnerAuthService auth) : PageModel
{
    [BindProperty] public string Password { get; set; } = "";
    [BindProperty] public string Code { get; set; } = "";
    public string Message { get; private set; } = "";
    public void OnGet() { }
    public async Task<IActionResult> OnPostReauthenticateAsync()
    {
        Message = Password.Length <= 1024 && Code.Length <= 100 && await auth.ReauthenticateAsync(User, Password, Code) ? "再認証しました。5分間有効です。" : "再認証できませんでした。";
        return Page();
    }
    public async Task<IActionResult> OnPostLogoutAllAsync()
    {
        if (!await auth.HasRecentAuthenticationAsync(User)) { Message = "パスワードと認証コードで再認証してください。"; return Page(); }
        await auth.RevokeAllAsync(Guid.Parse(User.FindFirstValue(ClaimTypes.NameIdentifier)!));
        await HttpContext.SignOutAsync(OwnerAuthentication.Scheme);
        return RedirectToPage("/Login");
    }
}
