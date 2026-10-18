using AstralRecordControl.Web.Authentication;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;

namespace AstralRecordControl.Web.Pages;

[AllowAnonymous]
public sealed class LogoutModel(OwnerAuthService auth) : PageModel
{
    public void OnGet() { }
    public async Task<IActionResult> OnPostAsync()
    {
        var session = await auth.ValidateSessionAsync(User);
        if (session is not null) await auth.RevokeAsync(session.SessionId);
        await HttpContext.SignOutAsync(OwnerAuthentication.Scheme);
        await HttpContext.SignOutAsync(OwnerAuthentication.PendingScheme);
        return RedirectToPage("/Login");
    }
}
