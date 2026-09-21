using System.Data;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using AstralRecordApi.Services;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordApi.Repositories;

/// <summary>Pluginのアカウント処理権限と同じ保存境界でWeb変更要求を確定します。</summary>
public sealed partial class SkillTreeOperationRepository(AstralRecordDbContext dbContext, INetworkRuntimeService networkRuntimeService) : ISkillTreeOperationRepository
{
    private static readonly TimeSpan RuntimeTtl = TimeSpan.FromSeconds(45);
    private static readonly TimeSpan LeaseTtl = TimeSpan.FromSeconds(30);
    private static readonly TimeSpan OperationTtl = TimeSpan.FromDays(7);
    private static readonly string[] ActiveStatuses = [SkillTreeOperationStatuses.PendingOnline, SkillTreeOperationStatuses.PendingOffline, SkillTreeOperationStatuses.Claimed];
    private const string SupportedCompatibility = "skilltree-operation-v1";
    private const int MaxViewJsonLength = 512 * 1024;

    // 本番DbContextのEnableRetryOnFailureとユーザーtransactionを同じ再実行境界に置く。
    private Task<T> ExecuteRuntimeTransactionAsync<T>(Func<Task<T>> action) =>
        dbContext.Database.CreateExecutionStrategy().ExecuteAsync(async () =>
        {
            dbContext.ChangeTracker.Clear();
            return await action();
        });

    public Task<string?> GetDefinitionAsync(string generationId) => dbContext.SkillTreeDefinitionGenerations
        .Where(x => x.DefinitionGenerationId == generationId).Select(x => (string?)x.CanonicalSnapshotJson).SingleOrDefaultAsync();

    public Task<SkillTreeServerRuntimeResponse?> RegisterServerAsync(string serverId, SkillTreeServerRegistrationRequest request) =>
        ExecuteRuntimeTransactionAsync(() => RegisterServerCoreAsync(serverId, request));

    private async Task<SkillTreeServerRuntimeResponse?> RegisterServerCoreAsync(string serverId, SkillTreeServerRegistrationRequest request)
    {
        if (!ValidServer(serverId) || request.ServerSessionId == Guid.Empty || request.ServerStartedAtUtc == default || request.PublicationRevision < 1
            || !ValidHash(request.DefinitionGenerationId) || string.IsNullOrWhiteSpace(request.PluginVersion)
            || request.PluginVersion.Length > 100 || request.CompatibilityVersion?.Length > 100
            || !MatchesGeneration(request.DefinitionGenerationId, request.CanonicalSnapshotJson)) return null;
        await using var tx = await dbContext.Database.BeginTransactionAsync(IsolationLevel.Serializable);
        var runtime = dbContext.Database.IsSqlServer()
            ? await dbContext.SkillTreeServerRuntimes.FromSqlInterpolated($"SELECT * FROM [dbo].[skilltree_server_runtime] WITH (UPDLOCK,HOLDLOCK) WHERE [server_id] = {serverId}").SingleOrDefaultAsync()
            : await dbContext.SkillTreeServerRuntimes.FindAsync(serverId);
        if (runtime is not null && runtime.ServerSessionId != request.ServerSessionId
            && request.ServerStartedAtUtc <= runtime.ServerStartedAtUtc) return null;
        if (runtime is not null && runtime.ServerSessionId == request.ServerSessionId
            && (request.PublicationRevision < runtime.PublicationRevision
                || request.PublicationRevision == runtime.PublicationRevision && runtime.DefinitionGenerationId != request.DefinitionGenerationId)) return null;
        var generation = await dbContext.SkillTreeDefinitionGenerations.FindAsync(request.DefinitionGenerationId);
        if (generation is null)
            dbContext.SkillTreeDefinitionGenerations.Add(new() { DefinitionGenerationId = request.DefinitionGenerationId, CanonicalSnapshotJson = request.CanonicalSnapshotJson, CreatedAtUtc = DateTime.UtcNow });
        else if (generation.CanonicalSnapshotJson != request.CanonicalSnapshotJson) return null;
        if (runtime is null)
        {
            runtime = new() { ServerId = serverId };
            dbContext.SkillTreeServerRuntimes.Add(runtime);
        }
        runtime.ServerSessionId = request.ServerSessionId;
        runtime.ServerStartedAtUtc = request.ServerStartedAtUtc;
        runtime.PublicationRevision = request.PublicationRevision;
        runtime.PluginVersion = request.PluginVersion.Trim();
        runtime.CompatibilityVersion = request.CompatibilityVersion ?? "";
        runtime.DefinitionGenerationId = request.DefinitionGenerationId;
        runtime.Ready = request.Ready && request.CompatibilityVersion == SupportedCompatibility;
        runtime.LastSeenUtc = DateTime.UtcNow;
        await dbContext.SaveChangesAsync();
        await tx.CommitAsync();
        return Runtime(runtime);
    }

