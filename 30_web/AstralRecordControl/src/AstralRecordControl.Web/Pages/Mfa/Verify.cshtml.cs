using System.ComponentModel.DataAnnotations;
using AstralRecordControl.Web.Authentication;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;

namespace AstralRecordControl.Web.Pages.Mfa;

public sealed class VerifyModel(OwnerAuthService auth) : PageModel
{
    [BindProperty, Required, StringLength(100)] public string Code { get; set; } = "";
    [BindProperty] public bool Recovery { get; set; }
    public void OnGet() { }
    public async Task<IActionResult> OnPostAsync()
    {
        var user = await auth.GetPendingUserAsync(User);
        if (user is null) return RedirectToPage("/Login");
        if (!user.TwoFactorEnabled) return RedirectToPage("/Mfa/Enroll");
        if (!ModelState.IsValid || !await auth.VerifyMfaAsync(user, Code, Recovery))
        { ModelState.AddModelError("", "認証コードを確認してください。"); return Page(); }
        await auth.SignInAsync(HttpContext, user);
        return LocalRedirect("/");
    }
}
