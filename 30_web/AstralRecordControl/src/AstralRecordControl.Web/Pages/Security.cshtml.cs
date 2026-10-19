using System.Security.Claims;
using AstralRecordControl.Web.Authentication;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;

namespace AstralRecordControl.Web.Pages;

public sealed class SecurityModel(OwnerAuthService auth) : PageModel
{
    public void OnGet() { }
    public async Task<IActionResult> OnPostLogoutAllAsync()
    {
        await auth.RevokeAllAsync(Guid.Parse(User.FindFirstValue(ClaimTypes.NameIdentifier)!));
        await HttpContext.SignOutAsync(OwnerAuthentication.Scheme);
        return RedirectToPage("/Login");
    }
}
