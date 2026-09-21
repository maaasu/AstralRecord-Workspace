using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using AstralRecordApi.Services;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordApi.Repositories;

/// <summary>Web要求を登録し、実ロード済みPlugin sessionだけがsnapshot transactionで確定します。</summary>
public sealed class SkillTreeOperationRepository(AstralRecordDbContext dbContext, INetworkRuntimeService networkRuntimeService) : ISkillTreeOperationRepository
{
    private static readonly TimeSpan RuntimeTtl = TimeSpan.FromSeconds(45);
    private static readonly TimeSpan LeaseTtl = TimeSpan.FromSeconds(30);
    private static readonly TimeSpan OperationTtl = TimeSpan.FromDays(7);
    private const int MaxViewJsonLength = 512 * 1024;

    public async Task<SkillTreeServerRuntimeResponse?> RegisterServerAsync(string serverId, SkillTreeServerRegistrationRequest request)
    {
        if (!ValidServer(serverId) || request.ServerSessionId == Guid.Empty || request.ServerStartedAtUtc == default || !ValidHash(request.DefinitionGenerationId) || string.IsNullOrWhiteSpace(request.PluginVersion) || string.IsNullOrWhiteSpace(request.CompatibilityVersion) || !MatchesGeneration(request.DefinitionGenerationId, request.CanonicalSnapshotJson)) return null;
        var now = DateTime.UtcNow;
        var generation = await dbContext.SkillTreeDefinitionGenerations.FindAsync(request.DefinitionGenerationId);
        if (generation is null) await dbContext.SkillTreeDefinitionGenerations.AddAsync(new SkillTreeDefinitionGenerationEntity { DefinitionGenerationId = request.DefinitionGenerationId, CanonicalSnapshotJson = request.CanonicalSnapshotJson, CreatedAtUtc = now });
        else if (!string.Equals(generation.CanonicalSnapshotJson, request.CanonicalSnapshotJson, StringComparison.Ordinal)) return null;
        var id = serverId.Trim(); var runtime = await dbContext.SkillTreeServerRuntimes.FindAsync(id);
        if (runtime is null) { runtime = new SkillTreeServerRuntimeEntity { ServerId = id }; await dbContext.SkillTreeServerRuntimes.AddAsync(runtime); }
        if (runtime.ServerSessionId != Guid.Empty && runtime.ServerSessionId != request.ServerSessionId && request.ServerStartedAtUtc <= runtime.ServerStartedAtUtc) return null;
        var sessionChanged = runtime.ServerSessionId != Guid.Empty && runtime.ServerSessionId != request.ServerSessionId;
        runtime.ServerSessionId = request.ServerSessionId; runtime.ServerStartedAtUtc = request.ServerStartedAtUtc; runtime.PluginVersion = request.PluginVersion.Trim(); runtime.CompatibilityVersion = request.CompatibilityVersion.Trim(); runtime.DefinitionGenerationId = request.DefinitionGenerationId; runtime.Ready = request.Ready; runtime.LastSeenUtc = now;
        if (sessionChanged) await dbContext.SkillTreeOperations.Where(x => x.TargetServerId == id && x.Status == SkillTreeOperationStatuses.Claimed).ExecuteUpdateAsync(s => s.SetProperty(x => x.Status, SkillTreeOperationStatuses.PendingOnline).SetProperty(x => x.ClaimedServerSessionId, (Guid?)null).SetProperty(x => x.LeaseTokenHash, (string?)null).SetProperty(x => x.LeaseExpiresAtUtc, (DateTime?)null));
        await dbContext.SaveChangesAsync(); return Runtime(runtime);
    }

    public async Task<SkillTreeServerRuntimeResponse?> HeartbeatServerAsync(string serverId, SkillTreeServerHeartbeatRequest request)
    {
        var runtime = await VerifyRuntimeAsync(serverId, request.ServerSessionId);
        if (runtime is null || runtime.DefinitionGenerationId != request.DefinitionGenerationId) return null;
        runtime.Ready = request.Ready; runtime.LastSeenUtc = DateTime.UtcNow; await dbContext.SaveChangesAsync(); return Runtime(runtime);
    }