    public async Task<SkillTreeServerRuntimeResponse?> HeartbeatServerAsync(string serverId, SkillTreeServerHeartbeatRequest request)
    {
        var now = DateTime.UtcNow;
        var count = await dbContext.SkillTreeServerRuntimes.Where(x => x.ServerId == serverId && x.ServerSessionId == request.ServerSessionId
                && x.DefinitionGenerationId == request.DefinitionGenerationId && x.CompatibilityVersion == SupportedCompatibility)
            .ExecuteUpdateAsync(s => s.SetProperty(x => x.LastSeenUtc, now).SetProperty(x => x.Ready, request.Ready));
        if (count == 0) return null;
        var runtime = await dbContext.SkillTreeServerRuntimes.AsNoTracking().SingleAsync(x => x.ServerId == serverId);
        return Runtime(runtime);
    }

    public Task<SkillTreeEditorResponse?> RegisterPlayerViewAsync(string serverId, Guid accountId, SkillTreePlayerViewRegistrationRequest request) =>
        ExecuteRuntimeTransactionAsync(() => RegisterPlayerViewCoreAsync(serverId, accountId, request));

    private async Task<SkillTreeEditorResponse?> RegisterPlayerViewCoreAsync(string serverId, Guid accountId, SkillTreePlayerViewRegistrationRequest request)
    {
        if (accountId == Guid.Empty || !IsValidView(request)) return null;
        await using var tx = await dbContext.Database.BeginTransactionAsync(IsolationLevel.Serializable);
        var account = await LockAccountAsync(accountId);
        var runtime = await VerifyRuntimeAsync(serverId, request.ServerSessionId);
        var owner = await MatchingSessionAsync(accountId, serverId, request.ServerSessionId, request.AccountSessionId, request.AccountLeaseToken);
        if (account is null || runtime is null || owner is null || runtime.DefinitionGenerationId != request.DefinitionGenerationId) return null;
        var state = await dbContext.AccountSkillTreeStates.SingleOrDefaultAsync(x => x.AccountId == accountId && !x.IsDeleted);
        if ((state?.Version ?? 0) != request.PlayerStateVersion || state?.DefinitionGenerationId != request.DefinitionGenerationId
            || request.ViewSequence <= owner.ViewSequence) return null;
        owner.ViewSequence = request.ViewSequence;
        owner.ExpiresAtUtc = DateTime.UtcNow + RuntimeTtl;
        await UpsertViewAsync(runtime, accountId, request.PlayerStateVersion, request.EvaluationFingerprint, request.EditEligible, request.View, DateTime.UtcNow);
        var view = await dbContext.SkillTreeServerPlayerViews.FindAsync(serverId, accountId);
        view!.AccountSessionId = owner.AccountSessionId;
        view.OfflineConfirmed = false;
        await dbContext.SkillTreeOperations.Where(x => x.AccountId == accountId && x.TargetServerId != serverId && ActiveStatuses.Contains(x.Status))
            .ExecuteUpdateAsync(s => s.SetProperty(x => x.Status, SkillTreeOperationStatuses.ReconfirmationRequired)
                .SetProperty(x => x.Reason, "参加先が変わったため再確認してください。").SetProperty(x => x.CompletedAtUtc, DateTime.UtcNow)
                .SetProperty(x => x.LeaseTokenHash, (string?)null));
        await dbContext.SaveChangesAsync();
        await tx.CommitAsync();
        return await BuildEditorAsync(account, state);
    }

