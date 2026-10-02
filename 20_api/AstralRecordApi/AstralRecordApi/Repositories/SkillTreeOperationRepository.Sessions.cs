using System.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordApi.Repositories;

public sealed partial class SkillTreeOperationRepository
{
    public Task<bool> AcquireAccountSessionAsync(string serverId, Guid accountId, SkillTreeAccountSessionRequest request) =>
        ExecuteRuntimeTransactionAsync(() => AcquireAccountSessionCoreAsync(serverId, accountId, request));

    private async Task<bool> AcquireAccountSessionCoreAsync(string serverId, Guid accountId, SkillTreeAccountSessionRequest request)
    {
        if (request.AccountSessionId == Guid.Empty || !ValidHash(request.AccountLeaseToken)) return false;
        await using var transaction = await dbContext.Database.BeginTransactionAsync(IsolationLevel.Serializable);
        var ownerUserId = await dbContext.Accounts.AsNoTracking()
            .Where(x => x.Uuid == accountId && !x.IsDeleted)
            .Select(x => (Guid?)x.UserId).SingleOrDefaultAsync();
        if (!ownerUserId.HasValue || !await LockUserForPlayerAdminAsync(ownerUserId.Value)) return false;
        if (await LockAccountAsync(accountId) is null) return false;
        var runtime = await VerifyRuntimeAsync(serverId, request.ServerSessionId);
        if (runtime is null || runtime.DefinitionGenerationId != request.DefinitionGenerationId) return false;
        var existing = await SkillTreeSessionReads.Query(dbContext).SingleOrDefaultAsync(x => x.AccountSessionId == request.AccountSessionId);
        var edit = await dbContext.PlayerAdminEditSessions.AsNoTracking().SingleOrDefaultAsync(x =>
            x.UserUuid == ownerUserId.Value && (x.Status == "DRAINING" || x.Status == "READY"
                || x.Status == "APPLYING" || x.Status == "RECOVERY_REQUIRED"));
        if (edit is not null && (edit.Status is not ("DRAINING" or "RECOVERY_REQUIRED") || existing is null
            || existing.CreatedAtUtc > edit.CreatedAtUtc || existing.Closed
            || !await dbContext.PlayerAdminEditDrains.AsNoTracking().AnyAsync(x =>
                x.EditSessionId == edit.EditSessionId && x.ServerId == serverId
                && x.ServerSessionId == request.ServerSessionId))) return false;
        if (existing is not null)
        {
            if (existing.Closed || existing.ExpiresAtUtc <= DateTime.UtcNow || existing.AccountId != accountId
                || existing.ServerId != serverId || existing.ServerSessionId != request.ServerSessionId
                || !FixedEquals(existing.LeaseTokenHash, Hash(request.AccountLeaseToken))) return false;
            existing.ExpiresAtUtc = DateTime.UtcNow + RuntimeTtl;
            await dbContext.SaveChangesAsync();
            await transaction.CommitAsync();
            return true;
        }
        var now = DateTime.UtcNow;
        var owners = await SkillTreeSessionReads.Query(dbContext).Where(x => x.AccountId == accountId && !x.Closed).ToListAsync();
        if (owners.Any(x => x.ExpiresAtUtc > now && !(x.ServerId == serverId && x.ServerSessionId != request.ServerSessionId))) return false;
        owners.ForEach(x => x.Closed = true);
        await dbContext.SaveChangesAsync();
        dbContext.SkillTreeAccountSessions.Add(new SkillTreeAccountSessionEntity
        {
            AccountSessionId = request.AccountSessionId, AccountId = accountId, ServerId = serverId,
            ServerSessionId = request.ServerSessionId, LeaseTokenHash = Hash(request.AccountLeaseToken),
            DefinitionGenerationId = request.DefinitionGenerationId,
            CreatedAtUtc = now, ExpiresAtUtc = now + RuntimeTtl,
        });
        await dbContext.SkillTreeServerPlayerViews.Where(x => x.AccountId == accountId)
            .ExecuteUpdateAsync(s => s.SetProperty(x => x.OfflineConfirmed, false));
        await dbContext.SkillTreeOperations.Where(x => x.AccountId == accountId
                && (x.Status == SkillTreeOperationStatuses.Claimed || x.Status == SkillTreeOperationStatuses.PendingOnline))
            .ExecuteUpdateAsync(s => s.SetProperty(x => x.Status, SkillTreeOperationStatuses.ReconfirmationRequired)
                .SetProperty(x => x.CompletedAtUtc, now).SetProperty(x => x.LeaseTokenHash, (string?)null));
        await dbContext.SaveChangesAsync();
        await transaction.CommitAsync();
        return true;
    }

    public Task<bool> CloseAccountSessionAsync(string serverId, Guid accountId, SkillTreePlayerViewRegistrationRequest request) =>
        ExecuteRuntimeTransactionAsync(() => CloseAccountSessionCoreAsync(serverId, accountId, request));