    public async Task<SkillTreeEditorResponse?> RegisterPlayerViewAsync(string serverId, Guid accountId, SkillTreePlayerViewRegistrationRequest request)
    {
        if (accountId == Guid.Empty || !IsValidView(request)) return null;
        var runtime = await VerifyRuntimeAsync(serverId, request.ServerSessionId);
        if (runtime is null || runtime.DefinitionGenerationId != request.DefinitionGenerationId) return null;
        var account = await dbContext.Accounts.AsNoTracking().SingleOrDefaultAsync(x => x.Uuid == accountId && !x.IsDeleted);
        var state = await dbContext.AccountSkillTreeStates.AsNoTracking().SingleOrDefaultAsync(x => x.AccountId == accountId && !x.IsDeleted);
        if (account is null || (state?.Version ?? 0) != request.PlayerStateVersion) return null;
        await UpsertViewAsync(runtime, accountId, request.PlayerStateVersion, request.EvaluationFingerprint, request.EditEligible, request.View, DateTime.UtcNow);
        // Offline案は作成時の参加先serverへ固定する。別serverへログインした時点で
        // 読み替えず、古いtargetの要求を再確認へ遷移させる。
        await dbContext.SkillTreeOperations.Where(x => x.AccountId == accountId
                && x.TargetServerId != runtime.ServerId
                && (x.Status == SkillTreeOperationStatuses.PendingOnline || x.Status == SkillTreeOperationStatuses.PendingOffline || x.Status == SkillTreeOperationStatuses.Claimed))
            .ExecuteUpdateAsync(s => s.SetProperty(x => x.Status, SkillTreeOperationStatuses.ReconfirmationRequired)
                .SetProperty(x => x.Reason, "参加先サーバーが変更されたため、最新の状態を再確認してください。")
                .SetProperty(x => x.CompletedAtUtc, DateTime.UtcNow)
                .SetProperty(x => x.LeaseTokenHash, (string?)null)
                .SetProperty(x => x.LeaseExpiresAtUtc, (DateTime?)null));
        await dbContext.SaveChangesAsync(); return await BuildEditorAsync(account, state, serverId, null);
    }

    public async Task<SkillTreeEditorResponse?> GetEditorAsync(Guid accountId, Guid actorUserId, string? targetServerId = null)
    {
        var account = await dbContext.Accounts.AsNoTracking().SingleOrDefaultAsync(x => x.Uuid == accountId && !x.IsDeleted && x.UserId == actorUserId);
        if (account is null) return null;
        var state = await dbContext.AccountSkillTreeStates.AsNoTracking().SingleOrDefaultAsync(x => x.AccountId == accountId && !x.IsDeleted);
        return await BuildEditorAsync(account, state, targetServerId, actorUserId);
    }

    public async Task<SkillTreeOperationResponse?> CreateAsync(Guid accountId, SkillTreeOperationCreateRequest request)
    {
        if (!ValidOperation(request)) return null;
        var account = await dbContext.Accounts.SingleOrDefaultAsync(x => x.Uuid == accountId && !x.IsDeleted && x.UserId == request.ActorUserId);
        if (account is null) return null;
        var hash = Hash(JsonSerializer.Serialize(request)); var existing = await dbContext.SkillTreeOperations.SingleOrDefaultAsync(x => x.OperationId == request.OperationId);
        if (existing is not null) return existing.AccountId == accountId && existing.RequestHash == hash ? Map(existing) : null;
        var runtime = await CurrentRuntimeAsync(request.TargetServerId); var state = await dbContext.AccountSkillTreeStates.AsNoTracking().SingleOrDefaultAsync(x => x.AccountId == accountId && !x.IsDeleted); var view = runtime is null ? null : await CurrentViewAsync(runtime, accountId);
        var presence = networkRuntimeService.GetPlayers().SingleOrDefault(x => x.Uuid == request.ActorUserId && x.AccountId == accountId); var online = presence is not null;
        var reason = runtime is null || view is null ? "サーバーの更新待ちです。最新の状態を再取得してください。" : state?.DefinitionGenerationId is null ? "既存のスキルツリー状態は移行確認が必要です。" : state.DefinitionGenerationId != request.ExpectedDefinitionGenerationId || view.DefinitionGenerationId != request.ExpectedDefinitionGenerationId || view.PlayerStateVersion != request.ExpectedPlayerStateVersion ? "最新の状態を再取得してください。" : online && (!string.Equals(presence!.ServerId, runtime.ServerId, StringComparison.OrdinalIgnoreCase) || !view.EditEligible) ? "ログイン中は拠点またはスキルツリーワールドでのみ編集できます。" : null;
        var now = DateTime.UtcNow;
        var item = new SkillTreeOperationEntity { OperationId = request.OperationId, AccountId = accountId, ActorUserId = request.ActorUserId, RequestHash = hash, TargetServerId = request.TargetServerId.Trim(), ExpectedDefinitionGenerationId = request.ExpectedDefinitionGenerationId, ExpectedPlayerStateVersion = request.ExpectedPlayerStateVersion, ExpectedEvaluationFingerprint = view?.EvaluationFingerprint ?? string.Empty, Action = request.Action.Trim().ToUpperInvariant(), NodeId = request.NodeId.Trim(), SourceClassId = BlankToNull(request.SourceClassId), Status = reason is null ? (online ? SkillTreeOperationStatuses.PendingOnline : SkillTreeOperationStatuses.PendingOffline) : SkillTreeOperationStatuses.Canceled, Reason = reason, CreatedAtUtc = now, ExpiresAtUtc = now + OperationTtl, CompletedAtUtc = reason is null ? null : now };
        if (reason is null && await dbContext.SkillTreeOperations.AnyAsync(x => x.AccountId == accountId && SkillTreeOperationStatuses.IsActive(x.Status))) return null;
        await dbContext.SkillTreeOperations.AddAsync(item); await dbContext.SaveChangesAsync(); return Map(item);
    }

