using System.Data;
using System.Text.Json;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordApi.Repositories;

public sealed partial class SkillTreeOperationRepository
{
    public Task<SkillTreeMigrationResponse?> MigrateAsync(string serverId, Guid sessionId, Guid accountId, SkillTreeMigrationRequest request) =>
        ExecuteRuntimeTransactionAsync(() => MigrateCoreAsync(serverId, sessionId, accountId, request));

    private async Task<SkillTreeMigrationResponse?> MigrateCoreAsync(string serverId, Guid sessionId, Guid accountId, SkillTreeMigrationRequest request)
    {
        if (request.OperationId == Guid.Empty || request.ExpectedStateVersion < 0 || !ValidHash(request.ToGenerationId)
            || request.LegacyBaselineNodeIds is null || request.RemoveNodeIds is null) return null;
        await using var transaction = await dbContext.Database.BeginTransactionAsync(IsolationLevel.Serializable);
        var account = await LockAccountAsync(accountId);
        if (account is null) return null;
        var requestHash = Hash(JsonSerializer.Serialize(new { accountId, request }));
        var previous = await dbContext.SkillTreeMigrationOperations.FindAsync(request.OperationId);
        if (previous is not null)
            return previous.AccountId == accountId && previous.RequestHash == requestHash
                ? JsonSerializer.Deserialize<SkillTreeMigrationResponse>(previous.ResultJson) : null;
        var runtime = await VerifyRuntimeAsync(serverId, sessionId);
        if (runtime?.DefinitionGenerationId != request.ToGenerationId || await ActiveSessionAsync(accountId) is not null
            || networkRuntimeService.GetPlayers().Any(x => x.Uuid == account.UserId)) return null;
        await ExpireAsync(accountId);
        if (await dbContext.SkillTreeOperations.AnyAsync(x => x.AccountId == accountId && ActiveStatuses.Contains(x.Status))) return null;
        var state = await dbContext.AccountSkillTreeStates.Include(x => x.UnlockedNodes)
            .SingleOrDefaultAsync(x => x.AccountId == accountId && !x.IsDeleted);
        if (state is null || state.Version != request.ExpectedStateVersion || state.DefinitionGenerationId != request.FromGenerationId
            || state.DefinitionGenerationId is null && !request.ConfirmLegacyBaseline) return null;
        var actual = state.UnlockedNodes.Select(x => x.NodeId).Order(StringComparer.Ordinal).ToArray();
        if (!actual.SequenceEqual(request.LegacyBaselineNodeIds.Order(StringComparer.Ordinal), StringComparer.Ordinal)
            || request.RemoveNodeIds.Distinct(StringComparer.Ordinal).Count() != request.RemoveNodeIds.Count
            || request.RemoveNodeIds.Except(actual, StringComparer.Ordinal).Any()) return null;
        var target = await dbContext.SkillTreeDefinitionGenerations.FindAsync(request.ToGenerationId);
        var source = state.DefinitionGenerationId is null ? target : await dbContext.SkillTreeDefinitionGenerations.FindAsync(state.DefinitionGenerationId);
        if (source is null || target is null) return null;
        var refunds = ValidateRetirementMigration(source.CanonicalSnapshotJson, target.CanonicalSnapshotJson, state.UnlockedNodes, request.RemoveNodeIds);
        if (refunds is null) return null;
        var result = new SkillTreeMigrationResponse
        {
            OperationId = request.OperationId, Status = request.PreviewOnly ? "PREVIEW" : "APPLIED",
            StateVersion = checked(state.Version + 1), RemovedNodeIds = request.RemoveNodeIds, Refunds = refunds,
        };
        if (request.PreviewOnly) return result;
        dbContext.AccountSkillTreeUnlockedNodes.RemoveRange(state.UnlockedNodes.Where(x => request.RemoveNodeIds.Contains(x.NodeId, StringComparer.Ordinal)));
        state.DefinitionGenerationId = request.ToGenerationId;
        state.Version = result.StateVersion;
        state.UpdatedAt = DateTime.UtcNow;
        state.UpdatedBy = account.UserId;
        dbContext.SkillTreeMigrationOperations.Add(new SkillTreeMigrationOperationEntity
        {
            OperationId = request.OperationId, AccountId = accountId, RequestHash = requestHash,
            ExpectedStateVersion = request.ExpectedStateVersion, FromGenerationId = request.FromGenerationId,
            ToGenerationId = request.ToGenerationId, BaselineNodeIdsJson = JsonSerializer.Serialize(actual),
            RemovedNodeIdsJson = JsonSerializer.Serialize(request.RemoveNodeIds), Status = "APPLIED", CompletedAtUtc = DateTime.UtcNow,
            ResultJson = JsonSerializer.Serialize(result),
        });
        await dbContext.SkillTreeServerPlayerViews.Where(x => x.AccountId == accountId).ExecuteDeleteAsync();
        await dbContext.SaveChangesAsync();
        await transaction.CommitAsync();
        return result;
    }