    public async Task<SkillTreeEditorResponse?> GetEditorAsync(Guid accountId, Guid actorUserId, string? targetServerId = null)
    {
        var account = await dbContext.Accounts.AsNoTracking().SingleOrDefaultAsync(x => x.Uuid == accountId && !x.IsDeleted && x.UserId == actorUserId);
        if (account is null) return null;
        var state = await dbContext.AccountSkillTreeStates.AsNoTracking().SingleOrDefaultAsync(x => x.AccountId == accountId && !x.IsDeleted);
        return await BuildEditorAsync(account, state);
    }

    public Task<SkillTreeOperationResponse?> CreateAsync(Guid accountId, SkillTreeOperationCreateRequest request) =>
        ExecuteRuntimeTransactionAsync(() => CreateCoreAsync(accountId, request));

    private async Task<SkillTreeOperationResponse?> CreateCoreAsync(Guid accountId, SkillTreeOperationCreateRequest request)
    {
        if (!ValidOperation(request)) return null;
        await using var tx = await dbContext.Database.BeginTransactionAsync(IsolationLevel.Serializable);
        var account = await LockAccountAsync(accountId);
        if (account is null || account.UserId != request.ActorUserId) return null;
        var hash = Hash(JsonSerializer.Serialize(request));
        var existing = await dbContext.SkillTreeOperations.SingleOrDefaultAsync(x => x.OperationId == request.OperationId);
        if (existing is not null) return existing.AccountId == accountId && existing.RequestHash == hash ? Map(existing) : null;
        var state = await dbContext.AccountSkillTreeStates.SingleOrDefaultAsync(x => x.AccountId == accountId && !x.IsDeleted);
        var editor = await BuildEditorAsync(account, state);
        if (!editor.CanEdit || editor.Connection.ServerId != request.TargetServerId
            || editor.GenerationId != request.ExpectedDefinitionGenerationId || editor.StateRevision != request.ExpectedPlayerStateVersion
            || await dbContext.SkillTreeOperations.AnyAsync(x => x.AccountId == accountId && ActiveStatuses.Contains(x.Status))) return null;
        var view = await dbContext.SkillTreeServerPlayerViews.FindAsync(request.TargetServerId, accountId);
        if (view is null) return null;
        var now = DateTime.UtcNow;
        var item = new SkillTreeOperationEntity
        {
            OperationId = request.OperationId, AccountId = accountId, ActorUserId = request.ActorUserId, RequestHash = hash,
            TargetServerId = request.TargetServerId, ExpectedDefinitionGenerationId = request.ExpectedDefinitionGenerationId,
            ExpectedPlayerStateVersion = request.ExpectedPlayerStateVersion, ExpectedEvaluationFingerprint = view.EvaluationFingerprint,
            Action = request.Action, NodeId = request.NodeId.Trim(), SourceClassId = BlankToNull(request.SourceClassId),
            Status = editor.Connection.Status == "online" ? SkillTreeOperationStatuses.PendingOnline : SkillTreeOperationStatuses.PendingOffline,
            CreatedAtUtc = now, ExpiresAtUtc = now + OperationTtl,
        };
        dbContext.SkillTreeOperations.Add(item);
        await dbContext.SaveChangesAsync();
        await tx.CommitAsync();
        return Map(item);
    }

    public async Task<SkillTreeOperationResponse?> FindAsync(Guid accountId, Guid operationId, Guid actorUserId)
    {
        if (!await dbContext.Accounts.AnyAsync(x => x.Uuid == accountId && x.UserId == actorUserId && !x.IsDeleted)) return null;
        await ExpireAsync(accountId);
        var item = await dbContext.SkillTreeOperations.AsNoTracking().SingleOrDefaultAsync(x => x.AccountId == accountId && x.OperationId == operationId && x.ActorUserId == actorUserId);
        return item is null ? null : Map(item);
    }