    private async Task<bool> CloseAccountSessionCoreAsync(string serverId, Guid accountId, SkillTreePlayerViewRegistrationRequest request)
    {
        await using var transaction = await dbContext.Database.BeginTransactionAsync(IsolationLevel.Serializable);
        var ownerUserId = await dbContext.Accounts.AsNoTracking()
            .Where(x => x.Uuid == accountId && !x.IsDeleted)
            .Select(x => (Guid?)x.UserId).SingleOrDefaultAsync();
        if (!ownerUserId.HasValue || !await LockUserForPlayerAdminAsync(ownerUserId.Value)) return false;
        if (await LockAccountAsync(accountId) is null) return false;
        var completed = await SkillTreeSessionReads.Query(dbContext)
            .SingleOrDefaultAsync(x => x.AccountSessionId == request.AccountSessionId);
        if (completed?.Closed == true)
        {
            if (completed.AccountId != accountId || completed.ServerId != serverId
                || completed.ServerSessionId != request.ServerSessionId
                || !ValidHash(request.AccountLeaseToken)
                || !FixedEquals(completed.LeaseTokenHash, Hash(request.AccountLeaseToken))) return false;
            await transaction.CommitAsync();
            return true;
        }
        var runtime = await dbContext.SkillTreeServerRuntimes.SingleOrDefaultAsync(x => x.ServerId == serverId);
        var session = await MatchingSessionAsync(accountId, serverId, request.ServerSessionId, request.AccountSessionId, request.AccountLeaseToken);
        if (session is null && ValidHash(request.DefinitionGenerationId)
            && await PlayerAdminEditWriteGuard.AllowsCapturedRuntimeFinalizationAsync(dbContext,
                accountId, ownerUserId.Value, serverId, request.ServerSessionId,
                request.AccountSessionId, request.AccountLeaseToken))
        {
            session = await SkillTreeSessionReads.Query(dbContext).SingleOrDefaultAsync(x =>
                x.AccountSessionId == request.AccountSessionId && x.AccountId == accountId
                && x.DefinitionGenerationId == request.DefinitionGenerationId && !x.Closed);
        }
        if (session is null) return false;
        var state = await dbContext.AccountSkillTreeStates.SingleOrDefaultAsync(x => x.AccountId == accountId && !x.IsDeleted);
        if (runtime is not null && runtime.ServerSessionId == request.ServerSessionId
            && state?.Version == request.PlayerStateVersion && state.DefinitionGenerationId == request.DefinitionGenerationId
            && runtime.DefinitionGenerationId == request.DefinitionGenerationId && IsValidView(request))
        {
            await UpsertViewAsync(runtime, accountId, state.Version, request.EvaluationFingerprint, false, request.View, DateTime.UtcNow);
            var view = await dbContext.SkillTreeServerPlayerViews.FindAsync(serverId, accountId);
            view!.OfflineConfirmed = true;
        }
        session.Closed = true;
        await dbContext.SkillTreeOperations.Where(x => x.AccountId == accountId
                && (x.Status == SkillTreeOperationStatuses.PendingOnline || x.Status == SkillTreeOperationStatuses.Claimed))
            .ExecuteUpdateAsync(s => s.SetProperty(x => x.Status, SkillTreeOperationStatuses.Canceled)
                .SetProperty(x => x.CompletedAtUtc, DateTime.UtcNow).SetProperty(x => x.LeaseTokenHash, (string?)null));
        await dbContext.SaveChangesAsync();
        await transaction.CommitAsync();
        return true;
    }

    public async Task<bool> RequiresRuntimeAuthorityAsync(Guid accountId) =>
        await SkillTreeSessionReads.Query(dbContext).AnyAsync(x => x.AccountId == accountId)
        || await dbContext.AccountSkillTreeStates.AnyAsync(x => x.AccountId == accountId && !x.IsDeleted && x.DefinitionGenerationId != null);

    private async Task<AccountEntity?> LockAccountAsync(Guid accountId)
    {
        if (dbContext.Database.IsSqlServer())
            return await dbContext.Accounts.FromSqlInterpolated($"SELECT * FROM [dbo].[account] WITH (UPDLOCK,HOLDLOCK) WHERE [uuid] = {accountId}")
                .SingleOrDefaultAsync(x => !x.IsDeleted);
        return await dbContext.Accounts.SingleOrDefaultAsync(x => x.Uuid == accountId && !x.IsDeleted);
    }

    private async Task<bool> LockUserForPlayerAdminAsync(Guid userId)
    {
        var query = dbContext.Database.IsSqlServer()
            ? dbContext.Users.FromSqlInterpolated($"SELECT * FROM [dbo].[user] WITH (UPDLOCK,HOLDLOCK) WHERE [uuid] = {userId}")
            : dbContext.Users.Where(x => x.Uuid == userId);
        return await query.AnyAsync(x => !x.IsDeleted);
    }

    private Task<SkillTreeAccountSessionEntity?> ActiveSessionAsync(Guid accountId) =>
        SkillTreeSessionReads.Query(dbContext).SingleOrDefaultAsync(x => x.AccountId == accountId && !x.Closed && x.ExpiresAtUtc > DateTime.UtcNow);

    private async Task<SkillTreeAccountSessionEntity?> MatchingSessionAsync(Guid accountId, string serverId, Guid bootId, Guid sessionId, string token)
    {
        if (!ValidHash(token)) return null;
        var session = await ActiveSessionAsync(accountId);
        return session is not null && session.AccountSessionId == sessionId && session.ServerId == serverId
            && session.ServerSessionId == bootId && FixedEquals(session.LeaseTokenHash, Hash(token)) ? session : null;
    }
}