    public async Task<SkillTreeOperationResponse?> FindAsync(Guid accountId, Guid operationId, Guid actorUserId) => await dbContext.SkillTreeOperations.AsNoTracking().Where(x => x.AccountId == accountId && x.OperationId == operationId && x.ActorUserId == actorUserId).Select(x => Map(x)).SingleOrDefaultAsync();
    public async Task<SkillTreeOperationResponse?> CancelAsync(Guid accountId, Guid operationId, Guid actorUserId)
    {
        var item = await dbContext.SkillTreeOperations.SingleOrDefaultAsync(x => x.AccountId == accountId && x.OperationId == operationId && x.ActorUserId == actorUserId);
        if (item is null || !SkillTreeOperationStatuses.IsActive(item.Status)) return item is null ? null : Map(item);
        item.Status = SkillTreeOperationStatuses.Canceled; item.Reason = "ユーザーが取消しました。"; item.CompletedAtUtc = DateTime.UtcNow; item.LeaseTokenHash = null; item.LeaseExpiresAtUtc = null; await dbContext.SaveChangesAsync(); return Map(item);
    }
    public async Task<IReadOnlyList<SkillTreeOperationResponse>?> GetClaimableAsync(string serverId, Guid serverSessionId, Guid accountId)
    {
        var runtime = await VerifyRuntimeAsync(serverId, serverSessionId); if (runtime is null) return null; await ExpireAsync();
        return await dbContext.SkillTreeOperations.AsNoTracking().Where(x => x.AccountId == accountId && x.TargetServerId == runtime.ServerId && (x.Status == SkillTreeOperationStatuses.PendingOnline || x.Status == SkillTreeOperationStatuses.PendingOffline)).Select(x => Map(x)).ToArrayAsync();
    }
    public async Task<SkillTreeOperationClaimResponse?> ClaimAsync(string serverId, Guid operationId, SkillTreeOperationClaimRequest request)
    {
        var runtime = await VerifyRuntimeAsync(serverId, request.ServerSessionId); if (runtime is null) return null;
        var view = await CurrentViewAsync(runtime, request.AccountId); if (view is null) return null;
        var item = await dbContext.SkillTreeOperations.SingleOrDefaultAsync(x => x.OperationId == operationId && x.AccountId == request.AccountId && x.TargetServerId == runtime.ServerId);
        if (item is null || item.ExpiresAtUtc <= DateTime.UtcNow) return null;
        if (item.ExpectedDefinitionGenerationId != runtime.DefinitionGenerationId || item.ExpectedPlayerStateVersion != view.PlayerStateVersion || item.ExpectedEvaluationFingerprint != view.EvaluationFingerprint)
        {
            item.Status = SkillTreeOperationStatuses.ReconfirmationRequired;
            item.Reason = "定義、状態または残高・条件が変更されたため、再確認してください。";
            item.CompletedAtUtc = DateTime.UtcNow;
            await dbContext.SaveChangesAsync();
            return null;
        }
        if (!view.EditEligible) { if (item.Status is SkillTreeOperationStatuses.PendingOnline or SkillTreeOperationStatuses.PendingOffline) { item.Status = SkillTreeOperationStatuses.Canceled; item.Reason = "拠点またはスキルツリーワールド外のため取消しました。"; item.CompletedAtUtc = DateTime.UtcNow; await dbContext.SaveChangesAsync(); } return null; }
        if (item.Status == SkillTreeOperationStatuses.Claimed && item.ClaimedServerSessionId != request.ServerSessionId) return null;
        if (item.Status is not (SkillTreeOperationStatuses.PendingOnline or SkillTreeOperationStatuses.PendingOffline or SkillTreeOperationStatuses.Claimed)) return null;
        var token = Convert.ToHexString(RandomNumberGenerator.GetBytes(32)).ToLowerInvariant(); item.Status = SkillTreeOperationStatuses.Claimed; item.ClaimedServerSessionId = request.ServerSessionId; item.LeaseTokenHash = Hash(token); item.LeaseExpiresAtUtc = DateTime.UtcNow + LeaseTtl; await dbContext.SaveChangesAsync(); return new SkillTreeOperationClaimResponse { LeaseToken = token, LeaseExpiresAtUtc = item.LeaseExpiresAtUtc.Value, Operation = Map(item) };
    }

