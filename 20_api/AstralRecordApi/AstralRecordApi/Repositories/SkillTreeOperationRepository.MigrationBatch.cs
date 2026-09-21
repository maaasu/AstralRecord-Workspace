using AstralRecordApi.Models;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordApi.Repositories;

public sealed partial class SkillTreeOperationRepository
{
    private const int MaxMigrationBatchSize = 100;
    private const int MaxMigrationCandidatePageSize = 500;

    /// <summary>
    /// 現在稼働中で編集互換性を満たすPlugin runtimeを返します。
    /// </summary>
    /// <param name="serverId">Pluginが登録したサーバーID</param>
    /// <returns>稼働中runtime。未登録・期限切れ・非readyの場合はnull</returns>
    public async Task<SkillTreeServerRuntimeResponse?> GetServerRuntimeAsync(string serverId)
    {
        if (!ValidServer(serverId)) return null;
        return await CurrentRuntimeAsync(serverId) is { } runtime ? Runtime(runtime) : null;
    }

    /// <summary>
    /// 現在世代と異なり、明示移行が必要な保存状態をページ単位で返します。
    /// legacy空状態はPlugin参加時に自動bindされるため候補から除外します。
    /// </summary>
    /// <param name="serverId">移行先PluginのサーバーID</param>
    /// <param name="sessionId">移行先Pluginの起動session ID</param>
    /// <param name="toGenerationId">移行先の実ロード世代</param>
    /// <param name="page">1始まりのページ番号</param>
    /// <param name="pageSize">1ページの最大件数</param>
    /// <returns>候補ページ。runtimeまたは入力が無効な場合はnull</returns>
    public async Task<SkillTreeMigrationCandidatePageResponse?> GetMigrationCandidatesAsync(
        string serverId,
        Guid sessionId,
        string toGenerationId,
        int page,
        int pageSize)
    {
        if (!ValidServer(serverId) || sessionId == Guid.Empty || !ValidHash(toGenerationId)
            || page < 1 || pageSize is < 1 or > MaxMigrationCandidatePageSize
            || page - 1 > int.MaxValue / pageSize) return null;
        var runtime = await VerifyRuntimeAsync(serverId, sessionId);
        if (runtime?.DefinitionGenerationId != toGenerationId) return null;

        var query =
            from state in dbContext.AccountSkillTreeStates.AsNoTracking()
            join account in dbContext.Accounts.AsNoTracking() on state.AccountId equals account.Uuid
            where !state.IsDeleted && !account.IsDeleted
                && (state.DefinitionGenerationId == null && state.UnlockedNodes.Any()
                    || state.DefinitionGenerationId != null && state.DefinitionGenerationId != toGenerationId)
            select new
            {
                StateId = state.AccountSkillTreeStateId,
                state.AccountId,
                account.UserId,
                account.AccountName,
                FromGenerationId = state.DefinitionGenerationId,
                state.Version,
            };
        var totalCount = await query.CountAsync();
        var pageRows = await query.OrderBy(x => x.AccountId)
            .Skip((page - 1) * pageSize)
            .Take(pageSize)
            .ToArrayAsync();
        var stateIds = pageRows.Select(x => x.StateId).ToArray();
        var nodeRows = stateIds.Length == 0
            ? []
            : await dbContext.AccountSkillTreeUnlockedNodes.AsNoTracking()
                .Where(x => stateIds.Contains(x.AccountSkillTreeStateId))
                .Select(x => new { x.AccountSkillTreeStateId, x.NodeId })
                .ToListAsync();
        var nodesByState = nodeRows.GroupBy(x => x.AccountSkillTreeStateId)
            .ToDictionary(
                group => group.Key,
                group => (IReadOnlyList<string>)group.Select(x => x.NodeId).Order(StringComparer.Ordinal).ToArray());

        return new()
        {
            Runtime = Runtime(runtime),
            Page = page,
            PageSize = pageSize,
            TotalCount = totalCount,
            Items = pageRows.Select(row => new SkillTreeMigrationCandidate
            {
                AccountId = row.AccountId,
                UserId = row.UserId,
                AccountName = row.AccountName,
                FromGenerationId = row.FromGenerationId,
                ExpectedStateVersion = row.Version,
                LegacyBaselineNodeIds = nodesByState.GetValueOrDefault(row.StateId, []),
            }).ToArray(),
        };
    }

