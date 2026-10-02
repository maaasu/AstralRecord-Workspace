using System.Data;
using System.Security.Cryptography;
using System.Text;
using System.Text.Encodings.Web;
using System.Text.Json;
using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;

namespace AstralRecordApi.Repositories;

/// <summary>管理編集のユーザー単位ロックとオフライン操作を永続化します。</summary>
public sealed partial class PlayerAdminEditRepository(
    AstralRecordDbContext dbContext,
    IItemRepository itemRepository,
    IClassRepository classRepository,
    IConfiguration configuration,
    IOptions<Microsoft.AspNetCore.Mvc.JsonOptions>? mvcJsonOptions = null) : IPlayerAdminEditRepository
{
    private static readonly string[] ActiveStatuses = ["DRAINING", "READY", "APPLYING", "RECOVERY_REQUIRED"];
    private static readonly TimeSpan SessionLifetime = TimeSpan.FromMinutes(30);
    private readonly JsonSerializerOptions catalogJsonOptions = mvcJsonOptions?.Value.JsonSerializerOptions
        ?? new JsonSerializerOptions(JsonSerializerDefaults.Web)
        {
            Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping,
        };

    public async Task<bool> IsUserLockedAsync(Guid userUuid)
    {
        if (userUuid == Guid.Empty) return false;
        var strategy = dbContext.Database.CreateExecutionStrategy();
        return await strategy.ExecuteAsync(async () =>
        {
            await using var tx = await dbContext.Database.BeginTransactionAsync(IsolationLevel.Serializable);
            // Serializes admission with edit Start's durable lock insertion.
            if (!await LockUserAsync(userUuid)) return false;
            var locked = await dbContext.PlayerAdminEditSessions.AsNoTracking()
                .AnyAsync(x => x.UserUuid == userUuid && ActiveStatuses.Contains(x.Status));
            await tx.CommitAsync();
            return locked;
        });
    }

    public async Task<PlayerAdminResult<PlayerAdminEditSessionResponse>> StartAsync(
        Guid accountId, Guid actor, PlayerAdminEditStartRequest request)
    {
        if (accountId == Guid.Empty || actor == Guid.Empty || request.EditSessionId == Guid.Empty
            || string.IsNullOrWhiteSpace(request.Reason) || request.Reason.Length > 500)
            return Fail<PlayerAdminEditSessionResponse>(400, "Invalid edit request.");
        var priorIdentity = await dbContext.PlayerAdminEditSessions.AsNoTracking()
            .Where(x => x.EditSessionId == request.EditSessionId)
            .Select(x => new { x.AccountId, x.ActorUserUuid, x.Reason }).SingleOrDefaultAsync();
        if (priorIdentity is not null && (priorIdentity.AccountId != accountId
            || priorIdentity.ActorUserUuid != actor || priorIdentity.Reason != request.Reason.Trim()))
            return Fail<PlayerAdminEditSessionResponse>(409, "Edit session ID was reused.");
        var accountOwner = await dbContext.Accounts.AsNoTracking()
            .Where(x => x.Uuid == accountId && !x.IsDeleted).Select(x => (Guid?)x.UserId).SingleOrDefaultAsync();
        if (!accountOwner.HasValue) return Fail<PlayerAdminEditSessionResponse>(404, "Account not found.");
        return await InTransactionAsync(async () =>
        {
            if (!await LockUserAsync(accountOwner.Value))
                return Fail<PlayerAdminEditSessionResponse>(404, "User not found.");
            var account = await LockAccountAsync(accountId);
            if (account is null || account.UserId != accountOwner.Value)
                return Fail<PlayerAdminEditSessionResponse>(409, "Account owner changed.");
            var sameId = await dbContext.PlayerAdminEditSessions
                .SingleOrDefaultAsync(x => x.EditSessionId == request.EditSessionId);
            if (sameId is not null)
                return sameId.AccountId == accountId && sameId.UserUuid == account.UserId
                    && sameId.ActorUserUuid == actor && sameId.Reason == request.Reason.Trim()
                    ? new(200, await MapSessionAsync(sameId))
                    : Fail<PlayerAdminEditSessionResponse>(409, "Edit session ID was reused.");
            var existing = await dbContext.PlayerAdminEditSessions
                .SingleOrDefaultAsync(x => x.UserUuid == account.UserId && ActiveStatuses.Contains(x.Status));
            if (existing is not null)
                return Fail<PlayerAdminEditSessionResponse>(409, "User already has an edit lock.");

            var required = configuration.GetSection("PlayerAdmin:RequiredServerIds").Get<string[]>() ?? [];
            if (required.Length == 0 || required.Any(string.IsNullOrWhiteSpace)
                || required.Distinct(StringComparer.OrdinalIgnoreCase).Count() != required.Length)
                return Fail<PlayerAdminEditSessionResponse>(409, "Required server roster is not configured.");
            var servers = await dbContext.PlayerAdminServerRuntimes
                .Where(x => x.Enabled).OrderBy(x => x.ServerId).ToListAsync();
            if (servers.Count == 0 || required.Any(id => !servers.Any(x =>
                    string.Equals(x.ServerId, id, StringComparison.OrdinalIgnoreCase)))
                || !servers.Any(x => x.Role == "RPG")
                || !servers.Any(x => x.Role == "LOBBY") || !servers.Any(x => x.Role == "PROXY"))
                return Fail<PlayerAdminEditSessionResponse>(409, "Required runtime registrations are missing.");

            var catalogHash = ComputeCatalogHash();
            var classHash = ComputeClassCatalogHash();
            if (servers.Where(x => x.Role == "RPG").Any(x =>
                !string.Equals(x.ItemCatalogHash, catalogHash, StringComparison.Ordinal)
                || !string.Equals(x.ClassCatalogHash, classHash, StringComparison.Ordinal)))
                return Fail<PlayerAdminEditSessionResponse>(409, "RPG item catalog is not synchronized.");
            var now = DateTime.UtcNow;
            var session = new PlayerAdminEditSessionEntity
            {
                EditSessionId = request.EditSessionId, UserUuid = account.UserId,
                AccountId = accountId, ActorUserUuid = actor, Reason = request.Reason.Trim(),
                Status = "DRAINING", Revision = 1, ExpectedServerCount = servers.Count,
                ItemCatalogHash = catalogHash, ClassCatalogHash = classHash,
                CreatedAtUtc = now, UpdatedAtUtc = now,
                ExpiresAtUtc = now + SessionLifetime,
            };
            dbContext.PlayerAdminEditSessions.Add(session);
            foreach (var server in servers)
                dbContext.PlayerAdminEditDrains.Add(new PlayerAdminEditDrainEntity
                {
                    EditSessionId = session.EditSessionId,
                    ServerId = server.ServerId,
                    ServerSessionId = server.ServerSessionId,
                });
            await dbContext.SaveChangesAsync();
            return new PlayerAdminResult<PlayerAdminEditSessionResponse>(201, await MapSessionAsync(session));
        });
    }

    public async Task<PlayerAdminResult<PlayerAdminEditSessionResponse>> GetActiveAsync(Guid accountId, Guid actor)
    {
        var session = await dbContext.PlayerAdminEditSessions.AsNoTracking().Where(x =>
            x.AccountId == accountId && ActiveStatuses.Contains(x.Status))
            .OrderByDescending(x => x.CreatedAtUtc).FirstOrDefaultAsync();
        return session is null ? Fail<PlayerAdminEditSessionResponse>(404, "No active edit session.")
            : session.ActorUserUuid != actor ? Fail<PlayerAdminEditSessionResponse>(403, "Session owner mismatch.")
            : new(200, await MapSessionAsync(session));
    }

    public async Task<PlayerAdminResult<PlayerAdminEditSessionResponse>> GetByIdAsync(Guid editSessionId, Guid actor)
    {
        var session = await dbContext.PlayerAdminEditSessions.AsNoTracking()
            .SingleOrDefaultAsync(x => x.EditSessionId == editSessionId);
        return session is null ? Fail<PlayerAdminEditSessionResponse>(404, "Edit session not found.")
            : session.ActorUserUuid != actor ? Fail<PlayerAdminEditSessionResponse>(403, "Session owner mismatch.")
            : new(200, await MapSessionAsync(session));
    }

    public async Task<PlayerAdminResult<PlayerAdminEditSessionResponse>> RefreshAsync(Guid editSessionId, Guid actor)
    {
        var owner = await dbContext.PlayerAdminEditSessions.AsNoTracking()
            .Where(x => x.EditSessionId == editSessionId).Select(x => (Guid?)x.UserUuid).SingleOrDefaultAsync();
        if (!owner.HasValue) return Fail<PlayerAdminEditSessionResponse>(404, "Edit session not found.");
        return await InTransactionAsync(async () =>
        {
            if (!await LockUserAsync(owner.Value)) return Fail<PlayerAdminEditSessionResponse>(404, "User not found.");
            var session = await dbContext.PlayerAdminEditSessions.SingleAsync(x => x.EditSessionId == editSessionId);
            if (session.ActorUserUuid != actor) return Fail<PlayerAdminEditSessionResponse>(403, "Session owner mismatch.");
            if (!ActiveStatuses.Contains(session.Status)) return new(200, await MapSessionAsync(session));
            var safe = await IsFullyDrainedAsync(session);
            var now = DateTime.UtcNow;
            if (!safe && (session.ExpiresAtUtc <= now || session.Status == "READY"))
                session.Status = "RECOVERY_REQUIRED";
            else if (safe)
                session.Status = "READY";
            if (safe) session.ExpiresAtUtc = now + SessionLifetime;
            session.UpdatedAtUtc = now;
            session.Revision++;
            await dbContext.SaveChangesAsync();
            return new PlayerAdminResult<PlayerAdminEditSessionResponse>(200, await MapSessionAsync(session));
        });
    }

    public async Task<PlayerAdminResult<PlayerAdminRuntimeRegistrationResponse>> RegisterServerAsync(
        string serverId, PlayerAdminRuntimeRegistrationRequest request)
    {
        if (!ValidServer(serverId) || request.ServerSessionId == Guid.Empty
            || request.Role is not ("RPG" or "LOBBY" or "PROXY")
            || request.Role == "RPG" && (!ValidHash(request.ItemCatalogHash)
                || !ValidHash(request.ClassCatalogHash)))
            return Fail<PlayerAdminRuntimeRegistrationResponse>(400, "Invalid runtime registration.");
        return await InTransactionAsync(async () =>
        {
            var server = await dbContext.PlayerAdminServerRuntimes.SingleOrDefaultAsync(x => x.ServerId == serverId);
            var now = DateTime.UtcNow;
            if (server is null)
            {
                server = new PlayerAdminServerRuntimeEntity { ServerId = serverId, RegisteredAtUtc = now };
                dbContext.PlayerAdminServerRuntimes.Add(server);
            }
            else if (!string.Equals(server.Role, request.Role, StringComparison.Ordinal))
            {
                // Drain quorum and safeToDisconnect depend on this role. Reusing a
                // server ID as another role would reinterpret captured ACKs.
                return Fail<PlayerAdminRuntimeRegistrationResponse>(409, "Server role cannot change.");
            }
            if (server.ServerSessionId != Guid.Empty && server.ServerSessionId != request.ServerSessionId
                && await dbContext.PlayerAdminEditDrains.AnyAsync(x => x.ServerId == serverId
                    && x.ServerSessionId == server.ServerSessionId && x.AcknowledgedAtUtc == null
                    && dbContext.PlayerAdminEditSessions.Any(s => s.EditSessionId == x.EditSessionId
                        && ActiveStatuses.Contains(s.Status))))
                return Fail<PlayerAdminRuntimeRegistrationResponse>(409, "Previous server boot has an unresolved drain.");
            server.ServerSessionId = request.ServerSessionId;
            server.Role = request.Role;
            server.ItemCatalogHash = request.ItemCatalogHash;
            server.ClassCatalogHash = request.ClassCatalogHash;
            server.Enabled = true;
            server.LastSeenUtc = now;
            await dbContext.SaveChangesAsync();
            return new PlayerAdminResult<PlayerAdminRuntimeRegistrationResponse>(200,
                new(serverId, server.ServerSessionId, server.Role));
        });
    }

    public async Task<PlayerAdminResult<IReadOnlyList<PlayerAdminDrainResponse>>> GetDrainsAsync(
        string serverId, Guid serverSessionId)
    {
        if (!ValidServer(serverId) || serverSessionId == Guid.Empty)
            return Fail<IReadOnlyList<PlayerAdminDrainResponse>>(400, "Invalid server identity.");
        var registered = await dbContext.PlayerAdminServerRuntimes.AsNoTracking()
            .AnyAsync(x => x.ServerId == serverId && x.ServerSessionId == serverSessionId && x.Enabled);
        if (!registered) return Fail<IReadOnlyList<PlayerAdminDrainResponse>>(409, "Server boot is not registered.");
        var drains = await dbContext.PlayerAdminEditDrains.AsNoTracking()
            .Where(x => x.ServerId == serverId && x.ServerSessionId == serverSessionId)
            .Join(dbContext.PlayerAdminEditSessions.AsNoTracking(), x => x.EditSessionId,
                s => s.EditSessionId, (x, s) => new { Drain = x, Session = s })
            .Where(x => ActiveStatuses.Contains(x.Session.Status)).ToListAsync();
        var result = new List<PlayerAdminDrainResponse>();
        foreach (var row in drains)
            result.Add(new(row.Session.EditSessionId, row.Session.AccountId, row.Session.UserUuid,
                row.Session.ExpiresAtUtc <= DateTime.UtcNow ? "RECOVERY_REQUIRED" : row.Session.Status,
                row.Session.Revision, await AllRpgDrainsAcknowledgedAsync(row.Session.EditSessionId)));
        return new(200, result);
    }

    public async Task<PlayerAdminResult<PlayerAdminEditSessionResponse>> AcknowledgeDrainAsync(
        Guid editSessionId, PlayerAdminDrainAckRequest request)
    {
        if (!ValidServer(request.ServerId) || request.ServerSessionId == Guid.Empty
            || request.AckId == Guid.Empty || !request.Saved || !request.Offline)
            return Fail<PlayerAdminEditSessionResponse>(400, "Drain acknowledgement is not safe.");
        var owner = await dbContext.PlayerAdminEditSessions.AsNoTracking()
            .Where(x => x.EditSessionId == editSessionId).Select(x => (Guid?)x.UserUuid).SingleOrDefaultAsync();
        if (!owner.HasValue) return Fail<PlayerAdminEditSessionResponse>(404, "Edit session not found.");
        return await InTransactionAsync(async () =>
        {
            if (!await LockUserAsync(owner.Value)) return Fail<PlayerAdminEditSessionResponse>(404, "User not found.");
            var session = await dbContext.PlayerAdminEditSessions.SingleAsync(x => x.EditSessionId == editSessionId);
            if (session.UserUuid != request.UserUuid || session.AccountId != request.AccountId)
                return Fail<PlayerAdminEditSessionResponse>(409, "Edit session identity or state changed.");
            var drain = await dbContext.PlayerAdminEditDrains.SingleOrDefaultAsync(x =>
                x.EditSessionId == editSessionId && x.ServerId == request.ServerId);
            var runtime = await dbContext.PlayerAdminServerRuntimes.AsNoTracking()
                .SingleOrDefaultAsync(x => x.ServerId == request.ServerId);
            if (drain is null || drain.ServerSessionId != request.ServerSessionId)
                return Fail<PlayerAdminEditSessionResponse>(409, "Server boot changed.");
            if (drain.AcknowledgedAtUtc.HasValue)
                return drain.AckId == request.AckId ? new(200, await MapSessionAsync(session))
                    : Fail<PlayerAdminEditSessionResponse>(409, "Drain was already acknowledged.");
            if (runtime is null || runtime.ServerSessionId != request.ServerSessionId)
                return Fail<PlayerAdminEditSessionResponse>(409, "Server boot changed.");
            if (!ActiveStatuses.Contains(session.Status))
                return Fail<PlayerAdminEditSessionResponse>(409, "Edit session is already terminal.");
            if (runtime.Role != "RPG" && !await AllRpgDrainsAcknowledgedAsync(editSessionId))
                return Fail<PlayerAdminEditSessionResponse>(409, "RPG save is still pending.");
            if (runtime.Role == "RPG" && await HasUnclosedSessionAsync(session.UserUuid, request.ServerId))
                return Fail<PlayerAdminEditSessionResponse>(409, "RPG account session is not closed.");
            drain.Saved = true;
            drain.Offline = true;
            drain.AckId = request.AckId;
            drain.AcknowledgedAtUtc = DateTime.UtcNow;
            await dbContext.SaveChangesAsync();
            if (await IsFullyDrainedAsync(session))
                session.Status = session.ExpiresAtUtc > DateTime.UtcNow ? "READY" : "RECOVERY_REQUIRED";
            session.Revision++;
            session.UpdatedAtUtc = DateTime.UtcNow;
            await dbContext.SaveChangesAsync();
            return new PlayerAdminResult<PlayerAdminEditSessionResponse>(200, await MapSessionAsync(session));
        });
    }

    private async Task<bool> IsFullyDrainedAsync(PlayerAdminEditSessionEntity session)
    {
        if (session.ExpectedServerCount <= 0) return false;
        var drains = await dbContext.PlayerAdminEditDrains.AsNoTracking()
            .Where(x => x.EditSessionId == session.EditSessionId).ToListAsync();
        if (drains.Count != session.ExpectedServerCount || drains.Any(x =>
            !x.Saved || !x.Offline || !x.AcknowledgedAtUtc.HasValue)) return false;
        foreach (var drain in drains)
        {
            var runtime = await dbContext.PlayerAdminServerRuntimes.AsNoTracking()
                .SingleOrDefaultAsync(x => x.ServerId == drain.ServerId);
            if (runtime is null || !runtime.Enabled || runtime.ServerSessionId != drain.ServerSessionId)
                return false;
        }
        return !await HasUnclosedSessionAsync(session.UserUuid);
    }

    private async Task<bool> HasUnclosedSessionAsync(Guid userUuid, string? serverId = null)
    {
        // A soft-deleted account can still have an unclosed gameplay lease. Its deletion
        // flag is not proof that the old server has persisted and released that lease.
        var accounts = await dbContext.Accounts.AsNoTracking().Where(x => x.UserId == userUuid)
            .Select(x => x.Uuid).ToArrayAsync();
        return await dbContext.SkillTreeAccountSessions.AsNoTracking()
            .AnyAsync(x => accounts.Contains(x.AccountId) && !x.Closed
                && (serverId == null || x.ServerId == serverId));
    }

    private async Task<bool> AllRpgDrainsAcknowledgedAsync(Guid editSessionId)
    {
        var rpg = await dbContext.PlayerAdminEditDrains.AsNoTracking()
            .Where(x => x.EditSessionId == editSessionId)
            .Join(dbContext.PlayerAdminServerRuntimes.AsNoTracking(), x => x.ServerId,
                s => s.ServerId, (x, s) => new { Drain = x, Runtime = s })
            .Where(x => x.Runtime.Role == "RPG").ToListAsync();
        return rpg.Count > 0 && rpg.All(x => x.Drain.Saved && x.Drain.Offline
            && x.Drain.AcknowledgedAtUtc.HasValue
            && x.Runtime.ServerSessionId == x.Drain.ServerSessionId);
    }

    private async Task<PlayerAdminEditSessionResponse> MapSessionAsync(PlayerAdminEditSessionEntity entity) => new(
        entity.EditSessionId, entity.AccountId, entity.UserUuid, entity.ActorUserUuid,
        entity.Reason, ActiveStatuses.Contains(entity.Status) && entity.ExpiresAtUtc <= DateTime.UtcNow
            ? "RECOVERY_REQUIRED" : entity.Status,
        entity.Revision, entity.CreatedAtUtc, entity.UpdatedAtUtc, entity.ExpiresAtUtc, entity.CompletedAtUtc,
        entity.ExpectedServerCount,
        await dbContext.PlayerAdminEditDrains.AsNoTracking().CountAsync(x =>
            x.EditSessionId == entity.EditSessionId && x.AcknowledgedAtUtc != null));

    private async Task<bool> LockUserAsync(Guid userUuid)
    {
        var query = dbContext.Database.IsSqlServer()
            ? dbContext.Users.FromSqlInterpolated($"SELECT * FROM [dbo].[user] WITH (UPDLOCK,HOLDLOCK) WHERE [uuid] = {userUuid}")
            : dbContext.Users.Where(x => x.Uuid == userUuid);
        return await query.AnyAsync(x => !x.IsDeleted);
    }

    private Task<AccountEntity?> LockAccountAsync(Guid accountId)
    {
        var query = dbContext.Database.IsSqlServer()
            ? dbContext.Accounts.FromSqlInterpolated($"SELECT * FROM [dbo].[account] WITH (UPDLOCK,HOLDLOCK) WHERE [uuid] = {accountId}")
            : dbContext.Accounts.Where(x => x.Uuid == accountId);
        return query.SingleOrDefaultAsync(x => !x.IsDeleted);
    }

    private async Task<PlayerAdminResult<T>> InTransactionAsync<T>(Func<Task<PlayerAdminResult<T>>> action)
    {
        var strategy = dbContext.Database.CreateExecutionStrategy();
        return await strategy.ExecuteAsync(async () =>
        {
            dbContext.ChangeTracker.Clear();
            await using var tx = await dbContext.Database.BeginTransactionAsync(IsolationLevel.Serializable);
            var result = await action();
            if (result.StatusCode is >= 200 and < 300) await tx.CommitAsync();
            return result;
        });
    }

    private string ComputeCatalogHash()
    {
        var lines = new StringBuilder();
        foreach (var summary in itemRepository.GetAllSummaries().OrderBy(x => x.Id, StringComparer.Ordinal))
        {
            var item = itemRepository.GetById(summary.Id);
            if (item is null) throw new InvalidOperationException("Item catalog is incomplete.");
            lines.Append(item.Id).Append('\t').Append(HashCatalogJson(item)).Append('\n');
        }
        return Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(lines.ToString()))).ToLowerInvariant();
    }

    private string ComputeClassCatalogHash()
    {
        var lines = new StringBuilder();
        foreach (var summary in classRepository.GetAllSummaries().OrderBy(x => x.Id, StringComparer.Ordinal))
        {
            var definition = classRepository.GetById(summary.Id);
            if (definition is null) throw new InvalidOperationException("Class catalog is incomplete.");
            lines.Append(definition.Id).Append('\t').Append(HashCatalogJson(definition)).Append('\n');
        }
        return Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(lines.ToString()))).ToLowerInvariant();
    }

    private static bool ValidServer(string? value) => !string.IsNullOrWhiteSpace(value)
        && value.Length <= 64 && value.All(c => char.IsAsciiLetterOrDigit(c) || c is '-' or '_');
    private static bool ValidHash(string? value) => value is { Length: 64 }
        && value.All(c => c is >= '0' and <= '9' or >= 'a' and <= 'f');
    private static PlayerAdminResult<T> Fail<T>(int status, string message) => new(status, default, message);
}
