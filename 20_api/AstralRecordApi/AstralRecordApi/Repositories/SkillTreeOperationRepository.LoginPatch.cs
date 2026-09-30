using System.Data;
using System.Text.Json;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordApi.Repositories;

public sealed partial class SkillTreeOperationRepository
{
    /// <summary>管理キーによる明示公開だけで公開順を採番します。同一定義の再起動では番号を増やしません。</summary>
    public Task<SkillTreePatchPublishResponse?> PublishPatchAsync(string serverId, Guid sessionId, SkillTreePatchPublishRequest request) =>
        ExecuteRuntimeTransactionAsync(() => PublishPatchCoreAsync(serverId, sessionId, request));

    private async Task<SkillTreePatchPublishResponse?> PublishPatchCoreAsync(string serverId, Guid sessionId, SkillTreePatchPublishRequest request)
    {
        if (!ValidServer(serverId) || sessionId == Guid.Empty || !ValidHash(request.DefinitionGenerationId)) return null;
        await using var transaction = await dbContext.Database.BeginTransactionAsync(IsolationLevel.Serializable);
        var runtime = await VerifyRuntimeAsync(serverId, sessionId);
        if (runtime?.DefinitionGenerationId != request.DefinitionGenerationId) return null;
        var generations = await ReadPatchCatalogForUpdateAsync();
        var target = generations.SingleOrDefault(x => x.DefinitionGenerationId == request.DefinitionGenerationId);
        if (target is null) return null;
        var version = target.PatchVersion;
        if (version is null or 0)
        {
            // 時計や登録時刻では比較しない。別定義の公開をまたいだ候補を再起動で昇格させない。
            var latest = generations.Max(x => x.PatchVersion ?? 0);
            if (target.IntroducedAfterPatchVersion != latest) return null;
            version = checked(latest + 1);
            await dbContext.SkillTreeDefinitionGenerations.Where(x => x.DefinitionGenerationId == target.DefinitionGenerationId)
                .ExecuteUpdateAsync(s => s.SetProperty(x => x.PatchVersion, version));
        }
        await transaction.CommitAsync();
        return new() { DefinitionGenerationId = target.DefinitionGenerationId, PatchVersion = version.Value };
    }

    /// <summary>定義登録と公開の順序を直列化し、大きなsnapshot JSONを取得せず採番情報だけ読みます。</summary>
    private async Task<List<PatchCatalogEntry>> ReadPatchCatalogForUpdateAsync()
    {
        var query = dbContext.Database.IsSqlServer()
            ? dbContext.SkillTreeDefinitionGenerations.FromSqlRaw("SELECT * FROM [dbo].[skilltree_definition_generation] WITH (UPDLOCK,HOLDLOCK)")
            : dbContext.SkillTreeDefinitionGenerations.AsQueryable();
        return await query.Select(x => new PatchCatalogEntry(x.DefinitionGenerationId, x.PatchVersion, x.IntroducedAfterPatchVersion)).ToListAsync();
    }

    private sealed record PatchCatalogEntry(string DefinitionGenerationId, long? PatchVersion, long IntroducedAfterPatchVersion);

    /// <summary>参加前の所有sessionだけが保持移行できます。再送は現世代判定で終わり二重適用しません。</summary>
    public Task<SkillTreeLoginPatchResponse?> PrepareLoginPatchAsync(string serverId, Guid accountId, SkillTreeLoginPatchRequest request) =>
        ExecuteRuntimeTransactionAsync(() => PrepareLoginPatchCoreAsync(serverId, accountId, request));

