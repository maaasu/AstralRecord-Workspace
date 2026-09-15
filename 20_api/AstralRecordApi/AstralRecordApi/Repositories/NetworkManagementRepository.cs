using System.Data;
using System.Text.Json;
using System.Text.RegularExpressions;
using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using AstralRecordApi.Services;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordApi.Repositories;

public sealed class NetworkManagementRepository(
    ManagementDbContext managementDb, AstralRecordDbContext gameDb, TimeProvider clock) : INetworkManagementRepository
{
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web);

    public async Task<ManagedNetworkSettings?> GetSettingsAsync(bool includePlayers = false)
    {
        var row = await managementDb.NetworkSettings.AsNoTracking().SingleOrDefaultAsync(x => x.Id == 1);
        if (row is null) return null;
        var settings = Deserialize(row);
        return Copy(settings, row.Revision, includePlayers ? await ResolvePlayersAsync(AllUserIds(settings)) : []);
    }

    public async Task<(ManagedNetworkSettings Settings, bool Created)> BootstrapAsync(ManagedNetworkSettings request)
    {
        await using var transaction = await managementDb.Database.BeginTransactionAsync(IsolationLevel.Serializable);
        var row = await LockSettingsAsync();
        if (row is not null)
        {
            await transaction.CommitAsync();
            return (Copy(Deserialize(row), row.Revision, []), false);
        }
        var normalized = Normalize(request);
        managementDb.NetworkSettings.Add(new ManagedNetworkSettingsEntity
        {
            Id = 1, Revision = 1, SettingsJson = Serialize(normalized), UpdatedAtUtc = Now(),
        });
        AddAudit("settings.bootstrap", null, null, null, Serialize(normalized));
        await SaveAsync();
        await transaction.CommitAsync();
        return ((await GetSettingsAsync())!, true);
    }

    public async Task<ManagedNetworkSettings> UpdateSettingsAsync(ManagedNetworkSettings request, Guid actorUuid)
    {
        var normalized = Normalize(request);
        await using var transaction = await managementDb.Database.BeginTransactionAsync(IsolationLevel.Serializable);
        var row = await LockSettingsAsync();
        if ((row?.Revision ?? 0) != request.Revision) throw new NetworkManagementConflictException();
        var previous = row is null ? null : Deserialize(row);
        var newUserIds = AllUserIds(normalized).Except(previous is null ? [] : AllUserIds(previous)).ToArray();
        var knownIds = await gameDb.Users.AsNoTracking().Where(user => !user.IsDeleted && newUserIds.Contains(user.Uuid))
            .Select(user => user.Uuid).ToListAsync();
        if (newUserIds.Except(knownIds).Any()) throw new ArgumentException("追加するプレイヤーは登録済みMCIDの候補から選択してください。");
        var before = row?.SettingsJson;
        if (row is null)
        {
            row = new ManagedNetworkSettingsEntity { Id = 1 };
            managementDb.NetworkSettings.Add(row);
        }
        row.SettingsJson = Serialize(normalized);
        row.Revision++;
        row.UpdatedAtUtc = Now();
        row.UpdatedBy = actorUuid;
        AddAudit("settings.update", actorUuid, null, before, row.SettingsJson);
        await SaveAsync();
        await transaction.CommitAsync();
        return (await GetSettingsAsync(includePlayers: true))!;
    }

    public async Task<IReadOnlyList<NetworkManagedPlayer>> SearchPlayersAsync(string? query)
    {
        var prefix = query?.Trim() ?? string.Empty;
        if (prefix.Length > 100) throw new ArgumentException("MCIDの検索文字列が長すぎます。");
        return await gameDb.Users.AsNoTracking().Where(user => !user.IsDeleted && user.Mcid.StartsWith(prefix))
            .OrderBy(user => user.Mcid).ThenBy(user => user.Uuid).Take(20)
            .Select(user => new NetworkManagedPlayer(user.Uuid, user.Mcid)).ToListAsync();
    }

    public async Task<NetworkChannelAccessResponse> GetChannelAccessAsync(Guid userUuid, string serverId)
    {
        var permission = await gameDb.Users.AsNoTracking().Where(user => user.Uuid == userUuid && !user.IsDeleted)
            .Select(user => (int?)user.Permission).FirstOrDefaultAsync() ?? 0;
        return NetworkAccessPolicy.Evaluate(userUuid, serverId, await GetSettingsAsync(), permission);
    }

    public async Task<bool> CanManageBanFromGameAsync(Guid actorUuid)
    {
        // Existing server console operations use SystemUser's nil UUID. This endpoint is API-key protected.
        if (actorUuid == Guid.Empty) return true;
        var permission = await gameDb.Users.AsNoTracking().Where(user => user.Uuid == actorUuid && !user.IsDeleted)
            .Select(user => (int?)user.Permission).FirstOrDefaultAsync() ?? 0;
        return permission >= 99 || (await GetSettingsAsync())?.AuthorityUsers.Contains(actorUuid) == true;
    }

    public async Task<NetworkBanStateResponse?> GetBanAsync(Guid userUuid)
    {
        var row = await managementDb.NetworkBans.AsNoTracking().SingleOrDefaultAsync(x => x.UserUuid == userUuid);
        var user = await gameDb.Users.AsNoTracking().FirstOrDefaultAsync(x => x.Uuid == userUuid && !x.IsDeleted);
        var identity = user?.Mcid ?? await managementDb.Players.AsNoTracking().Where(x => x.PlayerUuid == userUuid).Select(x => x.Mcid).SingleOrDefaultAsync();
        if (identity is null && row is null) return null;
        if (row is not null) return BanResponse(row, identity ?? string.Empty);
        DateTimeOffset? legacyExpiry = user?.BanDate is DateTime date
            ? new DateTimeOffset(TimeZoneInfo.ConvertTimeToUtc(DateTime.SpecifyKind(date, DateTimeKind.Unspecified), clock.LocalTimeZone)) : null;
        var indefinite = user?.BanIndefinite == true;
        return new NetworkBanStateResponse
        {
            UserUuid = userUuid, Mcid = identity!, Revision = 0,
            IsBanned = indefinite || legacyExpiry.HasValue,
            IsActive = indefinite || legacyExpiry > clock.GetUtcNow(),
            IsIndefinite = indefinite, ExpiresAtUtc = indefinite ? null : legacyExpiry, ServerTimeUtc = clock.GetUtcNow(),
        };
    }

    public async Task<IReadOnlyList<NetworkBanStateResponse>> GetActiveBansAsync()
    {
        var now = Now();
        var rows = await managementDb.NetworkBans.AsNoTracking()
            .Where(x => x.IsBanned && (x.ExpiresAtUtc == null || x.ExpiresAtUtc > now)).ToListAsync();
        var names = (await ResolvePlayersAsync(rows.Select(x => x.UserUuid))).ToDictionary(x => x.UserUuid, x => x.Mcid);
        return rows.Select(row => BanResponse(row, names.GetValueOrDefault(row.UserUuid, string.Empty))).ToArray();
    }

    public async Task<NetworkBanStateResponse?> UpdateBanAsync(Guid userUuid, NetworkBanUpdateRequest request, Guid actorUuid)
    {
        if (userUuid == Guid.Empty || request.ExpectedRevision < 0 || request.Reason?.Length > 500)
            throw new ArgumentException("BAN対象・版番号・理由を確認してください（理由は500文字以内）。");
        if (request.IsBanned && request.ExpiresAtUtc.HasValue && request.ExpiresAtUtc <= clock.GetUtcNow())
            throw new ArgumentException("BANの解除日時は現在より後にしてください。");
        var identity = await gameDb.Users.AsNoTracking().Where(x => x.Uuid == userUuid && !x.IsDeleted).Select(x => x.Mcid).FirstOrDefaultAsync()
            ?? await managementDb.Players.AsNoTracking().Where(x => x.PlayerUuid == userUuid).Select(x => x.Mcid).FirstOrDefaultAsync();
        if (identity is null) return null;
        await using var transaction = await managementDb.Database.BeginTransactionAsync(IsolationLevel.Serializable);
        var row = managementDb.Database.IsSqlServer()
            ? await managementDb.NetworkBans.FromSqlInterpolated($"SELECT * FROM dbo.network_ban WITH (UPDLOCK,HOLDLOCK) WHERE user_uuid = {userUuid}").SingleOrDefaultAsync()
            : await managementDb.NetworkBans.SingleOrDefaultAsync(x => x.UserUuid == userUuid);
        if ((row?.Revision ?? 0) != request.ExpectedRevision) throw new NetworkManagementConflictException();
        var before = row is null ? null : Serialize(row);
        if (row is null)
        {
            row = new ManagedNetworkBanEntity { UserUuid = userUuid };
            managementDb.NetworkBans.Add(row);
        }
        row.IsBanned = request.IsBanned;
        row.ExpiresAtUtc = request.IsBanned ? request.ExpiresAtUtc?.UtcDateTime : null;
        row.Reason = string.IsNullOrWhiteSpace(request.Reason) ? null : request.Reason.Trim();
        row.Revision++;
        row.UpdatedAtUtc = Now();
        row.UpdatedBy = actorUuid;
        // Retain identity even for players who have never signed in to the website or after a game reset.
        if (!await managementDb.Players.AnyAsync(x => x.PlayerUuid == userUuid))
            managementDb.Players.Add(new ManagementPlayerEntity { PlayerUuid = userUuid, Mcid = identity, CreatedAt = Now(), UpdatedAt = Now() });
        AddAudit(request.IsBanned ? "ban.update" : "ban.clear", actorUuid, userUuid, before, Serialize(row));
        await SaveAsync();
        await transaction.CommitAsync();
        return BanResponse(row, identity);
    }

    private Task<ManagedNetworkSettingsEntity?> LockSettingsAsync() => managementDb.Database.IsSqlServer()
        ? managementDb.NetworkSettings.FromSqlRaw("SELECT * FROM dbo.network_settings WITH (UPDLOCK,HOLDLOCK) WHERE id = 1").SingleOrDefaultAsync()
        : managementDb.NetworkSettings.SingleOrDefaultAsync(x => x.Id == 1);

    private async Task SaveAsync()
    {
        try { await managementDb.SaveChangesAsync(); }
        catch (DbUpdateConcurrencyException) { managementDb.ChangeTracker.Clear(); throw new NetworkManagementConflictException(); }
    }

    private async Task<IReadOnlyList<NetworkManagedPlayer>> ResolvePlayersAsync(IEnumerable<Guid> userIds)
    {
        var ids = userIds.Distinct().ToArray();
        var result = await gameDb.Users.AsNoTracking().Where(x => !x.IsDeleted && ids.Contains(x.Uuid))
            .Select(x => new NetworkManagedPlayer(x.Uuid, x.Mcid)).ToListAsync();
        var missing = ids.Except(result.Select(x => x.UserUuid)).ToArray();
        result.AddRange(await managementDb.Players.AsNoTracking().Where(x => missing.Contains(x.PlayerUuid))
            .Select(x => new NetworkManagedPlayer(x.PlayerUuid, x.Mcid)).ToListAsync());
        return result;
    }

    private NetworkBanStateResponse BanResponse(ManagedNetworkBanEntity row, string mcid)
    {
        var expiry = row.ExpiresAtUtc is DateTime date ? new DateTimeOffset(DateTime.SpecifyKind(date, DateTimeKind.Utc)) : (DateTimeOffset?)null;
        return new NetworkBanStateResponse
        {
            UserUuid = row.UserUuid, Mcid = mcid, Revision = row.Revision, IsBanned = row.IsBanned,
            IsActive = row.IsBanned && (expiry is null || expiry > clock.GetUtcNow()),
            IsIndefinite = row.IsBanned && expiry is null, ExpiresAtUtc = expiry, Reason = row.Reason, ServerTimeUtc = clock.GetUtcNow(),
        };
    }

    private static ManagedNetworkSettings Normalize(ManagedNetworkSettings source)
    {
        if (source.Revision < 0 || source.AuthorityUsers is null || source.Channels is null || source.Channels.Count is < 1 or > 128
            || source.TransferCooldownSeconds < 0 || source.TabRefreshSeconds < 1
            || source.PresenceHeartbeatSeconds < 1)
            throw new ArgumentException("チャンネルと更新間隔を確認してください。更新間隔は1秒以上です。");
        static Guid[] Members(IReadOnlyList<Guid>? ids)
        {
            if (ids is null || ids.Count > 5000 || ids.Contains(Guid.Empty)) throw new ArgumentException("プレイヤーUUIDの一覧が不正です。");
            return ids.Distinct().Order().ToArray();
        }
        var channels = source.Channels.Select(channel =>
        {
            if (channel is null || string.IsNullOrEmpty(channel.ServerId) || !Regex.IsMatch(channel.ServerId, "^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$")
                || string.IsNullOrWhiteSpace(channel.DisplayName) || channel.DisplayName.Trim().Length > 100
                || channel.MaxPlayers < 0 || channel.DonorExtraPlayers < 0 || channel.AdminExtraPlayers < 0)
                throw new ArgumentException("接続先ID・チャンネル名・人数を確認してください。");
            return new ManagedNetworkChannel
            {
                ServerId = channel.ServerId, DisplayName = channel.DisplayName.Trim(), IsGame = channel.IsGame,
                MaxPlayers = channel.MaxPlayers, DonorExtraPlayers = channel.DonorExtraPlayers, AdminExtraPlayers = channel.AdminExtraPlayers,
                DiscordEnabled = channel.DiscordEnabled, WhitelistEnabled = channel.WhitelistEnabled,
                DebugUsers = Members(channel.DebugUsers), WhitelistUsers = Members(channel.WhitelistUsers),
            };
        }).ToArray();
        if (channels.Select(x => x.ServerId).Distinct(StringComparer.OrdinalIgnoreCase).Count() != channels.Length
            || !channels.Any(x => string.Equals(x.ServerId, source.LobbyServerId, StringComparison.OrdinalIgnoreCase) && !x.IsGame))
            throw new ArgumentException("接続先IDは重複できません。ロビーIDと一致する非ゲームチャンネルが必要です。");
        return new ManagedNetworkSettings
        {
            Revision = 0, LobbyServerId = source.LobbyServerId, TransferCooldownSeconds = source.TransferCooldownSeconds,
            TabRefreshSeconds = source.TabRefreshSeconds, PresenceHeartbeatSeconds = source.PresenceHeartbeatSeconds,
            AuthorityUsers = Members(source.AuthorityUsers), Channels = channels,
        };
    }

    private static IEnumerable<Guid> AllUserIds(ManagedNetworkSettings settings) => settings.AuthorityUsers
        .Concat(settings.Channels.SelectMany(x => x.DebugUsers.Concat(x.WhitelistUsers))).Distinct();
    private static ManagedNetworkSettings Copy(ManagedNetworkSettings source, int revision, IReadOnlyList<NetworkManagedPlayer> players) => new()
    {
        Revision = revision, LobbyServerId = source.LobbyServerId, TransferCooldownSeconds = source.TransferCooldownSeconds,
        TabRefreshSeconds = source.TabRefreshSeconds, PresenceHeartbeatSeconds = source.PresenceHeartbeatSeconds,
        Channels = source.Channels, AuthorityUsers = source.AuthorityUsers, Players = players,
    };
    private static ManagedNetworkSettings Deserialize(ManagedNetworkSettingsEntity row) => JsonSerializer.Deserialize<ManagedNetworkSettings>(row.SettingsJson, JsonOptions)
        ?? throw new InvalidDataException("Managed network settings are empty.");
    private static string Serialize<T>(T value) => JsonSerializer.Serialize(value, JsonOptions);
    private DateTime Now() => clock.GetUtcNow().UtcDateTime;
    private void AddAudit(string operation, Guid? actor, Guid? target, string? before, string after) => managementDb.NetworkAudits.Add(new()
    {
        AuditId = Guid.NewGuid(), Operation = operation, ActorUuid = actor, TargetUuid = target,
        BeforeJson = before, AfterJson = after, OccurredAtUtc = Now(),
    });
}
