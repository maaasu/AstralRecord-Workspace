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
            || request.LegacyBaselineNodeIds is null || request.RemoveNodeIds is null || request.ConsumedClassAssignments is null
            || request.ConsumedClassAssignments.Any(assignment => !ValidMigrationAssignment(assignment))
            || request.ConsumedClassAssignments.Select(assignment => assignment.NodeId).Distinct(StringComparer.Ordinal).Count()
                != request.ConsumedClassAssignments.Count) return null;
        await using var transaction = await dbContext.Database.BeginTransactionAsync(IsolationLevel.Serializable);
        var account = await LockAccountAsync(accountId);
        if (account is null) return null;
        var requestHash = MigrationRequestHash(accountId, request);
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
            || request.RemoveNodeIds.Except(actual, StringComparer.Ordinal).Any()
            || request.ConsumedClassAssignments.Select(assignment => assignment.NodeId).Except(actual, StringComparer.Ordinal).Any()
            || request.ConsumedClassAssignments.Select(assignment => assignment.NodeId).Distinct(StringComparer.Ordinal).Count()
                != request.ConsumedClassAssignments.Count) return null;
        var target = await dbContext.SkillTreeDefinitionGenerations.FindAsync(request.ToGenerationId);
        var source = state.DefinitionGenerationId is null ? target : await dbContext.SkillTreeDefinitionGenerations.FindAsync(state.DefinitionGenerationId);
        if (source is null || target is null) return null;
        var assignments = request.ConsumedClassAssignments.ToDictionary(
            assignment => assignment.NodeId, assignment => assignment.ConsumedClassId, StringComparer.Ordinal);
        var refunds = ValidateRetirementMigration(source.CanonicalSnapshotJson, target.CanonicalSnapshotJson, state.UnlockedNodes,
            request.RemoveNodeIds, assignments);
        if (refunds is null) return null;
        var result = new SkillTreeMigrationResponse
        {
            OperationId = request.OperationId, Status = request.PreviewOnly ? "PREVIEW" : "APPLIED",
            StateVersion = checked(state.Version + 1), RemovedNodeIds = request.RemoveNodeIds, Refunds = refunds,
            ConsumedClassAssignments = request.ConsumedClassAssignments,
        };
        if (request.PreviewOnly) return result;
        foreach (var row in state.UnlockedNodes)
            if (row.ConsumedClassId is null && assignments.TryGetValue(row.NodeId, out var consumedClassId))
            {
                row.ConsumedClassId = consumedClassId;
                row.UpdatedAt = DateTime.UtcNow;
                row.UpdatedBy = account.UserId;
            }
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

    private static string MigrationRequestHash(Guid accountId, SkillTreeMigrationRequest request)
    {
        if (request.ConsumedClassAssignments.Count != 0)
            return Hash(JsonSerializer.Serialize(new { accountId, request }));
        var legacyRequest = new
        {
            request.OperationId,
            request.ExpectedStateVersion,
            request.FromGenerationId,
            request.ToGenerationId,
            request.LegacyBaselineNodeIds,
            request.RemoveNodeIds,
            request.ConfirmLegacyBaseline,
            request.PreviewOnly,
        };
        return Hash(JsonSerializer.Serialize(new { accountId, request = legacyRequest }));
    }

    /// <summary>
    /// 再課金しない保持・明示除去だけを検証する。ノード追加とCP付替えは許可しない。
    /// 職業は消費元IDの存続を検証し、表示・能力・使用可能スキル等の定義全体は比較しない。
    /// </summary>
    internal static IReadOnlyList<SkillTreeMigrationRefund>? ValidateRetirementMigration(string sourceJson, string targetJson,
        IEnumerable<AccountSkillTreeUnlockedNodeEntity> unlocked, IReadOnlyList<string> removedIds,
        IReadOnlyDictionary<string, string>? consumedClassAssignments = null)
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
            var assignments = consumedClassAssignments ?? new Dictionary<string, string>();
            var retained = new HashSet<string>(StringComparer.Ordinal);
            var refunds = new List<SkillTreeMigrationRefund>();
            foreach (var row in unlocked)
            {
                if (!oldNodes.TryGetValue(row.NodeId, out var oldNode)) return null;
                var kind = oldNode.GetProperty("pointType").GetString(); var cost = oldNode.GetProperty("pointCost").GetInt32();
                if (cost < 0 || kind is not ("CLASS_POINT" or "PASSIVE_POINT")) return null;
                var consumedClassId = row.ConsumedClassId;
                if (assignments.TryGetValue(row.NodeId, out var assignedClassId))
                {
                    if (kind != "CLASS_POINT" || cost <= 0 || consumedClassId is not null) return null;
                    if (!oldNode.TryGetProperty("unlockCondition", out var oldCondition)
                        || !oldCondition.TryGetProperty("classId", out var oldFixedClass)
                        || oldFixedClass.ValueKind != JsonValueKind.String || oldFixedClass.GetString() != assignedClassId) return null;
                    consumedClassId = assignedClassId;
                }
                if (removed.Contains(row.NodeId))
                {
                    if (assignments.ContainsKey(row.NodeId)) return null;
                    if (kind == "CLASS_POINT" && cost > 0 && (consumedClassId is null
                        || !newRoot.TryGetProperty("classes", out var refundClasses) || !refundClasses.TryGetProperty(consumedClassId, out _))) return null;
                    refunds.Add(new() { NodeId = row.NodeId, PointType = kind == "CLASS_POINT" ? "CP" : "PP", Points = cost, ClassId = consumedClassId });
                    continue;
                }
                if (!newNodes.TryGetValue(row.NodeId, out var target) || !positions.Contains(row.NodeId)
                    || target.GetProperty("pointType").GetString() != kind || target.GetProperty("pointCost").GetInt32() != cost
                    || !MigrationUnlockConditionsMatch(oldNode.GetProperty("unlockCondition"), target.GetProperty("unlockCondition"))) return null;
                if (kind == "CLASS_POINT" && cost > 0)
                {
                    if (consumedClassId is null || !newRoot.TryGetProperty("classes", out var classes)
                        || !classes.TryGetProperty(consumedClassId, out _)) return null;
                    // 保存済みCP消費はノードのコストと消費元IDで決まる。
                    // 無関係な職業や使用可能スキル・表示・能力の変更で保持移行を拒否しない。
                    var fixedSource = target.GetProperty("unlockCondition").GetProperty("classId");
                    if (fixedSource.ValueKind == JsonValueKind.String && fixedSource.GetString() != consumedClassId) return null;
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

    /// <summary>
    /// 解放条件の表示名だけを比較対象から除く。職業ID・必要レベルと未知の条件項目は維持比較し、
    /// 将来追加された条件を表示用項目とみなして無条件に許容しない。
    /// </summary>
    private static bool MigrationUnlockConditionsMatch(JsonElement source, JsonElement target)
    {
        if (source.ValueKind != JsonValueKind.Object || target.ValueKind != JsonValueKind.Object) return false;
        var oldConditions = source.EnumerateObject()
            .Where(property => property.Name != "classDisplayName")
            .ToDictionary(property => property.Name, property => property.Value, StringComparer.Ordinal);
        var newConditions = target.EnumerateObject()
            .Where(property => property.Name != "classDisplayName")
            .ToDictionary(property => property.Name, property => property.Value, StringComparer.Ordinal);
        return oldConditions.Count == newConditions.Count && oldConditions.All(property =>
            newConditions.TryGetValue(property.Key, out var value) && JsonElement.DeepEquals(property.Value, value));
    }
}
