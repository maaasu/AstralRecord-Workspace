using System.Security.Claims;
using AstralRecordWeb.Services;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Authentication.Cookies;

namespace AstralRecordWeb.Authorization;

public sealed class WebSessionEvents(WebAuthApiClient api) : CookieAuthenticationEvents
{
    public override Task RedirectToLogin(RedirectContext<CookieAuthenticationOptions> context)
    {
        return RedirectDiscordCallback(context) ? Task.CompletedTask : base.RedirectToLogin(context);
    }

    public override async Task ValidatePrincipal(CookieValidatePrincipalContext context)
    {
        var user = context.Principal;
        if (Guid.TryParse(user?.FindFirstValue(ClaimTypes.NameIdentifier), out var uuid) &&
            Guid.TryParse(user?.FindFirstValue(WebSession.VersionClaim), out var version) && version != Guid.Empty)
        {
            var state = await api.GetCredentialsAsync(uuid, context.HttpContext.RequestAborted);
            if (state?.SessionVersion == version) return;
        }
        // API障害時も失効確認を迂回させない。旧版Cookieは再ログインで移行する。
        context.RejectPrincipal();
        await context.HttpContext.SignOutAsync(CookieAuthenticationDefaults.AuthenticationScheme);
    }

    public override Task RedirectToAccessDenied(RedirectContext<CookieAuthenticationOptions> context)
    {
        if (RedirectDiscordCallback(context)) return Task.CompletedTask;
        if (context.HttpContext.Items.ContainsKey(WebSession.NeedsCodeItem))
        {
            var returnUrl = context.Request.PathBase + context.Request.Path + context.Request.QueryString;
            context.Response.Redirect("/Reauthenticate?returnUrl=" + Uri.EscapeDataString(returnUrl));
            return Task.CompletedTask;
        }
        return base.RedirectToAccessDenied(context);
    }

    /// <summary>認証切れのOAuth callbackからcode/stateをログインのReturnUrlへ転記しません。</summary>
    private static bool RedirectDiscordCallback(RedirectContext<CookieAuthenticationOptions> context)
    {
        if (!string.Equals(context.Request.Path.Value?.TrimEnd('/'), "/Donations/Discord", StringComparison.OrdinalIgnoreCase)) return false;
        context.Response.Headers["Referrer-Policy"] = "no-referrer";
        context.Response.Headers.CacheControl = "no-store";
        context.Response.Cookies.Delete(Pages.Donations.DiscordModel.StateCookie,
            new CookieOptions { Secure = true, HttpOnly = true, SameSite = SameSiteMode.Lax, Path = "/" });
        var destination = context.Request.PathBase + "/Donations";
        context.Response.Redirect(context.Request.PathBase + context.Options.LoginPath + "?"
            + context.Options.ReturnUrlParameter + "=" + Uri.EscapeDataString(destination));
        return true;
    }
}
