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

/// <summary>ManagementDBに開催と報酬を保存し、ゲームDBのメールへ冪等に配送します。</summary>
public sealed class DefenseRepository(
    ManagementDbContext management, AstralRecordDbContext game, INetworkManagementRepository network,
    IMobRepository mobs, IItemRepository items, TimeProvider clock) : IDefenseRepository
{
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web);
    private static readonly TimeZoneInfo Japan = TimeZoneInfo.FindSystemTimeZoneById("Asia/Tokyo");
    private static readonly string[] ActiveStatuses = ["PENDING", "RUNNING", "STOP_REQUESTED"];

    public async Task<DefenseChannelResponse> GetChannelAsync(string serverId)
    {
        await EnsureGameChannelAsync(serverId);
        var channel = await management.DefenseChannels.AsNoTracking().SingleOrDefaultAsync(x => x.ServerId == serverId);
        var runs = await management.DefenseRuns.AsNoTracking().Where(x => x.ServerId == serverId)
            .OrderByDescending(x => x.CreatedAtUtc).Take(30).ToListAsync();
        var configuration = Configuration(channel?.ConfigurationJson);
        var registrationCurrent = channel?.RegistrationReportedAtUtc is DateTime reported
            && reported >= Now().AddSeconds(-30) && channel.LastRuntimeInstanceId is not null;
        return new DefenseChannelResponse(serverId, channel?.Revision ?? 0, channel?.DailyEnabled ?? false,
            channel?.DailyStartTimeJst, configuration, Missing(configuration),
            NextSchedule(channel, runs), runs.FirstOrDefault(x => ActiveStatuses.Contains(x.Status)) is { } active ? Map(active) : null,
            runs.FirstOrDefault() is { } latest ? Map(latest) : null,
            registrationCurrent ? channel!.RegistrationStatus ?? "UNKNOWN" : "UNKNOWN",
            registrationCurrent ? channel!.RegisteredCount : null);
    }

    public async Task<DefenseChannelResponse> UpdateChannelAsync(string serverId, DefenseChannelUpdateRequest request, Guid actor)
    {
        await EnsureGameChannelAsync(serverId);
        if (actor == Guid.Empty || request.ExpectedRevision < 0 || request.Configuration is null)
            throw new ArgumentException("設定と更新版番号を確認してください。");
        if (request.DailyStartTimeJst is not null && !TimeOnly.TryParseExact(request.DailyStartTimeJst, "HH:mm", out _))
            throw new ArgumentException("日次時刻は HH:mm 形式で指定してください。");
        if (request.DailyEnabled && request.DailyStartTimeJst is null)
            throw new ArgumentException("日次開催時刻を指定してください。");
        if (JsonSerializer.Serialize(request.Configuration, JsonOptions).Length > 32768)
            throw new ArgumentException("設定が大きすぎます。");
        await WriteAsync(async () =>
        {
            var row = await LockedChannelAsync(serverId);
            if ((row?.Revision ?? 0) != request.ExpectedRevision) throw new DefenseConflictException("設定が更新されています。再読み込みしてください。");
            if (row is null)
            {
                row = new DefenseChannelEntity { ServerId = serverId };
                management.DefenseChannels.Add(row);
            }
            row.Revision++;
            row.DailyEnabled = request.DailyEnabled;
            row.DailyStartTimeJst = request.DailyStartTimeJst;
            row.ConfigurationJson = JsonSerializer.Serialize(request.Configuration, JsonOptions);
            row.UpdatedAtUtc = Now();
            row.UpdatedBy = actor;
            await management.SaveChangesAsync();
            return true;
        });
        return await GetChannelAsync(serverId);
    }

    public async Task<DefenseRunResponse> StartAsync(string serverId, DefenseStartRequest request)
    {
        await EnsureGameChannelAsync(serverId);
        if (request.OperationId == Guid.Empty || request.ExpectedRevision < 0)
            throw new ArgumentException("操作IDと設定版番号を確認してください。");
        return await WriteAsync(async () =>
        {
            var existing = await management.DefenseRuns.AsNoTracking().SingleOrDefaultAsync(x => x.OperationId == request.OperationId);
            if (existing is not null)
                return existing.ServerId == serverId && existing.Source == "MANUAL" ? Map(existing)
                    : throw new DefenseConflictException("操作IDが別の開催に使われています。");
            var channel = await LockedChannelAsync(serverId);
            if (channel is null || channel.Revision != request.ExpectedRevision)
                throw new DefenseConflictException("設定が更新されています。再読み込みしてください。");
            var configuration = Configuration(channel.ConfigurationJson);
            var missing = Missing(configuration);
            if (missing.Count > 0) throw new DefenseConflictException($"開始設定が不足しています: {string.Join(", ", missing)}");
            if (channel.RegistrationStatus != "OPEN" || channel.RegisteredCount is null or < 1
                || channel.RegistrationReportedAtUtc is not DateTime registration
                || registration < Now().AddSeconds(-30))
                throw new DefenseConflictException("受付中の参加者がいません。実行サーバーの受付状態を確認してください。");
            if (await ActiveRunAsync(serverId) is not null) throw new DefenseConflictException("このチャンネルでは開催が進行中です。");
            var run = NewRun(serverId, "MANUAL", request.OperationId, null, null, channel.ConfigurationJson);
            management.DefenseRuns.Add(run);
            await management.SaveChangesAsync();
            return Map(run);
        });
    }

    public async Task<DefenseRunResponse> StopAsync(string serverId, DefenseStopRequest request)
    {
        await EnsureGameChannelAsync(serverId);
        if (request.OperationId == Guid.Empty || request.RunId == Guid.Empty || string.IsNullOrWhiteSpace(request.Reason)
            || request.Reason.Length > 500) throw new ArgumentException("開催IDと停止理由を確認してください。");
        return await WriteAsync(async () =>
        {
            var existing = await management.DefenseReports.AsNoTracking().SingleOrDefaultAsync(x => x.OperationId == request.OperationId);
            var run = await LockedRunAsync(request.RunId);
            if (run is null || run.ServerId != serverId) throw new KeyNotFoundException();
            if (existing is not null)
                return existing.RunId == run.RunId && existing.Kind == "STOP" && existing.RequestHash == Hash(request) ? Map(run)
                    : throw new DefenseConflictException("操作IDが別の報告に使われています。");
            if (run.Status == "PENDING")
            {
                run.Status = "ABORTED";
                run.EndedAtUtc = Now();
                run.Reason = request.Reason.Trim();
            }
            else if (run.Status == "RUNNING")
            {
                run.Status = "STOP_REQUESTED";
                run.Reason = request.Reason.Trim();
            }
            management.DefenseReports.Add(new DefenseReportEntity
            {
                OperationId = request.OperationId, RunId = run.RunId, Kind = "STOP",
                RequestHash = Hash(request), CreatedAtUtc = Now(),
            });
            await management.SaveChangesAsync();
            return Map(run);
        });
    }

    public async Task<IReadOnlyList<DefenseRunResponse>> RunsAsync(string serverId, int limit)
    {
        await EnsureGameChannelAsync(serverId);
        if (limit is < 1 or > 100) throw new ArgumentException("limitは1～100です。");
        return (await management.DefenseRuns.AsNoTracking().Where(x => x.ServerId == serverId)
            .OrderByDescending(x => x.CreatedAtUtc).Take(limit).ToListAsync()).Select(Map).ToArray();
    }

    public async Task<DefenseRuntimeResponse> RuntimeAsync(string serverId, Guid instanceId)
    {
        await EnsureGameChannelAsync(serverId);
        if (instanceId == Guid.Empty) throw new ArgumentException("instanceIdが必要です。");
        await WriteAsync(async () =>
        {
            var row = await LockedChannelAsync(serverId);
            if (row is null) return false;
            if (row.LastRuntimeInstanceId != instanceId)
            {
                row.RegistrationStatus = null;
                row.RegisteredCount = null;
                row.RegistrationReportedAtUtc = null;
            }
            row.LastRuntimePollAtUtc = Now();
            row.LastRuntimeInstanceId = instanceId;
            await management.SaveChangesAsync();
            return true;
        });
        await ExpireStaleAsync(serverId);
        await MaterializeDailyAsync(serverId);
        var channel = await management.DefenseChannels.AsNoTracking().SingleOrDefaultAsync(x => x.ServerId == serverId);
        var configuration = Configuration(channel?.ConfigurationJson);
        var active = await management.DefenseRuns.AsNoTracking().Where(x => x.ServerId == serverId && ActiveStatuses.Contains(x.Status))
            .OrderByDescending(x => x.CreatedAtUtc).FirstOrDefaultAsync();
        // 他の起動世代が所有する開催はPluginへ渡さず、再起動後の旧戦闘を再開しません。
        if (active?.InstanceId is Guid owner && owner != instanceId) active = null;
        return new DefenseRuntimeResponse(serverId, configuration,
            active?.Status == "PENDING" ? Map(active) : null,
            active?.Status is "RUNNING" or "STOP_REQUESTED" ? Map(active) : null,
            Missing(configuration), Now());
    }

    public async Task<DefenseChannelResponse> RegistrationAsync(string serverId, DefenseRegistrationReport request)
    {
        await EnsureGameChannelAsync(serverId);
        if (request.InstanceId == Guid.Empty || request.OperationId == Guid.Empty
            || request.Status is not ("CLOSED" or "OPEN" or "PREPARING")
            || request.RegisteredCount is < 0 or > 30)
            throw new ArgumentException("受付状態を確認してください。");
        await WriteAsync(async () =>
        {
            var row = await LockedChannelAsync(serverId);
            if (row is null || row.LastRuntimeInstanceId != request.InstanceId)
                throw new DefenseConflictException("サーバー起動世代が一致しません。");
            row.RegistrationStatus = request.Status;
            row.RegisteredCount = request.RegisteredCount;
            row.RegistrationReportedAtUtc = Now();
            await management.SaveChangesAsync();
            return true;
        });
        return await GetChannelAsync(serverId);
    }

    public async Task<DefenseRunResponse> ReportAsync(string serverId, Guid runId, DefenseRunReportRequest request)
    {
        await EnsureGameChannelAsync(serverId);
        if (runId == Guid.Empty || request.InstanceId == Guid.Empty || request.OperationId == Guid.Empty
            || request.Event is not ("STARTED" or "HEARTBEAT" or "ENDED" or "ABORTED")
            || request.DefenseLevel is < 1 || request.Reason?.Length > 500 || request.Participants is null
            || request.Participants.Any(x => x is null))
            throw new ArgumentException("開催報告を確認してください。");
        var hash = Hash(request);
        return await WriteAsync(async () =>
        {
            var run = await LockedRunAsync(runId);
            if (run is null || run.ServerId != serverId) throw new KeyNotFoundException();
            var previous = await management.DefenseReports.AsNoTracking()
                .SingleOrDefaultAsync(x => x.OperationId == request.OperationId);
            if (previous is not null)
                return previous.RunId == runId && previous.Kind == request.Event && previous.RequestHash == hash
                    ? Map(run) : throw new DefenseConflictException("報告操作IDの内容が一致しません。");
            if (run.InstanceId is Guid owner && owner != request.InstanceId)
                throw new DefenseConflictException("別のサーバー起動世代が所有する開催です。");
            if (request.Event == "STARTED")
            {
                if (run.Status != "PENDING") throw new DefenseConflictException("開始待ちの開催ではありません。");
                if (request.Participants.Count is < 1 or > 30 || request.Participants.Any(x => x.AccountId == Guid.Empty || x.UserUuid == Guid.Empty)
                    || request.Participants.Select(x => x.AccountId).Distinct().Count() != request.Participants.Count
                    || request.Participants.Select(x => x.UserUuid).Distinct().Count() != request.Participants.Count)
                    throw new ArgumentException("参加者は異なる本人の1～30人で指定してください。");
                if (run.ScheduledAtUtc is DateTime scheduled && Now() < scheduled)
                    throw new DefenseConflictException("定刻前には戦闘を開始できません。");
                var ids = request.Participants.Select(x => x.AccountId).ToArray();
                var accounts = await game.Accounts.AsNoTracking().Where(x => ids.Contains(x.Uuid) && !x.IsDeleted)
                    .Select(x => new { x.Uuid, x.UserId }).ToListAsync();
                if (request.Participants.Any(x => !accounts.Any(a => a.Uuid == x.AccountId && a.UserId == x.UserUuid)))
                    throw new ArgumentException("参加アカウントと本人UUIDが一致しません。");
                run.Status = "RUNNING";
                run.InstanceId = request.InstanceId;
                run.StartedAtUtc = Now();
                run.LastHeartbeatAtUtc = Now();
                run.DefenseLevel = 1;
                run.ParticipantCount = request.Participants.Count;
                foreach (var participant in request.Participants)
                    management.DefenseParticipants.Add(new DefenseParticipantEntity
                    {
                        RunId = runId, AccountId = participant.AccountId, UserUuid = participant.UserUuid,
                    });
            }
            else if (request.Event == "HEARTBEAT")
            {
                if (run.Status is not ("RUNNING" or "STOP_REQUESTED"))
                    throw new DefenseConflictException("進行中の開催ではありません。");
                if (run.InstanceId != request.InstanceId) throw new DefenseConflictException("開催の起動世代が一致しません。");
                if (request.DefenseLevel is null || request.DefenseLevel < run.DefenseLevel)
                    throw new ArgumentException("防衛レベルを下げることはできません。");
                run.DefenseLevel = request.DefenseLevel;
                run.LastHeartbeatAtUtc = Now();
                var participants = await management.DefenseParticipants.Where(x => x.RunId == runId).ToDictionaryAsync(x => x.AccountId);
                foreach (var value in request.Participants)
                {
                    if (!participants.TryGetValue(value.AccountId, out var participant) || participant.UserUuid != value.UserUuid
                        || value.ActiveSeconds < 0) throw new ArgumentException("確定参加者以外は報告できません。");
                    participant.ActiveSeconds = Math.Max(participant.ActiveSeconds, value.ActiveSeconds);
                    participant.IsAfk = value.IsAfk;
                    participant.Eliminated |= value.Eliminated;
                }
            }
            else
            {
                if (request.Event == "ABORTED" && run.Status == "ABORTED")
                {
                    management.DefenseReports.Add(new DefenseReportEntity
                    {
                        OperationId = request.OperationId, RunId = runId, Kind = request.Event,
                        RequestHash = hash, CreatedAtUtc = Now(),
                    });
                    await management.SaveChangesAsync();
                    return Map(run);
                }
                if (run.Status is not ("RUNNING" or "STOP_REQUESTED" or "PENDING"))
                    throw new DefenseConflictException("既に終了しています。");
                run.Status = request.Event == "ENDED" ? "ENDED" : "ABORTED";
                run.EndedAtUtc = Now();
                run.Reason = request.Reason;
                if (request.DefenseLevel > run.DefenseLevel) run.DefenseLevel = request.DefenseLevel;
            }
            management.DefenseReports.Add(new DefenseReportEntity
            {
                OperationId = request.OperationId, RunId = runId, Kind = request.Event,
                RequestHash = hash, CreatedAtUtc = Now(),
            });
            await management.SaveChangesAsync();
            return Map(run);
        });
    }

    public async Task<IReadOnlyList<DefenseRewardReceipt>> RewardsAsync(string serverId, Guid runId,
        DefenseRewardReportRequest request)
    {
        await EnsureGameChannelAsync(serverId);
        if (runId == Guid.Empty || request.InstanceId == Guid.Empty || request.OperationId == Guid.Empty
            || request.Rewards is null or { Count: < 1 or > 30 }
            || request.Rewards.Any(x => x is null)
            || request.Rewards.Select(x => (x.AccountId, x.RequiredDefenseLevel)).Distinct().Count() != request.Rewards.Count)
            throw new ArgumentException("報酬報告を確認してください。");
        var hash = Hash(request);
        var receipts = await WriteAsync(async () =>
        {
            var run = await LockedRunAsync(runId);
            if (run is null || run.ServerId != serverId) throw new KeyNotFoundException();
            if (run.InstanceId != request.InstanceId || run.Status is not ("RUNNING" or "STOP_REQUESTED" or "ENDED" or "ABORTED"))
                throw new DefenseConflictException("開催の起動世代または状態が一致しません。");
            var previous = await management.DefenseReports.AsNoTracking().SingleOrDefaultAsync(x => x.OperationId == request.OperationId);
            if (previous is not null && (previous.RunId != runId || previous.Kind != "REWARD" || previous.RequestHash != hash))
                throw new DefenseConflictException("報酬操作IDの内容が一致しません。");
            var configuration = Configuration(run.ConfigurationJson);
            var every = configuration.RewardEveryLevels ?? 0;
            var participants = await management.DefenseParticipants.AsNoTracking().Where(x => x.RunId == runId)
                .ToDictionaryAsync(x => x.AccountId);
            var result = new List<DefenseRewardReceipt>();
            foreach (var entry in request.Rewards)
            {
                if (!participants.TryGetValue(entry.AccountId, out var participant) || participant.UserUuid != entry.UserUuid
                    || entry.RequiredDefenseLevel < 1 || entry.RequiredDefenseLevel > run.DefenseLevel || every < 1
                    || (entry.RequiredDefenseLevel - 1) % every != 0 || entry.IsAfk || participant.IsAfk
                    || entry.ActiveSeconds < (configuration.MinimumActiveSeconds ?? int.MaxValue)
                    || entry.ActiveSeconds > participant.ActiveSeconds)
                    throw new ArgumentException("段階、本人、AFK、参加時間を確認してください。");
                var tier = configuration.RewardTiers.Where(x => x.RequiredDefenseLevel <= entry.RequiredDefenseLevel)
                    .OrderByDescending(x => x.RequiredDefenseLevel).FirstOrDefault()
                    ?? throw new DefenseConflictException("この段階の報酬が設定されていません。");
                var existing = await management.DefenseRewards.SingleOrDefaultAsync(x => x.RunId == runId
                    && x.AccountId == entry.AccountId && x.DefenseLevel == entry.RequiredDefenseLevel);
                if (existing is null)
                {
                    existing = new DefenseRewardEntity
                    {
                        RunId = runId, AccountId = entry.AccountId, UserUuid = entry.UserUuid,
                        DefenseLevel = entry.RequiredDefenseLevel, OrbItemId = tier.OrbItemId!,
                        OrbQuantity = tier.OrbQuantity!.Value,
                        MailId = $"defense-{runId:N}-{entry.AccountId:N}-{entry.RequiredDefenseLevel}",
                        CreatedAtUtc = Now(),
                    };
                    management.DefenseRewards.Add(existing);
                }
                result.Add(new DefenseRewardReceipt(existing.AccountId, existing.DefenseLevel,
                    existing.OrbItemId, existing.OrbQuantity, existing.MailId));
            }
            if (previous is null) management.DefenseReports.Add(new DefenseReportEntity
            {
                OperationId = request.OperationId, RunId = runId, Kind = "REWARD",
                RequestHash = hash, CreatedAtUtc = Now(),
            });
            await management.SaveChangesAsync();
            return result.ToArray();
        });
        await DeliverPendingAsync(CancellationToken.None);
        return receipts;
    }

    public async Task<int> DeliverPendingAsync(CancellationToken cancellationToken)
    {
        var pending = await management.DefenseRewards.AsNoTracking().Where(x => x.DeliveredAtUtc == null)
            .OrderBy(x => x.CreatedAtUtc).Take(50).ToListAsync(cancellationToken);
        var delivered = 0;
        foreach (var reward in pending)
        {
            cancellationToken.ThrowIfCancellationRequested();
            var account = await game.Accounts.AsNoTracking().SingleOrDefaultAsync(x => x.Uuid == reward.AccountId && !x.IsDeleted,
                cancellationToken);
            if (account is null || account.UserId != reward.UserUuid) continue;
            await game.Database.CreateExecutionStrategy().ExecuteAsync(async () =>
            {
                game.ChangeTracker.Clear();
                await using var tx = await game.Database.BeginTransactionAsync(IsolationLevel.Serializable, cancellationToken);
                var exists = await game.PlayerMailDeliveries.AnyAsync(x => x.AccountId == reward.AccountId && x.MailId == reward.MailId,
                    cancellationToken);
                if (!exists)
                {
                    var mail = new MailResponse
                    {
                        SchemaVersion = 1, Id = reward.MailId, Icon = "ENDER_EYE", Title = "防衛戦の段階報酬",
                        Body = $"防衛レベル {reward.DefenseLevel} 到達報酬です。",
                        PublishFrom = reward.CreatedAtUtc, PublishTo = null, ReceiveOnRead = true,
                        Rewards = [new MailRewardResponse
                        {
                            ItemId = reward.OrbItemId, Category = "orb", Amount = reward.OrbQuantity,
                        }],
                    };
                    game.PlayerMailDeliveries.Add(new PlayerMailDeliveryEntity
                    {
                        PlayerMailDeliveryId = Guid.NewGuid(), AccountId = reward.AccountId,
                        MailId = reward.MailId, PayloadJson = JsonSerializer.Serialize(mail, MasterDataPayloadJson.Options),
                        Version = 1, CreatedAt = Now(), UpdatedAt = Now(), CreatedBy = reward.UserUuid,
                        UpdatedBy = reward.UserUuid,
                    });
                    await game.SaveChangesAsync(cancellationToken);
                }
                await tx.CommitAsync(cancellationToken);
            });
            var row = await management.DefenseRewards.SingleAsync(x => x.RunId == reward.RunId
                && x.AccountId == reward.AccountId && x.DefenseLevel == reward.DefenseLevel, cancellationToken);
            if (row.DeliveredAtUtc is null)
            {
                row.DeliveredAtUtc = Now();
                await management.SaveChangesAsync(cancellationToken);
            }
            delivered++;
        }
        return delivered;
    }

    public async Task ReconcileScheduleAsync(CancellationToken cancellationToken)
    {
        var ids = await management.DefenseChannels.AsNoTracking().Select(x => x.ServerId).ToListAsync(cancellationToken);
        foreach (var id in ids)
        {
            cancellationToken.ThrowIfCancellationRequested();
            await ExpireStaleAsync(id);
            await MaterializeDailyAsync(id);
        }
    }

    private async Task MaterializeDailyAsync(string serverId)
    {
        var channel = await management.DefenseChannels.AsNoTracking().SingleOrDefaultAsync(x => x.ServerId == serverId);
        if (channel is null || !channel.DailyEnabled || !TimeOnly.TryParseExact(channel.DailyStartTimeJst, "HH:mm", out var time)) return;
        var now = clock.GetUtcNow();
        var jst = TimeZoneInfo.ConvertTime(now, Japan);
        var localDate = jst.Date;
        var scheduled = TimeZoneInfo.ConvertTimeToUtc(localDate.Add(time.ToTimeSpan()), Japan);
        var configuration = Configuration(channel.ConfigurationJson);
        var lead = configuration.RegistrationLeadSeconds ?? 0;
        if (now.UtcDateTime < scheduled.AddSeconds(-lead)) return;
        await WriteAsync(async () =>
        {
            var locked = await LockedChannelAsync(serverId);
            if (locked is null || !locked.DailyEnabled) return false;
            if (await management.DefenseRuns.AnyAsync(x => x.ServerId == serverId && x.Source == "DAILY"
                && x.ScheduledDateJst == localDate)) return false;
            var active = await ActiveRunAsync(serverId);
            var missing = Missing(configuration);
            var missed = Now() > scheduled.AddSeconds(30);
            var online = locked.LastRuntimePollAtUtc is DateTime seen && seen >= Now().AddSeconds(-30);
            if (!missed && !online) return false;
            var reason = active is not null ? "前回の開催が継続中です。"
                : missing.Count > 0 ? $"設定不足: {string.Join(", ", missing)}"
                : missed ? "実行サーバーが定刻に接続していません。" : null;
            var run = NewRun(serverId, "DAILY", Guid.NewGuid(), scheduled, localDate, locked.ConfigurationJson);
            if (reason is not null)
            {
                run.Status = "SKIPPED";
                run.EndedAtUtc = Now();
                run.Reason = reason;
            }
            management.DefenseRuns.Add(run);
            await management.SaveChangesAsync();
            return true;
        });
    }

    private async Task ExpireStaleAsync(string serverId)
    {
        await WriteAsync(async () =>
        {
            var run = await ActiveRunAsync(serverId);
            if (run is null) return false;
            var configuration = Configuration(run.ConfigurationJson);
            var staleSeconds = Math.Max(60, configuration.ReconnectGraceSeconds ?? 0);
            if (run.Status == "PENDING")
            {
                var cutoff = run.Source == "DAILY" && run.ScheduledAtUtc is DateTime due
                    ? due.AddSeconds(30) : run.CreatedAtUtc.AddSeconds(Math.Max(60, configuration.RegistrationLeadSeconds ?? 0));
                if (Now() <= cutoff) return false;
                run.Status = run.Source == "DAILY" ? "SKIPPED" : "ABORTED";
                run.Reason = "開始準備が完了しませんでした。";
            }
            else
            {
                if (run.LastHeartbeatAtUtc is not DateTime seen || seen >= Now().AddSeconds(-staleSeconds)) return false;
                run.Status = "ABORTED";
                run.Reason = "実行サーバーの報告が途絶えました。";
            }
            run.EndedAtUtc = Now();
            await management.SaveChangesAsync();
            return true;
        });
    }

    private async Task EnsureGameChannelAsync(string serverId)
    {
        if (string.IsNullOrWhiteSpace(serverId) || serverId.Length > 64)
            throw new ArgumentException("serverIdが不正です。");
        var settings = await network.GetSettingsAsync();
        if (settings?.Channels.Any(x => x.IsGame && string.Equals(x.ServerId, serverId, StringComparison.Ordinal)) != true)
            throw new KeyNotFoundException("ゲームチャンネルが見つかりません。");
    }

    private IReadOnlyList<string> Missing(DefenseConfiguration configuration)
    {
        var missing = DefenseConfigurationValidator.Missing(configuration).ToList();
        if (!mobs.GetAllSummaries("ENEMY").Any(x => x.DefenseEligible)) missing.Add("eligibleMobs");
        if (configuration.RewardTiers is { Count: > 0 })
        {
            var orbs = items.GetAllSummaries().Where(x => string.Equals(x.Category, "orb", StringComparison.OrdinalIgnoreCase))
                .Select(x => x.Id).ToHashSet(StringComparer.OrdinalIgnoreCase);
            if (configuration.RewardTiers.Any(x => x.OrbItemId is null || !orbs.Contains(x.OrbItemId)))
                missing.Add("configuration.rewardTiers.orbItemId");
        }
        return missing;
    }

    private static DefenseConfiguration Configuration(string? json) => json is null
        ? new DefenseConfiguration() : JsonSerializer.Deserialize<DefenseConfiguration>(json, JsonOptions) ?? new();

    private static DefenseRunResponse Map(DefenseRunEntity run) => new(run.RunId, run.ServerId, run.Source, run.Status,
        run.ScheduledAtUtc, run.CreatedAtUtc, run.StartedAtUtc, run.EndedAtUtc, run.InstanceId,
        Configuration(run.ConfigurationJson), run.DefenseLevel, run.ParticipantCount, run.Reason);

    private DefenseRunEntity NewRun(string serverId, string source, Guid operationId, DateTime? scheduled,
        DateTime? localDate, string configurationJson) => new()
    {
        RunId = Guid.NewGuid(), ServerId = serverId, Source = source, Status = "PENDING",
        OperationId = operationId, ScheduledAtUtc = scheduled, ScheduledDateJst = localDate,
        CreatedAtUtc = Now(), ConfigurationJson = configurationJson,
    };

    private DateTime? NextSchedule(DefenseChannelEntity? channel, IReadOnlyList<DefenseRunEntity> runs)
    {
        if (channel?.DailyEnabled != true || !TimeOnly.TryParseExact(channel.DailyStartTimeJst, "HH:mm", out var time)) return null;
        var local = TimeZoneInfo.ConvertTime(clock.GetUtcNow(), Japan);
        var date = local.Date;
        if (runs.Any(x => x.Source == "DAILY" && x.ScheduledDateJst == date) || local.TimeOfDay > time.ToTimeSpan())
            date = date.AddDays(1);
        return TimeZoneInfo.ConvertTimeToUtc(date.Add(time.ToTimeSpan()), Japan);
    }

    private Task<DefenseChannelEntity?> LockedChannelAsync(string serverId) => management.Database.IsSqlServer()
        ? management.DefenseChannels.FromSqlInterpolated($"SELECT * FROM dbo.defense_channel WITH (UPDLOCK,HOLDLOCK) WHERE server_id = {serverId}").SingleOrDefaultAsync()
        : management.DefenseChannels.SingleOrDefaultAsync(x => x.ServerId == serverId);

    private Task<DefenseRunEntity?> LockedRunAsync(Guid runId) => management.Database.IsSqlServer()
        ? management.DefenseRuns.FromSqlInterpolated($"SELECT * FROM dbo.defense_run WITH (UPDLOCK,HOLDLOCK) WHERE run_id = {runId}").SingleOrDefaultAsync()
        : management.DefenseRuns.SingleOrDefaultAsync(x => x.RunId == runId);

    private Task<DefenseRunEntity?> ActiveRunAsync(string serverId) => management.Database.IsSqlServer()
        ? management.DefenseRuns.FromSqlInterpolated($"SELECT * FROM dbo.defense_run WITH (UPDLOCK,HOLDLOCK) WHERE server_id = {serverId} AND status IN ('PENDING','RUNNING','STOP_REQUESTED')").SingleOrDefaultAsync()
        : management.DefenseRuns.SingleOrDefaultAsync(x => x.ServerId == serverId && ActiveStatuses.Contains(x.Status));

    private async Task<T> WriteAsync<T>(Func<Task<T>> action)
    {
        return await management.Database.CreateExecutionStrategy().ExecuteAsync(async () =>
        {
            management.ChangeTracker.Clear();
            await using var transaction = await management.Database.BeginTransactionAsync(IsolationLevel.Serializable);
            try
            {
                var result = await action();
                await transaction.CommitAsync();
                return result;
            }
            catch (DbUpdateConcurrencyException)
            {
                throw new DefenseConflictException("同時更新が発生しました。再読み込みしてください。");
            }
            catch (DbUpdateException)
            {
                throw new DefenseConflictException("同時操作が競合しました。状態を確認してください。");
            }
        });
    }

    private DateTime Now() => clock.GetUtcNow().UtcDateTime;
    private static string Hash<T>(T value) => Convert.ToHexString(SHA256.HashData(
        Encoding.UTF8.GetBytes(JsonSerializer.Serialize(value, JsonOptions))));
}
