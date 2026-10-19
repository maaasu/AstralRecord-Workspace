using System.Security.Claims;
using AstralRecordControl.Web.Data;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Identity;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordControl.Web.Authentication;

public sealed class OwnerAuthService(ControlDbContext db, UserManager<ControlUser> users, SignInManager<ControlUser> signIn, TimeProvider clock, OwnerSessionConnections connections)
{
    public async Task<IdentityResult> CreateAsync(string name, string password)
    {
        await using var transaction = await db.Database.BeginTransactionAsync();
        if (await db.Users.AnyAsync() || await db.OwnerSettings.AnyAsync()) return IdentityResult.Failed(new IdentityError { Description = "Owner already exists." });
        var user = new ControlUser { Id = Guid.NewGuid(), UserName = name, LockoutEnabled = true };
        var result = await users.CreateAsync(user, password);
        if (!result.Succeeded) return result;
        db.OwnerSettings.Add(new OwnerSettings { OwnerUserId = user.Id });
        await db.SaveChangesAsync();
        await transaction.CommitAsync();
        return result;
    }

    public async Task<ControlUser?> CheckPasswordAsync(string name, string password)
    {
        var user = await users.FindByNameAsync(name);
        if (user is null)
        {
            // Keep the missing-user path comparable to the password hashing path.
            _ = new PasswordHasher<ControlUser>().HashPassword(new ControlUser(), password);
            return null;
        }
        if (!await db.OwnerSettings.AnyAsync(x => x.OwnerUserId == user.Id)) return null;
        var result = await signIn.CheckPasswordSignInAsync(user, password, lockoutOnFailure: true);
        return result.Succeeded ? user : null;
    }

    public async Task SignInAsync(HttpContext context, ControlUser user)
    {
        var now = clock.GetUtcNow();
        var session = new OwnerSession { SessionId = Guid.NewGuid(), OwnerUserId = user.Id, SecurityStamp = await users.GetSecurityStampAsync(user), CreatedAtUtc = now.UtcDateTime, ExpiresAtUtc = now.AddHours(8).UtcDateTime, ReauthenticatedAtUtc = now.UtcDateTime };
        db.OwnerSessions.Add(session);
        await db.SaveChangesAsync();
        var identity = new ClaimsIdentity([
            new Claim(ClaimTypes.NameIdentifier, user.Id.ToString()), new Claim(ClaimTypes.Name, user.UserName!),
            new Claim("amr", "pwd"), new Claim(OwnerAuthentication.SessionClaim, session.SessionId.ToString()),
            new Claim(OwnerAuthentication.StampClaim, session.SecurityStamp)
        ], OwnerAuthentication.Scheme);
        context.Response.Cookies.Delete("__Host-ControlPending", new CookieOptions { Secure = true, Path = "/" });
        await context.SignInAsync(OwnerAuthentication.Scheme, new ClaimsPrincipal(identity), new AuthenticationProperties
        { IsPersistent = false, IssuedUtc = now, ExpiresUtc = now.AddHours(8), AllowRefresh = false });
    }

    public async Task<OwnerSession?> ValidateSessionAsync(ClaimsPrincipal? principal)
    {
        if (principal?.FindFirstValue("amr") != "pwd" || !Guid.TryParse(principal.FindFirstValue(OwnerAuthentication.SessionClaim), out var id)) return null;
        var session = await ValidateSessionAsync(id);
        return session is not null && session.OwnerUserId.ToString() == principal.FindFirstValue(ClaimTypes.NameIdentifier)
            && session.SecurityStamp == principal.FindFirstValue(OwnerAuthentication.StampClaim) ? session : null;
    }

    public async Task<OwnerSession?> ValidateSessionAsync(Guid id)
    {
        var session = await db.OwnerSessions.AsNoTracking().SingleOrDefaultAsync(x => x.SessionId == id);
        if (session is null || session.RevokedAtUtc is not null || session.ExpiresAtUtc <= clock.GetUtcNow().UtcDateTime) return null;
        var user = await users.FindByIdAsync(session.OwnerUserId.ToString());
        return user is not null && user.SecurityStamp == session.SecurityStamp
            && await db.OwnerSettings.AnyAsync(x => x.OwnerUserId == user.Id) ? session : null;
    }

    public async Task RevokeAsync(Guid sessionId)
    {
        await db.OwnerSessions.Where(x => x.SessionId == sessionId && x.RevokedAtUtc == null).ExecuteUpdateAsync(s => s.SetProperty(x => x.RevokedAtUtc, clock.GetUtcNow().UtcDateTime));
        connections.Revoke(sessionId);
    }
    public async Task RevokeAllAsync(Guid ownerId)
    {
        var user = await users.FindByIdAsync(ownerId.ToString());
        if (user is null) throw new InvalidOperationException("Owner is missing.");
        var updated = await users.UpdateSecurityStampAsync(user);
        if (!updated.Succeeded) throw new InvalidOperationException("Owner security stamp could not be updated.");
        var ids = await db.OwnerSessions.Where(x => x.OwnerUserId == ownerId && x.RevokedAtUtc == null).Select(x => x.SessionId).ToListAsync();
        await db.OwnerSessions.Where(x => x.OwnerUserId == ownerId && x.RevokedAtUtc == null).ExecuteUpdateAsync(s => s.SetProperty(x => x.RevokedAtUtc, clock.GetUtcNow().UtcDateTime));
        foreach (var id in ids) connections.Revoke(id);
    }
    public async Task<IdentityResult> ResetAsync(string password)
    {
        var setting = await db.OwnerSettings.SingleOrDefaultAsync();
        if (setting is null) return IdentityResult.Failed(new IdentityError { Description = "Owner is not initialized." });
        var user = (await users.FindByIdAsync(setting.OwnerUserId.ToString()))!;
        await using var transaction = await db.Database.BeginTransactionAsync();
        // The offline OS administrator need not decrypt the service account's Data Protection keys.
        var result = await users.RemovePasswordAsync(user);
        if (!result.Succeeded) return result;
        result = await users.AddPasswordAsync(user, password);
        if (!result.Succeeded) return result;
        await users.SetTwoFactorEnabledAsync(user, false);
        await users.ResetAuthenticatorKeyAsync(user);
        await users.GenerateNewTwoFactorRecoveryCodesAsync(user, 0);
        await users.SetLockoutEndDateAsync(user, null);
        await users.ResetAccessFailedCountAsync(user);
        await RevokeAllAsync(user.Id);
        await transaction.CommitAsync();
        return IdentityResult.Success;
    }
}
