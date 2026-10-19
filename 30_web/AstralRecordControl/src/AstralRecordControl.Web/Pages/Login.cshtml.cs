using System.ComponentModel.DataAnnotations;
using AstralRecordControl.Web.Authentication;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;

namespace AstralRecordControl.Web.Pages;

[AllowAnonymous]
public sealed class LoginModel(OwnerAuthService auth) : PageModel
{
    [BindProperty, Required, StringLength(256)] public string UserName { get; set; } = "";
    [BindProperty, Required, StringLength(1024)] public string Password { get; set; } = "";
    public void OnGet() { }
    public async Task<IActionResult> OnPostAsync()
    {
        if (!ModelState.IsValid) return Page();
        var user = await auth.CheckPasswordAsync(UserName, Password);
        if (user is null) { ModelState.AddModelError("", "ログインできませんでした。"); return Page(); }
        await auth.SignInAsync(HttpContext, user);
        return LocalRedirect("/");
    }
}