    public async Task<bool> ValidateRuntimeStateSaveAsync(string serverId, Guid serverSessionId, string definitionGenerationId)
    {
        if (!ValidServer(serverId) || serverSessionId == Guid.Empty || !ValidHash(definitionGenerationId)) return false;
        var runtime = await VerifyRuntimeAsync(serverId, serverSessionId);
        return runtime is not null && runtime.DefinitionGenerationId == definitionGenerationId;
    }

    public async Task<bool> CompleteFromSnapshotAsync(Guid accountId, PlayerStateSkillTreeOperationSection section, DateTime now)
    {
        if (section.OperationId == Guid.Empty || string.IsNullOrWhiteSpace(section.LeaseToken) || !ValidHash(section.DefinitionGenerationId) || !ValidHash(section.FinalEvaluationFingerprint) || section.FinalStatus is not (SkillTreeOperationStatuses.Applied or SkillTreeOperationStatuses.ReconfirmationRequired or SkillTreeOperationStatuses.Failed or SkillTreeOperationStatuses.Canceled)) return false;
        var item = await dbContext.SkillTreeOperations.SingleOrDefaultAsync(x => x.OperationId == section.OperationId && x.AccountId == accountId); var runtime = await dbContext.SkillTreeServerRuntimes.SingleOrDefaultAsync(x => x.ServerId == section.ServerId);
        if (item is null || runtime is null || !IsLive(runtime) || !runtime.Ready || runtime.ServerSessionId != section.ServerSessionId || runtime.DefinitionGenerationId != section.DefinitionGenerationId || item.ExpectedDefinitionGenerationId != section.DefinitionGenerationId || item.Status != SkillTreeOperationStatuses.Claimed || item.ClaimedServerSessionId != section.ServerSessionId || item.LeaseExpiresAtUtc < now || !FixedEquals(item.LeaseTokenHash, Hash(section.LeaseToken))) return false;
        var state = await dbContext.AccountSkillTreeStates.SingleOrDefaultAsync(x => x.AccountId == accountId && !x.IsDeleted);
        if ((state?.Version ?? 0) != section.FinalPlayerStateVersion || state?.DefinitionGenerationId != section.DefinitionGenerationId) return false;
        if (section.EvaluatedView.HasValue && IsViewPayload(section.EvaluatedView.Value)) await UpsertViewAsync(runtime, accountId, state.Version, section.FinalEvaluationFingerprint, true, section.EvaluatedView.Value, now);
        item.Status = section.FinalStatus; item.Reason = BlankToNull(section.FailureReason); item.CompletedAtUtc = now; item.LeaseTokenHash = null; item.LeaseExpiresAtUtc = null; return true;
    }

