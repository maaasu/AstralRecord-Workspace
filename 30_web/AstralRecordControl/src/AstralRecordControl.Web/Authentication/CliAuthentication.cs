using System.Security.Claims;
using System.Security.Cryptography;
using System.Text.Encodings.Web;
using AstralRecordControl.Contracts;
using AstralRecordControl.Web.Data;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Antiforgery;
using Microsoft.AspNetCore.Identity;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;

namespace AstralRecordControl.Web.Authentication;

public static class CliAuthentication
{
    public const string Scheme = "CliBearer";
    public const string OperatorPolicy = "ControlOperator";
    public const string SchemaSql = """
        CREATE TABLE CliCredentials(CredentialId TEXT NOT NULL PRIMARY KEY,OwnerUserId TEXT NOT NULL,TokenHashHex TEXT NOT NULL,SecurityStamp TEXT NOT NULL,ExpiresAtUtc TEXT NOT NULL,RevokedAtUtc TEXT NULL,CreatedAtUtc TEXT NOT NULL,FOREIGN KEY(OwnerUserId) REFERENCES AspNetUsers(Id) ON DELETE RESTRICT);
        CREATE UNIQUE INDEX IX_CliCredentials_TokenHashHex ON CliCredentials(TokenHashHex);
        """;
    public static void Configure(WebApplicationBuilder builder)
    {
        builder.Services.AddScoped<CliCredentialService>();
        builder.Services.AddAuthentication().AddScheme<AuthenticationSchemeOptions, CliBearerHandler>(Scheme, _ => { });
        builder.Services.AddAuthorization(options =>
        {
            options.AddPolicy("CliOnly", p => p.AddAuthenticationSchemes(Scheme).RequireAuthenticatedUser().RequireClaim("control_cli"));
            options.AddPolicy(OperatorPolicy, p => p.AddAuthenticationSchemes(OwnerAuthentication.Scheme, Scheme).RequireAuthenticatedUser().RequireAssertion(context =>
                context.User.Identities.Any(identity => identity.IsAuthenticated &&
                    (identity.AuthenticationType == Scheme && identity.HasClaim(c => c.Type == "control_cli") ||
                     identity.AuthenticationType == OwnerAuthentication.Scheme && identity.HasClaim("amr", "pwd") && identity.HasClaim(c => c.Type == OwnerAuthentication.SessionClaim)))));
        });
    }
    public static void Map(WebApplication app)
    {
        app.MapPost("/api/control/v1/auth/cli-token", async (System.Text.Json.JsonElement body, HttpContext context, CliCredentialService service) =>
        {
            if (context.Request.Cookies.Count != 0) return Results.BadRequest();
            CliLoginRequest input;
            try { input = ControlJson.Deserialize<CliLoginRequest>(body.GetRawText()); }
            catch (System.Text.Json.JsonException) { return Results.BadRequest(); }
            var token = await service.IssueAsync(input);
            return token is null ? Results.Unauthorized() : Results.Ok(token);
        }).AllowAnonymous().RequireRateLimiting(OwnerAuthentication.LoginLimit);
        app.MapPost("/api/control/v1/auth/cli-token/revoke", async (HttpContext context, CliCredentialService service) =>
        {
            if (!Guid.TryParse(context.User.FindFirstValue("control_cli"), out var id)) return Results.Unauthorized();
            await service.RevokeAsync(id); return Results.NoContent();
        }).RequireAuthorization("CliOnly");
    }
    public static async Task ValidateCookieCsrfAsync(HttpContext context)
    {
        if (context.Request.Cookies.ContainsKey(OwnerAuthentication.CookieName) || context.User.Identities.Any(i => i.AuthenticationType == OwnerAuthentication.Scheme && i.IsAuthenticated))
            await context.RequestServices.GetRequiredService<IAntiforgery>().ValidateRequestAsync(context);
    }
}

public sealed class CliCredentialService(ControlDbContext db, OwnerAuthService owner, UserManager<ControlUser> users, TimeProvider clock)
{
    public async Task<CliTokenIssued?> IssueAsync(CliLoginRequest input)
    {
        if (input.LoginName is null || input.Password is null || input.LoginName.Length > 256 || input.Password.Length is < 1 or > 1024) return null;
        var user = await owner.CheckPasswordAsync(input.LoginName, input.Password);
        if (user is null) return null;
        var now = clock.GetUtcNow();
        var token = Convert.ToHexStringLower(RandomNumberGenerator.GetBytes(32));
        var credential = new CliCredential { CredentialId = Guid.NewGuid(), OwnerUserId = user.Id, SecurityStamp = await users.GetSecurityStampAsync(user), TokenHashHex = ControlProtocol.Hash(token), CreatedAtUtc = now.UtcDateTime, ExpiresAtUtc = now.AddMinutes(15).UtcDateTime };
        db.CliCredentials.Add(credential); await db.SaveChangesAsync();
        return new(credential.CredentialId, token, new DateTimeOffset(credential.ExpiresAtUtc, TimeSpan.Zero));
    }
    public async Task<CliCredential?> AuthenticateAsync(string token)
    {
        if (token.Length != 64 || token.Any(c => !char.IsAsciiHexDigit(c))) return null;
        var hash = ControlProtocol.Hash(token);
        var row = await db.CliCredentials.AsNoTracking().SingleOrDefaultAsync(c => c.TokenHashHex == hash);
        if (row is null || row.RevokedAtUtc.HasValue || row.ExpiresAtUtc <= clock.GetUtcNow().UtcDateTime) return null;
        var user = await users.FindByIdAsync(row.OwnerUserId.ToString());
        return user is not null && user.SecurityStamp == row.SecurityStamp && await db.OwnerSettings.AnyAsync(s => s.OwnerUserId == user.Id) ? row : null;
    }
    public async Task RevokeAsync(Guid credentialId) => await db.CliCredentials.Where(c => c.CredentialId == credentialId).ExecuteUpdateAsync(update => update.SetProperty(c => c.RevokedAtUtc, clock.GetUtcNow().UtcDateTime));
}

public sealed class CliBearerHandler(IOptionsMonitor<AuthenticationSchemeOptions> options, ILoggerFactory logger, UrlEncoder encoder, CliCredentialService credentials) : AuthenticationHandler<AuthenticationSchemeOptions>(options, logger, encoder)
{
    protected override async Task<AuthenticateResult> HandleAuthenticateAsync()
    {
        var header = Request.Headers.Authorization.ToString();
        if (!header.StartsWith("Bearer ", StringComparison.OrdinalIgnoreCase)) return AuthenticateResult.NoResult();
        var credential = await credentials.AuthenticateAsync(header[7..]);
        if (credential is null) return AuthenticateResult.Fail("Invalid CLI credential.");
        var principal = new ClaimsPrincipal(new ClaimsIdentity([new Claim(ClaimTypes.NameIdentifier, credential.OwnerUserId.ToString()), new Claim("control_cli", credential.CredentialId.ToString()), new Claim("amr", "pwd")], CliAuthentication.Scheme));
        return AuthenticateResult.Success(new AuthenticationTicket(principal, CliAuthentication.Scheme));
    }
}