    public Task<SkillTreeOperationResponse?> CancelAsync(Guid accountId, Guid operationId, Guid actorUserId) =>
        ExecuteRuntimeTransactionAsync(() => CancelCoreAsync(accountId, operationId, actorUserId));

    private async Task<SkillTreeOperationResponse?> CancelCoreAsync(Guid accountId, Guid operationId, Guid actorUserId)
    {
        await using var tx = await dbContext.Database.BeginTransactionAsync(IsolationLevel.Serializable);
        var account = await LockAccountAsync(accountId);
        if (account is null || account.UserId != actorUserId) return null;
        var item = await dbContext.SkillTreeOperations.SingleOrDefaultAsync(x => x.AccountId == accountId && x.OperationId == operationId && x.ActorUserId == actorUserId);
        if (item is null) return null;
        if (SkillTreeOperationStatuses.IsActive(item.Status))
        {
            item.Status = SkillTreeOperationStatuses.Canceled;
            item.Reason = "ユーザーが取消しました。";
            item.CompletedAtUtc = DateTime.UtcNow;
            item.LeaseTokenHash = null;
            item.LeaseExpiresAtUtc = null;
            await dbContext.SaveChangesAsync();
        }
        await tx.CommitAsync();
        return Map(item);
    }

    public async Task<IReadOnlyList<SkillTreeOperationResponse>?> GetClaimableAsync(string serverId, Guid serverSessionId, Guid accountId)
    {
        var runtime = await VerifyRuntimeAsync(serverId, serverSessionId);
        var owner = await ActiveSessionAsync(accountId);
        if (runtime is null || owner?.ServerId != serverId || owner.ServerSessionId != serverSessionId) return null;
        await ExpireAsync(accountId);
        var rows = await dbContext.SkillTreeOperations.AsNoTracking().Where(x => x.AccountId == accountId && x.TargetServerId == serverId
            && (x.Status == SkillTreeOperationStatuses.PendingOnline || x.Status == SkillTreeOperationStatuses.PendingOffline)).ToListAsync();
        return rows.Select(Map).ToArray();
    }

    public Task<SkillTreeOperationClaimResponse?> ClaimAsync(string serverId, Guid operationId, SkillTreeOperationClaimRequest request) =>
        ExecuteRuntimeTransactionAsync(() => ClaimCoreAsync(serverId, operationId, request));

    private async Task<SkillTreeOperationClaimResponse?> ClaimCoreAsync(string serverId, Guid operationId, SkillTreeOperationClaimRequest request)
    {
        await using var tx = await dbContext.Database.BeginTransactionAsync(IsolationLevel.Serializable);
        if (await LockAccountAsync(request.AccountId) is null) return null;
        var runtime = await VerifyRuntimeAsync(serverId, request.ServerSessionId);
        var owner = await MatchingSessionAsync(request.AccountId, serverId, request.ServerSessionId, request.AccountSessionId, request.AccountLeaseToken);
        if (runtime is null || owner is null) return null;
        var view = await CurrentViewAsync(runtime, request.AccountId);
        if (view is null || view.AccountSessionId != owner.AccountSessionId) return null;
        var item = await dbContext.SkillTreeOperations.SingleOrDefaultAsync(x => x.OperationId == operationId && x.AccountId == request.AccountId && x.TargetServerId == serverId);
        if (item is null || item.ExpiresAtUtc <= DateTime.UtcNow
            || item.Status is not (SkillTreeOperationStatuses.PendingOnline or SkillTreeOperationStatuses.PendingOffline)) return null;
        if (item.ExpectedDefinitionGenerationId != runtime.DefinitionGenerationId || item.ExpectedPlayerStateVersion != view.PlayerStateVersion
            || item.ExpectedEvaluationFingerprint != view.EvaluationFingerprint || !view.EditEligible)
        {
            item.Status = SkillTreeOperationStatuses.ReconfirmationRequired;
            item.Reason = "定義、状態、残高、条件または現在地が変わりました。";
            item.CompletedAtUtc = DateTime.UtcNow;
            await dbContext.SaveChangesAsync();
            await tx.CommitAsync();
            return null;
        }
        var token = Convert.ToHexString(RandomNumberGenerator.GetBytes(32)).ToLowerInvariant();
        item.Status = SkillTreeOperationStatuses.Claimed;
        item.ClaimedServerSessionId = request.ServerSessionId;
        item.ClaimedAccountSessionId = owner.AccountSessionId;
        item.LeaseTokenHash = Hash(token);
        item.LeaseExpiresAtUtc = DateTime.UtcNow + LeaseTtl;
        await dbContext.SaveChangesAsync();
        await tx.CommitAsync();
        return new() { LeaseToken = token, LeaseExpiresAtUtc = item.LeaseExpiresAtUtc.Value, Operation = Map(item) };
    }