    private async Task<SkillTreeEditorResponse> BuildEditorAsync(AccountEntity account, AccountSkillTreeStateEntity? state, string? targetServerId, Guid? actorUserId)
    {
        var presence = actorUserId is null ? null : networkRuntimeService.GetPlayers().SingleOrDefault(x => x.Uuid == actorUserId && x.AccountId == account.Uuid); var selected = !string.IsNullOrWhiteSpace(targetServerId) ? targetServerId.Trim() : presence?.ServerId;
        if (selected is null) selected = await dbContext.SkillTreeServerPlayerViews.AsNoTracking().Where(x => x.AccountId == account.Uuid).OrderByDescending(x => x.LastSeenUtc).Select(x => x.ServerId).FirstOrDefaultAsync();
        var runtime = selected is null ? null : await CurrentRuntimeAsync(selected); var view = runtime is null ? null : await CurrentViewAsync(runtime, account.Uuid); var online = presence is not null && runtime is not null && string.Equals(presence.ServerId, runtime.ServerId, StringComparison.OrdinalIgnoreCase); var usable = view is not null && state?.DefinitionGenerationId == view.DefinitionGenerationId && state.Version == view.PlayerStateVersion; var canEdit = usable && (online ? view!.EditEligible : true); var connectionStatus = online ? "online" : presence is not null ? "stale" : runtime is null || view is null ? "unknown" : "offline";
        var reason = !usable ? state?.DefinitionGenerationId is null ? "既存のスキルツリー状態は移行確認が必要です。" : "サーバーの更新待ちです。最新の状態を再取得してください。" : online && !view!.EditEligible ? "ログイン中は拠点またはスキルツリーワールドでのみ編集できます。" : null;
        var pending = await dbContext.SkillTreeOperations.AsNoTracking().Where(x => x.AccountId == account.Uuid && SkillTreeOperationStatuses.IsActive(x.Status)).OrderByDescending(x => x.CreatedAtUtc).ToArrayAsync();
        return new SkillTreeEditorResponse { AccountId = account.Uuid, AccountName = account.AccountName, Connection = new SkillTreeEditorConnectionResponse { Status = connectionStatus, ServerId = online ? runtime!.ServerId : selected, ChannelName = presence?.Channel, WorldName = presence?.WorldName, X = presence?.X, Y = presence?.Y, Z = presence?.Z, ObservedAtUtc = presence?.LastSeenUtc, CanEdit = online && view?.EditEligible == true }, GenerationId = view?.DefinitionGenerationId, StateRevision = state?.Version ?? 0, CanEdit = canEdit, HasFreshState = online && usable, BalanceKind = usable ? online ? "LIVE" : "SAVED" : "UNKNOWN", Reason = reason, Tree = GetProperty(view?.ViewJson, "tree"), Points = usable ? GetProperty(view?.ViewJson, "points") : null, RelockGoldCost = usable ? GetDecimalProperty(view?.ViewJson, "relockGoldCost") : null, PendingOperation = pending.Select(Map).FirstOrDefault(), PendingOperations = pending.Select(Map).ToArray() };
    }
    private async Task<SkillTreeServerPlayerViewEntity?> CurrentViewAsync(SkillTreeServerRuntimeEntity runtime, Guid accountId) { var value = await dbContext.SkillTreeServerPlayerViews.SingleOrDefaultAsync(x => x.ServerId == runtime.ServerId && x.AccountId == accountId); return value is not null && value.ServerSessionId == runtime.ServerSessionId && value.DefinitionGenerationId == runtime.DefinitionGenerationId && value.LastSeenUtc >= DateTime.UtcNow - RuntimeTtl ? value : null; }
    private async Task UpsertViewAsync(SkillTreeServerRuntimeEntity runtime, Guid accountId, int version, string fingerprint, bool eligible, JsonElement view, DateTime now) { var item = await dbContext.SkillTreeServerPlayerViews.FindAsync(runtime.ServerId, accountId); if (item is null) { item = new SkillTreeServerPlayerViewEntity { ServerId = runtime.ServerId, AccountId = accountId }; await dbContext.SkillTreeServerPlayerViews.AddAsync(item); } item.ServerSessionId = runtime.ServerSessionId; item.DefinitionGenerationId = runtime.DefinitionGenerationId; item.PlayerStateVersion = version; item.EvaluationFingerprint = fingerprint; item.EditEligible = eligible; item.ViewJson = view.GetRawText(); item.LastSeenUtc = now; }
    private async Task<SkillTreeServerRuntimeEntity?> CurrentRuntimeAsync(string serverId) { var runtime = await dbContext.SkillTreeServerRuntimes.SingleOrDefaultAsync(x => x.ServerId == serverId.Trim()); return runtime is { Ready: true } && IsLive(runtime) ? runtime : null; }
    private async Task<SkillTreeServerRuntimeEntity?> VerifyRuntimeAsync(string serverId, Guid sessionId) { var runtime = await dbContext.SkillTreeServerRuntimes.SingleOrDefaultAsync(x => x.ServerId == serverId.Trim()); return runtime is { Ready: true } && runtime.ServerSessionId == sessionId && IsLive(runtime) ? runtime : null; }
    private async Task ExpireAsync() => await dbContext.SkillTreeOperations.Where(x => SkillTreeOperationStatuses.IsActive(x.Status) && x.ExpiresAtUtc <= DateTime.UtcNow).ExecuteUpdateAsync(x => x.SetProperty(v => v.Status, SkillTreeOperationStatuses.Expired).SetProperty(v => v.CompletedAtUtc, DateTime.UtcNow));
    private static bool IsLive(SkillTreeServerRuntimeEntity value) => value.LastSeenUtc >= DateTime.UtcNow - RuntimeTtl;
    private static bool ValidServer(string? value) => !string.IsNullOrWhiteSpace(value) && value.Length <= 64 && value == value.Trim();
    private static bool ValidHash(string? value) => value?.Length == 64 && value.All(c => c is >= '0' and <= '9' or >= 'a' and <= 'f');
    private static bool MatchesGeneration(string id, string json) => !string.IsNullOrWhiteSpace(json) && string.Equals(id, Hash(json), StringComparison.Ordinal);
    private static bool ValidOperation(SkillTreeOperationCreateRequest value) => value.OperationId != Guid.Empty && value.ActorUserId != Guid.Empty && ValidServer(value.TargetServerId) && ValidHash(value.ExpectedDefinitionGenerationId) && value.ExpectedPlayerStateVersion >= 0 && value.Action is "UNLOCK" or "RELOCK" && !string.IsNullOrWhiteSpace(value.NodeId) && value.NodeId.Trim().Length <= 200;
    private static bool IsValidView(SkillTreePlayerViewRegistrationRequest value) => value.ServerSessionId != Guid.Empty && ValidHash(value.DefinitionGenerationId) && ValidHash(value.EvaluationFingerprint) && value.PlayerStateVersion >= 0 && IsViewPayload(value.View);
    private static bool IsViewPayload(JsonElement value) => value.ValueKind == JsonValueKind.Object && value.GetRawText().Length <= MaxViewJsonLength && value.TryGetProperty("tree", out var tree) && tree.ValueKind == JsonValueKind.Object && value.TryGetProperty("points", out var points) && points.ValueKind == JsonValueKind.Object;
    private static string Hash(string value) => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(value))).ToLowerInvariant();
    private static bool FixedEquals(string? left, string right) => left is not null && CryptographicOperations.FixedTimeEquals(Encoding.UTF8.GetBytes(left), Encoding.UTF8.GetBytes(right));
    private static string? BlankToNull(string? value) => string.IsNullOrWhiteSpace(value) ? null : value.Trim();
    private static SkillTreeServerRuntimeResponse Runtime(SkillTreeServerRuntimeEntity value) => new() { ServerId = value.ServerId, ServerSessionId = value.ServerSessionId, DefinitionGenerationId = value.DefinitionGenerationId, Ready = value.Ready, LastSeenUtc = value.LastSeenUtc };
    private static SkillTreeOperationResponse Map(SkillTreeOperationEntity value) => new() { OperationId = value.OperationId, AccountId = value.AccountId, TargetServerId = value.TargetServerId, ExpectedDefinitionGenerationId = value.ExpectedDefinitionGenerationId, ExpectedPlayerStateVersion = value.ExpectedPlayerStateVersion, ExpectedEvaluationFingerprint = value.ExpectedEvaluationFingerprint, Action = value.Action, NodeId = value.NodeId, SourceClassId = value.SourceClassId, Status = value.Status, Reason = value.Reason, CreatedAtUtc = value.CreatedAtUtc, ExpiresAtUtc = value.ExpiresAtUtc, CompletedAtUtc = value.CompletedAtUtc };
    private static JsonElement? GetProperty(string? json, string name) { if (json is null) return null; try { using var doc = JsonDocument.Parse(json); return doc.RootElement.TryGetProperty(name, out var value) ? value.Clone() : null; } catch (JsonException) { return null; } }
    private static decimal? GetDecimalProperty(string? json, string name) { var value = GetProperty(json, name); return value is { ValueKind: JsonValueKind.Number } && value.Value.TryGetDecimal(out var result) ? result : null; }
}