    /// <summary>
    /// 既存の単一アカウント移行を入力順に実行し、失敗したアカウントがあっても後続を継続します。
    /// 各アカウントのtransaction、offline判定、version・baseline検証、冪等台帳は単体移行に委ねます。
    /// </summary>
    /// <param name="serverId">移行先PluginのサーバーID</param>
    /// <param name="sessionId">移行先Pluginの起動session ID</param>
    /// <param name="request">一括移行要求</param>
    /// <returns>アカウント別結果。runtimeまたは要求全体が無効な場合はnull</returns>
    public async Task<SkillTreeMigrationBatchResponse?> MigrateBatchAsync(
        string serverId,
        Guid sessionId,
        SkillTreeMigrationBatchRequest request)
    {
        if (!ValidServer(serverId) || sessionId == Guid.Empty || request.Items is null
            || request.Items.Count is < 1 or > MaxMigrationBatchSize
            || request.Mode is not (SkillTreeMigrationBatchModes.Preview or SkillTreeMigrationBatchModes.Commit)
            || request.Items.Any(item => item is null || item.AccountId == Guid.Empty || item.Migration is null
                || item.Migration.OperationId == Guid.Empty || item.Migration.ExpectedStateVersion < 0
                || !ValidHash(item.Migration.ToGenerationId)
                || item.Migration.FromGenerationId is not null && !ValidHash(item.Migration.FromGenerationId)
                || item.Migration.LegacyBaselineNodeIds is null || item.Migration.RemoveNodeIds is null
                || item.Migration.LegacyBaselineNodeIds.Distinct(StringComparer.Ordinal).Count() != item.Migration.LegacyBaselineNodeIds.Count
                || item.Migration.RemoveNodeIds.Distinct(StringComparer.Ordinal).Count() != item.Migration.RemoveNodeIds.Count)
            || request.Items.Select(item => item.AccountId).Distinct().Count() != request.Items.Count
            || request.Items.Select(item => item.Migration.OperationId).Distinct().Count() != request.Items.Count
            || request.Items.Select(item => item.Migration.ToGenerationId).Distinct(StringComparer.Ordinal).Count() != 1) return null;
        var runtime = await VerifyRuntimeAsync(serverId, sessionId);
        if (runtime is null || request.Items.Any(item => item.Migration.ToGenerationId != runtime.DefinitionGenerationId)) return null;

        var preview = request.Mode == SkillTreeMigrationBatchModes.Preview;
        var items = new List<SkillTreeMigrationBatchItemResponse>(request.Items.Count);
        foreach (var item in request.Items)
        {
            var source = item.Migration;
            var migration = new SkillTreeMigrationRequest
            {
                OperationId = source.OperationId,
                ExpectedStateVersion = source.ExpectedStateVersion,
                FromGenerationId = source.FromGenerationId,
                ToGenerationId = source.ToGenerationId,
                LegacyBaselineNodeIds = source.LegacyBaselineNodeIds,
                RemoveNodeIds = source.RemoveNodeIds,
                ConfirmLegacyBaseline = source.ConfirmLegacyBaseline,
                PreviewOnly = preview,
            };
            var result = await MigrateAsync(serverId, sessionId, item.AccountId, migration);
            items.Add(new()
            {
                AccountId = item.AccountId,
                OperationId = migration.OperationId,
                Status = result?.Status ?? "REJECTED",
                Migration = result,
            });
            dbContext.ChangeTracker.Clear();
        }

        return new()
        {
            Mode = request.Mode,
            AcceptedCount = items.Count(item => item.Migration is not null),
            RejectedCount = items.Count(item => item.Migration is null),
            Items = items,
        };
    }
}