    public async Task<bool> ValidateRuntimeStateSaveAsync(Guid accountId, string serverId, Guid serverSessionId, string definitionGenerationId, Guid accountSessionId, string accountLeaseToken)
    {
        if (!ValidServer(serverId) || !ValidHash(definitionGenerationId)) return false;
        var runtime = await VerifyRuntimeAsync(serverId, serverSessionId);
        var owner = await MatchingSessionAsync(accountId, serverId, serverSessionId, accountSessionId, accountLeaseToken);
        return runtime is not null && owner?.DefinitionGenerationId == definitionGenerationId;
    }

    public async Task<bool> CompleteFromSnapshotAsync(Guid accountId, PlayerStateSkillTreeOperationSection section, DateTime now)
    {
        now = DateTime.UtcNow;
        if (dbContext.Database.CurrentTransaction is null) throw new InvalidOperationException("Operation completion requires the player-state transaction.");
        if (await LockAccountAsync(accountId) is null || section.OperationId == Guid.Empty || string.IsNullOrWhiteSpace(section.LeaseToken)
            || !ValidHash(section.FinalEvaluationFingerprint)
            || section.FinalStatus is not (SkillTreeOperationStatuses.Applied or SkillTreeOperationStatuses.ReconfirmationRequired or SkillTreeOperationStatuses.Failed or SkillTreeOperationStatuses.Canceled)) return false;
        var item = await dbContext.SkillTreeOperations.SingleOrDefaultAsync(x => x.OperationId == section.OperationId && x.AccountId == accountId);
        var runtime = await VerifyRuntimeAsync(section.ServerId, section.ServerSessionId);
        var owner = await ActiveSessionAsync(accountId);
        if (item is null || runtime is null || owner is null || owner.ServerId != section.ServerId || owner.ServerSessionId != section.ServerSessionId
            || item.ClaimedAccountSessionId != owner.AccountSessionId || item.Status != SkillTreeOperationStatuses.Claimed
            || item.ClaimedServerSessionId != section.ServerSessionId || item.LeaseExpiresAtUtc is null || item.LeaseExpiresAtUtc <= now
            || item.ExpiresAtUtc <= now || !FixedEquals(item.LeaseTokenHash, Hash(section.LeaseToken))) return false;
        var state = await dbContext.AccountSkillTreeStates.SingleOrDefaultAsync(x => x.AccountId == accountId && !x.IsDeleted);
        if ((state?.Version ?? 0) != section.FinalPlayerStateVersion) return false;
        if (section.FinalStatus == SkillTreeOperationStatuses.Applied
            && (state?.DefinitionGenerationId != section.DefinitionGenerationId || runtime.DefinitionGenerationId != section.DefinitionGenerationId
                || item.ExpectedDefinitionGenerationId != section.DefinitionGenerationId
                || section.FinalPlayerStateVersion != item.ExpectedPlayerStateVersion + 1)) return false;
        if (section.EvaluatedView.HasValue && IsViewPayload(section.EvaluatedView.Value) && state is not null)
        {
            await UpsertViewAsync(runtime, accountId, state.Version, section.FinalEvaluationFingerprint, true, section.EvaluatedView.Value, now);
            var view = await dbContext.SkillTreeServerPlayerViews.FindAsync(runtime.ServerId, accountId);
            view!.AccountSessionId = owner.AccountSessionId;
            view.OfflineConfirmed = false;
        }
        item.Status = section.FinalStatus;
        item.Reason = BlankToNull(section.FailureReason);
        item.CompletedAtUtc = now;
        item.LeaseTokenHash = null;
        item.LeaseExpiresAtUtc = null;
        return true;
    }

