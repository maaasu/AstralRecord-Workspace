using AstralRecordApi.Data;
using System.Security.Cryptography;
using System.Text;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordApi.Repositories;

/// <summary>
/// Serializes offline writers with the user-wide player edit lock. Call at the start of
/// an existing transaction, before taking account, inventory, or market row locks.
/// </summary>
internal static class PlayerAdminEditWriteGuard
{
    private static readonly string[] ActiveStatuses =
        ["DRAINING", "READY", "APPLYING", "RECOVERY_REQUIRED"];

    internal static async Task<bool> IsBlockedAsync(AstralRecordDbContext dbContext,
        IEnumerable<Guid> accountIds, CancellationToken cancellationToken = default)
    {
        var ids = accountIds.Where(x => x != Guid.Empty).Distinct().ToArray();
        if (ids.Length == 0) return false;
        var users = await dbContext.Accounts.AsNoTracking()
            .Where(x => ids.Contains(x.Uuid)).Select(x => x.UserId)
            .Distinct().ToArrayAsync(cancellationToken);
        foreach (var user in users.OrderBy(x => x))
        {
            var locked = dbContext.Database.IsSqlServer()
                ? dbContext.Users.FromSqlInterpolated(
                    $"SELECT * FROM [dbo].[user] WITH (UPDLOCK,HOLDLOCK) WHERE [uuid] = {user}")
                : dbContext.Users.Where(x => x.Uuid == user);
            if (!await locked.AnyAsync(cancellationToken)) return true;
        }
        return await dbContext.PlayerAdminEditSessions.AsNoTracking().AnyAsync(
            x => users.Contains(x.UserUuid) && ActiveStatuses.Contains(x.Status), cancellationToken);
    }

    // Only an account session captured before the edit lock may finish a pending
    // RPG save. Lease TTL is intentionally not used as proof of being offline.
    internal static async Task<bool> AllowsCapturedRuntimeFinalizationAsync(
        AstralRecordDbContext dbContext, Guid accountId, Guid userUuid,
        string? serverId, Guid? serverSessionId, Guid? accountSessionId, string? accountLeaseToken,
        DateTime? operationCreatedAt = null)
    {
        if (string.IsNullOrWhiteSpace(serverId) || serverId.Length > 64
            || serverSessionId is null || serverSessionId == Guid.Empty
            || accountSessionId is null || accountSessionId == Guid.Empty)
            return false;
        var edit = await dbContext.PlayerAdminEditSessions.AsNoTracking().SingleOrDefaultAsync(x =>
            x.UserUuid == userUuid && ActiveStatuses.Contains(x.Status));
        if (edit is null || edit.Status is not ("DRAINING" or "RECOVERY_REQUIRED")
            || operationCreatedAt.HasValue && operationCreatedAt.Value > edit.CreatedAtUtc
            || string.IsNullOrEmpty(accountLeaseToken) || accountLeaseToken.Length != 64
            || accountLeaseToken.Any(c => c is not (>= '0' and <= '9' or >= 'a' and <= 'f')))
            return false;
        var drain = await dbContext.PlayerAdminEditDrains.AsNoTracking().SingleOrDefaultAsync(x =>
            x.EditSessionId == edit.EditSessionId && x.ServerId == serverId
            && x.ServerSessionId == serverSessionId.Value);
        if (drain is null || drain.AcknowledgedAtUtc.HasValue) return false;
        var runtime = await dbContext.PlayerAdminServerRuntimes.AsNoTracking().SingleOrDefaultAsync(x => x.ServerId == serverId);
        if (runtime is null || !runtime.Enabled || runtime.Role != "RPG"
            || runtime.ServerSessionId != serverSessionId.Value) return false;
        var session = await SkillTreeSessionReads.Query(dbContext).AsNoTracking().SingleOrDefaultAsync(x =>
            x.AccountSessionId == accountSessionId.Value && x.AccountId == accountId
            && x.ServerId == serverId && x.ServerSessionId == serverSessionId.Value
            && !x.Closed && x.CreatedAtUtc <= edit.CreatedAtUtc);
        if (session is null) return false;
        var suppliedHash = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(accountLeaseToken))).ToLowerInvariant();
        return CryptographicOperations.FixedTimeEquals(
            Encoding.UTF8.GetBytes(session.LeaseTokenHash), Encoding.UTF8.GetBytes(suppliedHash));
    }
}
