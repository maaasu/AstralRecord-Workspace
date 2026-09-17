using System.Security.Claims;
using AstralRecordWeb.Services;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Authentication.Cookies;

namespace AstralRecordWeb.Authorization;

public sealed class WebSessionEvents(WebAuthApiClient api) : CookieAuthenticationEvents
{
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
        if (context.HttpContext.Items.ContainsKey(WebSession.NeedsCodeItem))
        {
            var returnUrl = context.Request.PathBase + context.Request.Path + context.Request.QueryString;
            context.Response.Redirect("/Reauthenticate?returnUrl=" + Uri.EscapeDataString(returnUrl));
            return Task.CompletedTask;
        }
        return base.RedirectToAccessDenied(context);
    }
}