    private async Task<SkillTreeEditorResponse> BuildEditorAsync(AccountEntity account, AccountSkillTreeStateEntity? state)
    {
        await ExpireAsync(account.Uuid);
        var owner = await ActiveSessionAsync(account.Uuid);
        var networkPresence = networkRuntimeService.GetPlayers().FirstOrDefault(x => x.Uuid == account.UserId);
        var selected = owner?.ServerId ?? await dbContext.SkillTreeServerPlayerViews.AsNoTracking()
            .Where(x => x.AccountId == account.Uuid).OrderByDescending(x => x.LastSeenUtc).Select(x => x.ServerId).FirstOrDefaultAsync();
        var runtime = selected is null ? null : await CurrentRuntimeAsync(selected);
        var view = selected is null ? null : await dbContext.SkillTreeServerPlayerViews.AsNoTracking().SingleOrDefaultAsync(x => x.ServerId == selected && x.AccountId == account.Uuid);
        var online = owner is not null && runtime?.ServerSessionId == owner.ServerSessionId && view?.AccountSessionId == owner.AccountSessionId
            && view.ServerSessionId == owner.ServerSessionId && view.LastSeenUtc >= DateTime.UtcNow - RuntimeTtl && view.DefinitionGenerationId == runtime.DefinitionGenerationId;
        var offline = owner is null && networkPresence is null && view?.OfflineConfirmed == true;
        var usable = (online || offline) && view is not null && state?.DefinitionGenerationId == view.DefinitionGenerationId && state.Version == view.PlayerStateVersion;
        if (offline && usable && (account.UpdatedAt > view!.LastSeenUtc
            || await dbContext.InventoryEntries.AnyAsync(entry => entry.UpdatedAt > view.LastSeenUtc
                && dbContext.Inventories.Any(inventory => inventory.InventoryId == entry.InventoryId && inventory.AccountId == account.Uuid))))
            usable = false;
        var status = online ? "online" : offline ? "offline" : owner is not null ? "stale" : "unknown";
        var pending = await dbContext.SkillTreeOperations.AsNoTracking().Where(x => x.AccountId == account.Uuid && ActiveStatuses.Contains(x.Status)).OrderByDescending(x => x.CreatedAtUtc).ToListAsync();
        return new()
        {
            AccountId = account.Uuid, AccountName = account.AccountName, GenerationId = usable ? view?.DefinitionGenerationId : null,
            StateRevision = state?.Version ?? 0, CanEdit = usable && (offline || view!.EditEligible),
            HasFreshState = online && usable, BalanceKind = usable ? online ? "LIVE" : "SAVED" : "UNKNOWN",
            Reason = !usable ? "サーバーの更新待ち、または状態の再確認が必要です。" : online && !view!.EditEligible ? "拠点またはスキルツリーワールドで編集してください。" : null,
            Connection = new()
            {
                Status = status, ServerId = selected, CanEdit = online && view!.EditEligible,
                ChannelName = online ? GetStringProperty(view?.ViewJson, "channelName") : null,
                WorldName = online ? GetNestedString(view?.ViewJson, "location", "worldDisplayName") : null,
                X = online ? GetNestedDouble(view?.ViewJson, "location", "x") : null,
                Y = online ? GetNestedDouble(view?.ViewJson, "location", "y") : null,
                Z = online ? GetNestedDouble(view?.ViewJson, "location", "z") : null,
                ObservedAtUtc = view?.LastSeenUtc,
            },
            Tree = usable ? GetProperty(view?.ViewJson, "tree") : null,
            Points = usable ? GetProperty(view?.ViewJson, "points") : null,
            RelockGoldCost = usable ? GetDecimalProperty(view?.ViewJson, "relockGoldCost") : null,
            PendingOperation = pending.Select(Map).FirstOrDefault(), PendingOperations = pending.Select(Map).ToArray(),
        };
    }

