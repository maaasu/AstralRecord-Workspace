using System.ComponentModel.DataAnnotations;
using AstralRecordControl.Web.Authentication;
using AstralRecordControl.Web.Data;
using Microsoft.AspNetCore.Identity;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;

namespace AstralRecordControl.Web.Pages.Mfa;

public sealed class EnrollModel(OwnerAuthService auth, UserManager<ControlUser> users) : PageModel
{
    public string SharedKey { get; private set; } = "";
    public string AuthenticatorUri { get; private set; } = "";
    public string[] RecoveryCodes { get; private set; } = [];
    [BindProperty, Required, StringLength(20)] public string Code { get; set; } = "";
    private async Task LoadAsync(ControlUser user)
    {
        var key = await users.GetAuthenticatorKeyAsync(user);
        if (string.IsNullOrEmpty(key))
        {
            await users.ResetAuthenticatorKeyAsync(user);
            key = await users.GetAuthenticatorKeyAsync(user);
            // Identity changes its security stamp when creating an authenticator key.
            await auth.BeginMfaAsync(HttpContext, user);
        }
        SharedKey = key!;
        AuthenticatorUri = $"otpauth://totp/AstralRecordControl:{Uri.EscapeDataString(user.UserName!)}?secret={SharedKey}&issuer=AstralRecordControl&digits=6";
    }
    public async Task<IActionResult> OnGetAsync()
    {
        var user = await auth.GetPendingUserAsync(User);
        if (user is null) return RedirectToPage("/Login");
        if (user.TwoFactorEnabled) return RedirectToPage("/Mfa/Verify");
        await LoadAsync(user);
        return Page();
    }
    public async Task<IActionResult> OnPostAsync()
    {
        var user = await auth.GetPendingUserAsync(User);
        if (user is null) return RedirectToPage("/Login");
        if (user.TwoFactorEnabled) return RedirectToPage("/Mfa/Verify");
        if (!ModelState.IsValid || !await auth.VerifyMfaAsync(user, Code, false))
        { await LoadAsync(user); ModelState.AddModelError("", "認証コードを確認してください。"); return Page(); }
        var enabled = await users.SetTwoFactorEnabledAsync(user, true);
        if (!enabled.Succeeded) return StatusCode(409);
        RecoveryCodes = (await users.GenerateNewTwoFactorRecoveryCodesAsync(user, 10))!.ToArray();
        await auth.SignInAsync(HttpContext, user);
        return Page();
    }
}
