using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;

namespace AstralRecordControl.Web.Pages.Mfa;

[AllowAnonymous]
public sealed class VerifyModel : PageModel
{
    public IActionResult OnGet() => User.Identity?.IsAuthenticated == true ? LocalRedirect("/") : RedirectToPage("/Login");
    public IActionResult OnPost() => OnGet();
}