    private async Task<SkillTreeServerPlayerViewEntity?> CurrentViewAsync(SkillTreeServerRuntimeEntity runtime, Guid accountId)
    {
        var view = await dbContext.SkillTreeServerPlayerViews.SingleOrDefaultAsync(x => x.ServerId == runtime.ServerId && x.AccountId == accountId);
        return view is not null && view.ServerSessionId == runtime.ServerSessionId && view.DefinitionGenerationId == runtime.DefinitionGenerationId
            && view.LastSeenUtc >= DateTime.UtcNow - RuntimeTtl ? view : null;
    }

    private async Task UpsertViewAsync(SkillTreeServerRuntimeEntity runtime, Guid accountId, int version, string fingerprint, bool eligible, JsonElement view, DateTime now)
    {
        var item = await dbContext.SkillTreeServerPlayerViews.FindAsync(runtime.ServerId, accountId);
        if (item is null) { item = new() { ServerId = runtime.ServerId, AccountId = accountId }; dbContext.SkillTreeServerPlayerViews.Add(item); }
        item.ServerSessionId = runtime.ServerSessionId;
        item.DefinitionGenerationId = runtime.DefinitionGenerationId;
        item.PlayerStateVersion = version;
        item.EvaluationFingerprint = fingerprint;
        item.EditEligible = eligible;
        item.ViewJson = view.GetRawText();
        item.LastSeenUtc = now;
    }

