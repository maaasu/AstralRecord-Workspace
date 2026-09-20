using System.Globalization;
using System.Security.Claims;
using AstralRecordWeb.Models;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Authentication.Cookies;

namespace AstralRecordWeb.Authorization;

/// <summary>認証APIの結果から保護されたCookieを発行し、再認証の鮮度を判定します。</summary>
public static class WebSession
{
    public const string VersionClaim = "sessionVersion";
    public const string CodeTimeClaim = "codeAuthenticatedAt";
    public const string CodeProofClaim = "codeAuthenticationProof";
    public const string NeedsCodeItem = "WebAdminNeedsCode";
    public const string TrustedBrowserCookieName = "__Host-AstralRecordTrustedAdmin";
    public static readonly TimeSpan TrustedBrowserIdleWindow = TimeSpan.FromDays(7);

    public static DateTimeOffset? RecentCodeTime(ClaimsPrincipal user, TimeProvider clock)
    {
        if (string.IsNullOrEmpty(user.FindFirstValue(CodeProofClaim))) return null;
        if (!DateTimeOffset.TryParseExact(user.FindFirstValue(CodeTimeClaim), "O", CultureInfo.InvariantCulture,
                DateTimeStyles.None, out var at)) return null;
        var now = clock.GetUtcNow();
        return at <= now && at >= now.AddMinutes(-5) ? at : null;
    }

    public static Task SignInAsync(HttpContext context, WebLoginChallengeConsumeResponse session, DateTimeOffset? codeTime)
    {
        var claims = new List<Claim>
        {
            new(ClaimTypes.NameIdentifier, session.UserUuid.ToString()),
            new(ClaimTypes.Name, session.Mcid),
            new("permission", session.Permission.ToString(CultureInfo.InvariantCulture)),
            new(VersionClaim, session.SessionVersion.ToString()),
        };
        if (codeTime.HasValue && !string.IsNullOrEmpty(session.CodeAuthenticationProof))
        {
            claims.Add(new(CodeTimeClaim, codeTime.Value.ToString("O", CultureInfo.InvariantCulture)));
            claims.Add(new(CodeProofClaim, session.CodeAuthenticationProof));
        }
        if (session.CurrentAccountId.HasValue) claims.Add(new("currentAccountId", session.CurrentAccountId.Value.ToString()));
        claims.AddRange(session.AccountIds.Select(id => new Claim("accountId", id.ToString())));
        return context.SignInAsync(CookieAuthenticationDefaults.AuthenticationScheme,
            new ClaimsPrincipal(new ClaimsIdentity(claims, CookieAuthenticationDefaults.AuthenticationScheme)));
    }

    public static Task RenewVersionAsync(HttpContext context, WebCredentialState state)
    {
        var identity = new ClaimsIdentity(context.User.Identity as ClaimsIdentity);
        foreach (var claim in identity.Claims.Where(c => c.Type is VersionClaim or CodeTimeClaim or CodeProofClaim).ToList()) identity.RemoveClaim(claim);
        identity.AddClaim(new(VersionClaim, state.SessionVersion.ToString()));
        if (state.CodeAuthenticatedAt is { } codeAt && !string.IsNullOrEmpty(state.CodeAuthenticationProof))
        {
            identity.AddClaim(new(CodeTimeClaim, codeAt.ToString("O", CultureInfo.InvariantCulture)));
            identity.AddClaim(new(CodeProofClaim, state.CodeAuthenticationProof));
        }
        return context.SignInAsync(CookieAuthenticationDefaults.AuthenticationScheme, new ClaimsPrincipal(identity));
    }

    public static string? GetTrustedBrowserToken(HttpContext context) =>
        context.Request.Cookies[TrustedBrowserCookieName];

    public static void SetTrustedBrowserCookie(HttpContext context, string token, TimeProvider clock)
    {
        context.Response.Cookies.Append(TrustedBrowserCookieName, token, new CookieOptions
        {
            HttpOnly = true,
            Secure = true,
            SameSite = SameSiteMode.Lax,
            Path = "/",
            IsEssential = true,
            Expires = clock.GetUtcNow().Add(TrustedBrowserIdleWindow),
            MaxAge = TrustedBrowserIdleWindow,
        });
    }
}
