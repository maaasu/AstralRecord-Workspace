using System.Data;
using System.Text.Json;
using AstralRecordApi.Models;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordApi.Repositories;

public sealed partial class SkillTreeOperationRepository
{
    public async Task<IReadOnlyList<SkillTreeAccountHeartbeatItemResponse>?> HeartbeatAccountsAsync(
        string serverId, SkillTreeAccountHeartbeatBatchRequest request)
    {
        if (!ValidServer(serverId) || request.Items is not { Count: >= 1 and <= 32 }
            || request.Items.Any(x => x is null || x.AccountId == Guid.Empty || x.Session is null)
            || request.Items.Select(x => x.AccountId).Distinct().Count() != request.Items.Count
            || request.Items.Select(x => x.Session.ServerSessionId).Distinct().Count() != 1) return null;
        var results = new List<SkillTreeAccountHeartbeatItemResponse>();
        // 1件ずつ既存のuser→account lock順で確定する。複数accountのlockを抱えない。
        foreach (var item in request.Items)
            results.Add(await ExecuteRuntimeTransactionAsync(() => HeartbeatAccountCoreAsync(serverId, item)));
        var acceptedSessions = request.Items.Where(x => results.Any(r => r.AccountId == x.AccountId && r.Renewed))
            .Select(x => x.Session.AccountSessionId).ToArray();
        if (acceptedSessions.Length == 0) return results;
        var now = DateTime.UtcNow;
        var bootId = request.Items[0].Session.ServerSessionId;
        // 空pollでexpire UPDATEを行わない。失効済みoperationを配送せず、claimが再検証する。
        // renewal後のclose/reconnect/runtime世代変更も、この一括照会で再照合する。
        var rows = await (from operation in dbContext.SkillTreeOperations.AsNoTracking()
            join session in SkillTreeSessionReads.Query(dbContext).AsNoTracking() on operation.AccountId equals session.AccountId
            join runtime in dbContext.SkillTreeServerRuntimes.AsNoTracking() on session.ServerId equals runtime.ServerId
            where acceptedSessions.Contains(session.AccountSessionId) && !session.Closed && session.ExpiresAtUtc > now
                && session.ServerId == serverId && session.ServerSessionId == bootId
                && runtime.ServerSessionId == bootId && runtime.Ready && runtime.LastSeenUtc >= now - RuntimeTtl
                && runtime.DefinitionGenerationId == session.DefinitionGenerationId
                && (runtime.CompatibilityVersion == SupportedCompatibility || runtime.CompatibilityVersion == BatchCompatibility)
                && operation.TargetServerId == serverId && operation.ExpiresAtUtc > now
                && (operation.Status == SkillTreeOperationStatuses.PendingOnline || operation.Status == SkillTreeOperationStatuses.PendingOffline)
            orderby operation.CreatedAtUtc, operation.OperationId
            select operation).ToListAsync();
        foreach (var result in results)
            result.Operations = rows.Where(x => x.AccountId == result.AccountId).Select(Map).ToArray();
        return results;
    }

    private async Task<SkillTreeAccountHeartbeatItemResponse> HeartbeatAccountCoreAsync(string serverId, SkillTreeAccountHeartbeatItemRequest item)
    {
        SkillTreeAccountHeartbeatItemResponse Rejected() => new() { AccountId = item.AccountId };
        var request = item.Session;
        if (request.ServerSessionId == Guid.Empty || request.AccountSessionId == Guid.Empty
            || !ValidHash(request.AccountLeaseToken) || !ValidHash(request.DefinitionGenerationId)) return Rejected();
        await using var tx = await dbContext.Database.BeginTransactionAsync(IsolationLevel.Serializable);
        var userId = await dbContext.Accounts.AsNoTracking().Where(x => x.Uuid == item.AccountId && !x.IsDeleted)
            .Select(x => (Guid?)x.UserId).SingleOrDefaultAsync();
        if (!userId.HasValue || !await LockUserForPlayerAdminAsync(userId.Value)
            || await LockAccountAsync(item.AccountId) is null) return Rejected();
        var runtime = await VerifyRuntimeAsync(serverId, request.ServerSessionId);
        var owner = await MatchingSessionAsync(item.AccountId, serverId, request.ServerSessionId, request.AccountSessionId, request.AccountLeaseToken);
        if (runtime is null || runtime.DefinitionGenerationId != request.DefinitionGenerationId || owner is null
            || owner.DefinitionGenerationId != request.DefinitionGenerationId
            || !await AllowsSessionRenewalDuringAdminEditAsync(userId.Value, serverId, request.ServerSessionId, owner)) return Rejected();
        var now = DateTime.UtcNow;
        var acceptedView = false;
        if (item.View is { } view && view.ServerSessionId == request.ServerSessionId
            && view.AccountSessionId == owner.AccountSessionId && view.AccountLeaseToken == request.AccountLeaseToken
            && view.DefinitionGenerationId == request.DefinitionGenerationId && view.ViewSequence > owner.ViewSequence
            && ValidHash(view.EvaluationFingerprint) && view.PlayerStateVersion >= 0)
        {
            var state = await dbContext.AccountSkillTreeStates.AsNoTracking().SingleOrDefaultAsync(x => x.AccountId == item.AccountId && !x.IsDeleted);
            if (state?.Version == view.PlayerStateVersion && state.DefinitionGenerationId == view.DefinitionGenerationId)
            {
                var cached = await dbContext.SkillTreeServerPlayerViews.FindAsync(serverId, item.AccountId);
                if (item.ViewUnchanged)
                {
                    acceptedView = view.View.ValueKind is JsonValueKind.Undefined or JsonValueKind.Null
                        && cached is not null && cached.AccountSessionId == owner.AccountSessionId
                        && cached.ServerSessionId == request.ServerSessionId && cached.DefinitionGenerationId == request.DefinitionGenerationId
                        && cached.PlayerStateVersion == view.PlayerStateVersion && cached.EvaluationFingerprint == view.EvaluationFingerprint
                        && cached.EditEligible == view.EditEligible && !cached.OfflineConfirmed
                        && ValidHash(item.ViewContentHash) && FixedEquals(Hash(cached.ViewJson), item.ViewContentHash!);
                    if (acceptedView) cached!.LastSeenUtc = now;
                }
                else if (IsValidView(view))
                {
                    await UpsertViewAsync(runtime, item.AccountId, view.PlayerStateVersion, view.EvaluationFingerprint, view.EditEligible, view.View, now);
                    cached = await dbContext.SkillTreeServerPlayerViews.FindAsync(serverId, item.AccountId);
                    cached!.AccountSessionId = owner.AccountSessionId;
                    cached.OfflineConfirmed = false;
                    acceptedView = true;
                }
                if (acceptedView)
                {
                    owner.ViewSequence = view.ViewSequence;
                    // account取得時に参加先の旧操作は既に失効している。heartbeatで同じUPDATEを繰り返さない。
                }
            }
        }
        owner.ExpiresAtUtc = now + RuntimeTtl;
        await dbContext.SaveChangesAsync();
        await tx.CommitAsync();
        return new() { AccountId = item.AccountId, Renewed = true, ViewAccepted = acceptedView };
    }
}