    private async Task<SkillTreeServerRuntimeEntity?> CurrentRuntimeAsync(string serverId)
    {
        var runtime = await dbContext.SkillTreeServerRuntimes.SingleOrDefaultAsync(x => x.ServerId == serverId);
        return runtime is { Ready: true } && runtime.CompatibilityVersion == SupportedCompatibility && IsLive(runtime) ? runtime : null;
    }
    private async Task<SkillTreeServerRuntimeEntity?> VerifyRuntimeAsync(string serverId, Guid sessionId)
    {
        var runtime = await CurrentRuntimeAsync(serverId);
        return runtime?.ServerSessionId == sessionId ? runtime : null;
    }
    private async Task ExpireAsync(Guid accountId)
    {
        var now = DateTime.UtcNow;
        await dbContext.SkillTreeOperations.Where(x => x.AccountId == accountId && ActiveStatuses.Contains(x.Status) && x.ExpiresAtUtc <= now)
            .ExecuteUpdateAsync(s => s.SetProperty(x => x.Status, SkillTreeOperationStatuses.Expired).SetProperty(x => x.CompletedAtUtc, now));
        await dbContext.SkillTreeOperations.Where(x => x.AccountId == accountId && x.Status == SkillTreeOperationStatuses.Claimed && x.LeaseExpiresAtUtc <= now)
            .ExecuteUpdateAsync(s => s.SetProperty(x => x.Status, SkillTreeOperationStatuses.ReconfirmationRequired).SetProperty(x => x.CompletedAtUtc, now).SetProperty(x => x.LeaseTokenHash, (string?)null));
    }
    private static bool IsLive(SkillTreeServerRuntimeEntity value) => value.LastSeenUtc >= DateTime.UtcNow - RuntimeTtl;
    private static bool ValidServer(string? value) => !string.IsNullOrWhiteSpace(value) && value.Length <= 64 && value == value.Trim();
    private static bool ValidHash(string? value) => value?.Length == 64 && value.All(c => c is >= '0' and <= '9' or >= 'a' and <= 'f');
    private static bool MatchesGeneration(string id, string json)
    {
        if (string.IsNullOrWhiteSpace(json) || json.Length > 4 * 1024 * 1024 || id != Hash(json)) return false;
        try { using var doc = JsonDocument.Parse(json); return doc.RootElement.ValueKind == JsonValueKind.Object; } catch (JsonException) { return false; }
    }
    private static bool ValidOperation(SkillTreeOperationCreateRequest x) => x.OperationId != Guid.Empty && x.ActorUserId != Guid.Empty
        && ValidServer(x.TargetServerId) && ValidHash(x.ExpectedDefinitionGenerationId) && x.ExpectedPlayerStateVersion >= 0
        && x.Action is "UNLOCK" or "RELOCK" && !string.IsNullOrWhiteSpace(x.NodeId) && x.NodeId.Trim().Length <= 200 && x.SourceClassId?.Length is not > 100;
    private static bool IsValidView(SkillTreePlayerViewRegistrationRequest x) => x.ServerSessionId != Guid.Empty && ValidHash(x.DefinitionGenerationId)
        && ValidHash(x.EvaluationFingerprint) && x.PlayerStateVersion >= 0 && IsViewPayload(x.View);
    private static bool IsViewPayload(JsonElement x) => x.ValueKind == JsonValueKind.Object && x.GetRawText().Length <= MaxViewJsonLength
        && x.TryGetProperty("tree", out var tree) && tree.ValueKind == JsonValueKind.Object && x.TryGetProperty("points", out var points) && points.ValueKind == JsonValueKind.Object;
    private static string Hash(string value) => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(value))).ToLowerInvariant();
    private static bool FixedEquals(string? left, string right) => left is not null && CryptographicOperations.FixedTimeEquals(Encoding.UTF8.GetBytes(left), Encoding.UTF8.GetBytes(right));
    private static string? BlankToNull(string? value) => string.IsNullOrWhiteSpace(value) ? null : value.Trim();
    private static SkillTreeServerRuntimeResponse Runtime(SkillTreeServerRuntimeEntity x) => new() { ServerId = x.ServerId, ServerSessionId = x.ServerSessionId, DefinitionGenerationId = x.DefinitionGenerationId, Ready = x.Ready, LastSeenUtc = x.LastSeenUtc };
    private static SkillTreeOperationResponse Map(SkillTreeOperationEntity x) => new() { OperationId = x.OperationId, AccountId = x.AccountId, TargetServerId = x.TargetServerId, ExpectedDefinitionGenerationId = x.ExpectedDefinitionGenerationId, ExpectedPlayerStateVersion = x.ExpectedPlayerStateVersion, ExpectedEvaluationFingerprint = x.ExpectedEvaluationFingerprint, Action = x.Action, NodeId = x.NodeId, SourceClassId = x.SourceClassId, Status = x.Status, Reason = x.Reason, CreatedAtUtc = x.CreatedAtUtc, ExpiresAtUtc = x.ExpiresAtUtc, CompletedAtUtc = x.CompletedAtUtc };
    private static JsonElement? GetProperty(string? json, string name) { if (json is null) return null; try { using var doc = JsonDocument.Parse(json); return doc.RootElement.TryGetProperty(name, out var value) ? value.Clone() : null; } catch (JsonException) { return null; } }
    private static decimal? GetDecimalProperty(string? json, string name) { var value = GetProperty(json, name); return value is { ValueKind: JsonValueKind.Number } && value.Value.TryGetDecimal(out var result) ? result : null; }
    private static string? GetStringProperty(string? json, string name) { var value = GetProperty(json, name); return value is { ValueKind: JsonValueKind.String } ? value.Value.GetString() : null; }
    private static string? GetNestedString(string? json, string parent, string name) { var value = GetProperty(json, parent); return value is { ValueKind: JsonValueKind.Object } && value.Value.TryGetProperty(name, out var nested) && nested.ValueKind == JsonValueKind.String ? nested.GetString() : null; }
    private static double? GetNestedDouble(string? json, string parent, string name) { var value = GetProperty(json, parent); return value is { ValueKind: JsonValueKind.Object } && value.Value.TryGetProperty(name, out var nested) && nested.TryGetDouble(out var number) ? number : null; }
}