    /// <summary>再課金しない保持・明示除去だけを検証する。ノード追加とCP付替えは許可しない。</summary>
    internal static IReadOnlyList<SkillTreeMigrationRefund>? ValidateRetirementMigration(string sourceJson, string targetJson,
        IEnumerable<AccountSkillTreeUnlockedNodeEntity> unlocked, IReadOnlyList<string> removedIds)
    {
        try
        {
            using var oldDocument = JsonDocument.Parse(sourceJson);
            using var newDocument = JsonDocument.Parse(targetJson);
            var oldRoot = oldDocument.RootElement; var newRoot = newDocument.RootElement;
            var oldNodes = oldRoot.GetProperty("nodes").EnumerateArray().ToDictionary(x => x.GetProperty("nodeId").GetString()!, StringComparer.Ordinal);
            var newNodes = newRoot.GetProperty("nodes").EnumerateArray().ToDictionary(x => x.GetProperty("nodeId").GetString()!, StringComparer.Ordinal);
            var positions = newRoot.GetProperty("positions").EnumerateArray().Select(x => x.GetProperty("nodeId").GetString()!).ToHashSet(StringComparer.Ordinal);
            var removed = removedIds.ToHashSet(StringComparer.Ordinal);
            var retained = new HashSet<string>(StringComparer.Ordinal);
            var refunds = new List<SkillTreeMigrationRefund>();
            foreach (var row in unlocked)
            {
                if (!oldNodes.TryGetValue(row.NodeId, out var oldNode)) return null;
                var kind = oldNode.GetProperty("pointType").GetString(); var cost = oldNode.GetProperty("pointCost").GetInt32();
                if (cost < 0 || kind is not ("CLASS_POINT" or "PASSIVE_POINT")) return null;
                if (removed.Contains(row.NodeId))
                {
                    if (kind == "CLASS_POINT" && cost > 0 && (row.ConsumedClassId is null
                        || !newRoot.TryGetProperty("classes", out var refundClasses) || !refundClasses.TryGetProperty(row.ConsumedClassId, out _))) return null;
                    refunds.Add(new() { NodeId = row.NodeId, PointType = kind == "CLASS_POINT" ? "CP" : "PP", Points = cost, ClassId = row.ConsumedClassId });
                    continue;
                }
                if (!newNodes.TryGetValue(row.NodeId, out var target) || !positions.Contains(row.NodeId)
                    || target.GetProperty("pointType").GetString() != kind || target.GetProperty("pointCost").GetInt32() != cost
                    || !JsonElement.DeepEquals(oldNode.GetProperty("unlockCondition"), target.GetProperty("unlockCondition"))) return null;
                if (kind == "CLASS_POINT" && cost > 0)
                {
                    if (row.ConsumedClassId is null || !newRoot.TryGetProperty("classes", out var classes)
                        || !classes.TryGetProperty(row.ConsumedClassId, out _)) return null;
                    if (oldRoot.TryGetProperty("classes", out var oldClasses) && !JsonElement.DeepEquals(oldClasses, classes)) return null;
                    var fixedSource = target.GetProperty("unlockCondition").GetProperty("classId");
                    if (fixedSource.ValueKind == JsonValueKind.String && fixedSource.GetString() != row.ConsumedClassId) return null;
                }
                retained.Add(row.NodeId);
            }
            if (retained.Count != 0)
            {
                var rootId = newRoot.GetProperty("rootNodeId").GetString()!;
                if (!retained.Contains(rootId)) return null;
                var edges = newRoot.GetProperty("edges").EnumerateArray().Select(x => x.GetString()!.Split("->", StringSplitOptions.None)).ToArray();
                if (edges.Any(x => x.Length != 2)) return null;
                var seen = new HashSet<string>(StringComparer.Ordinal) { rootId };
                var queue = new Queue<string>(); queue.Enqueue(rootId);
                while (queue.TryDequeue(out var current))
                    foreach (var edge in edges)
                    {
                        var adjacent = edge[0] == current ? edge[1] : edge[1] == current ? edge[0] : null;
                        if (adjacent is not null && retained.Contains(adjacent) && seen.Add(adjacent)) queue.Enqueue(adjacent);
                    }
                if (!seen.SetEquals(retained)) return null;
            }
            return refunds;
        }
        catch (Exception exception) when (exception is JsonException or InvalidOperationException or KeyNotFoundException or ArgumentException or OverflowException)
        { return null; }
    }
}
