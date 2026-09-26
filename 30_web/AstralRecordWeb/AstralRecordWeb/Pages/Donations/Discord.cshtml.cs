using System.Security.Claims;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using AstralRecordWeb.Options;
using AstralRecordWeb.Authorization;
using AstralRecordWeb.Services;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.DataProtection;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;
using Microsoft.AspNetCore.WebUtilities;
using Microsoft.Extensions.Options;

namespace AstralRecordWeb.Pages.Donations;

[Authorize]
[ResponseCache(NoStore = true, Location = ResponseCacheLocation.None)]
public sealed class DiscordModel(IOptions<DonationOptions> options, IDataProtectionProvider protection,
    DiscordOAuthClient oauth, DonationApiClient api, TimeProvider clock) : PageModel
{
    public const string StateCookie = "__Host-AstralRecordDiscord";
    [TempData] public string? StatusMessage { get; set; }
    private IDataProtector Protector => protection.CreateProtector("AstralRecord.DiscordLink.v1");

    public IActionResult OnPost()
    {
        if (!Guid.TryParse(User.FindFirstValue(ClaimTypes.NameIdentifier), out var actor)) return Challenge();
        if (!options.Value.DiscordConfigured) return Failure("Discord連携の準備ができていません。運営へお問い合わせください。");
        var state = WebEncoders.Base64UrlEncode(RandomNumberGenerator.GetBytes(32));
        var verifier = WebEncoders.Base64UrlEncode(RandomNumberGenerator.GetBytes(32));
        var payload = new OAuthState(actor, User.FindFirstValue(WebSession.VersionClaim), state, verifier, clock.GetUtcNow().AddMinutes(10));
        Response.Cookies.Append(StateCookie, Protector.Protect(JsonSerializer.Serialize(payload)), new CookieOptions
        {
            HttpOnly = true, Secure = true, SameSite = SameSiteMode.Lax, Path = "/", IsEssential = true,
            MaxAge = TimeSpan.FromMinutes(10),
        });
        return Redirect(QueryHelpers.AddQueryString("https://discord.com/oauth2/authorize", new Dictionary<string, string?>
        {
            ["client_id"] = options.Value.DiscordClientId, ["redirect_uri"] = options.Value.DiscordRedirectUri,
            ["response_type"] = "code", ["scope"] = "identify guilds.members.read", ["state"] = state,
            ["code_challenge"] = WebEncoders.Base64UrlEncode(SHA256.HashData(Encoding.ASCII.GetBytes(verifier))),
            ["code_challenge_method"] = "S256", ["prompt"] = "consent",
        }));
    }

    public async Task<IActionResult> OnGetAsync(string? code, string? state, string? error, CancellationToken ct)
    {
        Response.Headers["Referrer-Policy"] = "no-referrer";
        if (!Guid.TryParse(User.FindFirstValue(ClaimTypes.NameIdentifier), out var actor)) return Challenge();
        var cookie = Request.Cookies[StateCookie];
        Response.Cookies.Delete(StateCookie, new CookieOptions { Secure = true, HttpOnly = true, SameSite = SameSiteMode.Lax, Path = "/" });
        OAuthState? saved = null;
        try { if (cookie is not null) saved = JsonSerializer.Deserialize<OAuthState>(Protector.Unprotect(cookie)); }
        catch (Exception ex) when (ex is CryptographicException or JsonException) { }
        if (!options.Value.DiscordConfigured || saved is null || saved.Actor != actor
            || saved.SessionVersion != User.FindFirstValue(WebSession.VersionClaim) || saved.ExpiresAt <= clock.GetUtcNow()
            || string.IsNullOrEmpty(state) || state.Length != saved.State.Length
            || !CryptographicOperations.FixedTimeEquals(Encoding.ASCII.GetBytes(state), Encoding.ASCII.GetBytes(saved.State)))
            return Failure("Discord連携の確認期限が切れたか、確認情報が一致しません。もう一度連携してください。");
        if (!string.IsNullOrEmpty(error) || string.IsNullOrEmpty(code) || code.Length > 2048)
            return Failure("Discord連携は完了していません。必要な権限を確認して再度お試しください。");
        var tokens = await oauth.ExchangeAsync(code, saved.Verifier, ct);
        if (tokens is null) return Failure("Discordとの認証に失敗しました。もう一度連携してください。");
        var linked = await api.LinkAsync(actor, tokens.AccessToken, tokens.RefreshToken, ct);
        if (!linked.Succeeded) return Failure(linked.ErrorMessage ?? "Discord連携を保存できませんでした。公式サーバーへの参加後、再度連携してください。");
        StatusMessage = "Discordを連携し、公式サーバーへの参加を確認しました。";
        return RedirectToPage("/Donations/Index");
    }

    private IActionResult Failure(string message) { StatusMessage = message; return RedirectToPage("/Donations/Index"); }
    private sealed record OAuthState(Guid Actor, string? SessionVersion, string State, string Verifier, DateTimeOffset ExpiresAt);
}