    private async Task<SkillTreeLoginPatchResponse?> PrepareLoginPatchCoreAsync(string serverId, Guid accountId, SkillTreeLoginPatchRequest request)
    {
        if (!ValidServer(serverId) || accountId == Guid.Empty || request.ServerSessionId == Guid.Empty
            || request.AccountSessionId == Guid.Empty || !ValidHash(request.DefinitionGenerationId)
            || !ValidHash(request.AccountLeaseToken) || request.ExpectedStateVersion < 0) return null;
        await using var transaction = await dbContext.Database.BeginTransactionAsync(IsolationLevel.Serializable);
        var account = await LockAccountAsync(accountId);
        var runtime = await VerifyRuntimeAsync(serverId, request.ServerSessionId);
        var owner = await MatchingSessionAsync(accountId, serverId, request.ServerSessionId, request.AccountSessionId, request.AccountLeaseToken);
        if (account is null || runtime?.DefinitionGenerationId != request.DefinitionGenerationId || owner is null
            || owner.DefinitionGenerationId != request.DefinitionGenerationId || owner.ViewSequence != 0) return null;
        var target = await dbContext.SkillTreeDefinitionGenerations.FindAsync(request.DefinitionGenerationId);
        var state = await dbContext.AccountSkillTreeStates.Include(x => x.UnlockedNodes)
            .SingleOrDefaultAsync(x => x.AccountId == accountId && !x.IsDeleted);
        SkillTreeLoginPatchResponse Result(string status) => new()
        {
            Status = status, DefinitionGenerationId = request.DefinitionGenerationId,
            PatchVersion = target?.PatchVersion, StateVersion = state?.Version ?? 0,
        };
        if (target?.PatchVersion is null or <= 0) return Result(SkillTreeLoginPatchStatuses.Unpublished);
        if (state is null || state.DefinitionGenerationId == target.DefinitionGenerationId
            || state.DefinitionGenerationId is null && state.UnlockedNodes.Count == 0)
            return Result(SkillTreeLoginPatchStatuses.Current);
        if (state.DefinitionGenerationId is null) return Result(SkillTreeLoginPatchStatuses.LegacyRequiresRepair);
        var source = await dbContext.SkillTreeDefinitionGenerations.FindAsync(state.DefinitionGenerationId);
        if (source?.PatchVersion is null) return Result(SkillTreeLoginPatchStatuses.LegacyRequiresRepair);
        if (source.PatchVersion >= target.PatchVersion) return Result(SkillTreeLoginPatchStatuses.ChannelOutdated);
        if (ValidateRetirementMigration(source.CanonicalSnapshotJson, target.CanonicalSnapshotJson, state.UnlockedNodes, []) is null)
            return Result(SkillTreeLoginPatchStatuses.Incompatible);
        if (!request.Apply) return Result(SkillTreeLoginPatchStatuses.UpdateRequired);
        if (state.Version != request.ExpectedStateVersion) return null;
        // 一つの参加処理で複数の異なる移行を行わない。応答消失後の同世代再送は上でCURRENTになる。
        if (await dbContext.SkillTreeMigrationOperations.AnyAsync(x => x.OperationId == request.AccountSessionId)) return null;
        var fromGeneration = state.DefinitionGenerationId;
        var previousVersion = state.Version;
        var now = DateTime.UtcNow;
        var baseline = state.UnlockedNodes.Select(x => x.NodeId).Order(StringComparer.Ordinal).ToArray();
        state.DefinitionGenerationId = target.DefinitionGenerationId;
        state.Version = checked(state.Version + 1);
        state.UpdatedAt = now;
        state.UpdatedBy = account.UserId;
        owner.ExpiresAtUtc = now + RuntimeTtl;
        await dbContext.SkillTreeOperations.Where(x => x.AccountId == accountId && ActiveStatuses.Contains(x.Status))
            .ExecuteUpdateAsync(s => s.SetProperty(x => x.Status, SkillTreeOperationStatuses.Canceled)
                .SetProperty(x => x.Reason, "ログイン時のパッチ適用により未適用操作を取消しました。")
                .SetProperty(x => x.CompletedAtUtc, now).SetProperty(x => x.LeaseTokenHash, (string?)null)
                .SetProperty(x => x.LeaseExpiresAtUtc, (DateTime?)null));
        await dbContext.SkillTreeServerPlayerViews.Where(x => x.AccountId == accountId).ExecuteDeleteAsync();
        dbContext.SkillTreeMigrationOperations.Add(new SkillTreeMigrationOperationEntity
        {
            OperationId = request.AccountSessionId, AccountId = accountId,
            RequestHash = Hash(JsonSerializer.Serialize(new { accountId, fromGeneration, request.DefinitionGenerationId, previousVersion })),
            ExpectedStateVersion = previousVersion, FromGenerationId = fromGeneration,
            ToGenerationId = target.DefinitionGenerationId, BaselineNodeIdsJson = JsonSerializer.Serialize(baseline),
            RemovedNodeIdsJson = "[]", Status = "APPLIED", CompletedAtUtc = now,
            ResultJson = JsonSerializer.Serialize(new SkillTreeMigrationResponse
            {
                OperationId = request.AccountSessionId, Status = "APPLIED", StateVersion = state.Version,
                RemovedNodeIds = [], Refunds = [], ConsumedClassAssignments = [],
            }),
        });
        await dbContext.SaveChangesAsync();
        await transaction.CommitAsync();
        return Result(SkillTreeLoginPatchStatuses.Applied);
    }
}
