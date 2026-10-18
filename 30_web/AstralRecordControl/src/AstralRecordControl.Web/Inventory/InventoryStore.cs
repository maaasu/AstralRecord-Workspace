using System.Text.Json;
using AstralRecordControl.Contracts;
using AstralRecordControl.Web.Data;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Configuration;

namespace AstralRecordControl.Web.Inventory;

public sealed class InventoryFault(int status, string code) : Exception(code)
{
    public int Status { get; } = status;
    public string Code { get; } = code;
}

// A single Web instance owns control.db. Serialize read/validate/write transactions,
// including checks spanning several rows, rather than retrying a partially validated write.
public sealed class InventoryWriteGate { internal SemaphoreSlim Semaphore { get; } = new(1); }

public sealed partial class InventoryStore(ControlDbContext db, InventoryWriteGate gate, TimeProvider clock, IConfiguration? configuration = null)
{
    private DateTimeOffset Now => clock.GetUtcNow();
    private bool ProcessControlEnabled => configuration?.GetValue<bool>("Control:EnableProcessControl") == true;
    public async Task<T> WriteAsync<T>(Func<Task<T>> action)
    {
        await gate.Semaphore.WaitAsync();
        try
        {
            await using var transaction = await db.Database.BeginTransactionAsync();
            var result = await action();
            await db.SaveChangesAsync();
            await transaction.CommitAsync();
            return result;
        }
        catch (DbUpdateException) { db.ChangeTracker.Clear(); throw new InventoryFault(409, "OperationConflict"); }
        catch { db.ChangeTracker.Clear(); throw; }
        finally { gate.Semaphore.Release(); }
    }

    public async Task<InventoryHost> CreateHostAsync(HostCreateRequest request, Guid key, string requestJson) => await WriteAsync(async () =>
    {
        var hash = ControlProtocol.Hash("Host:" + requestJson);
        var replay = await ReplayAsync(key, hash);
        if (replay != null) return await HostAsync(replay.EntityId);
        ValidateName(request.HostKey, request.DisplayName);
        if (await db.Hosts.AnyAsync(x => x.HostKey == request.HostKey)) throw new InventoryFault(409, "OperationConflict");
        var host = new InventoryHost { HostId = Guid.NewGuid(), HostKey = request.HostKey, DisplayName = request.DisplayName, CreatedAtUtc = Now, UpdatedAtUtc = Now };
        db.Hosts.Add(host);
        SaveRequest(key, hash, host.HostId, "Host");
        SaveInventory(host, []);
        return host;
    });

    public async Task<InventoryHost> UpdateHostAsync(Guid id, HostUpdateRequest request, long revision) => await WriteAsync(async () =>
    {
        var host = await HostAsync(id);
        if (host.Revision != revision) throw new InventoryFault(409, "StaleRevision");
        ValidateDisplayName(request.DisplayName);
        host.DisplayName = request.DisplayName; host.Enabled = request.Enabled; host.Revision++; host.UpdatedAtUtc = Now;
        if (!request.Enabled) { host.ConnectionState = HostConnectionState.Disabled; host.ConnectionEpoch = null; }
        else if (host.ConnectionState == HostConnectionState.Disabled) host.ConnectionState = HostConnectionState.Offline;
        return host;
    });

    public async Task<InventoryEnvironment> CreateEnvironmentAsync(EnvironmentCreateRequest request, Guid key, string json) => await WriteAsync(async () =>
    {
        var hash = ControlProtocol.Hash("Environment:" + json);
        var replay = await ReplayAsync(key, hash);
        if (replay != null) return await db.Environments.SingleAsync(x => x.EnvironmentId == replay.EntityId);
        ValidateName(request.EnvironmentKey, request.DisplayName);
        if (await db.Environments.AnyAsync(x => x.EnvironmentKey == request.EnvironmentKey)) throw new InventoryFault(409, "OperationConflict");
        var row = new InventoryEnvironment { EnvironmentId = Guid.NewGuid(), EnvironmentKey = request.EnvironmentKey, DisplayName = request.DisplayName, CreatedAtUtc = Now, UpdatedAtUtc = Now };
        db.Environments.Add(row); SaveRequest(key, hash, row.EnvironmentId, "Environment"); return row;
    });

    public async Task<InventoryEnvironment> UpdateEnvironmentAsync(Guid id, EnvironmentUpdateRequest request, long revision) => await WriteAsync(async () =>
    {
        var row = await db.Environments.SingleOrDefaultAsync(x => x.EnvironmentId == id) ?? throw new InventoryFault(404, "NotFound");
        if (row.Revision != revision) throw new InventoryFault(409, "StaleRevision");
        ValidateDisplayName(request.DisplayName); row.DisplayName = request.DisplayName; row.Revision++; row.UpdatedAtUtc = Now; return row;
    });

    public async Task<InventoryServer> CreateServerAsync(ServerCreateRequest request, Guid key, string json, string configJson) => await WriteAsync(async () =>
    {
        var hash = ControlProtocol.Hash("Server:" + json);
        var replay = await ReplayAsync(key, hash);
        if (replay != null) return await ServerAsync(replay.EntityId);
        ValidateName(request.ServerKey, request.DisplayName);
        if (await db.Servers.AnyAsync(x => x.ServerKey == request.ServerKey || x.ServerId == request.Config.ServerId)) throw new InventoryFault(409, "OperationConflict");
        var host = await HostAsync(request.HostId);
        var row = new InventoryServer { ServerId = request.Config.ServerId, HostId = request.HostId, ServerKey = request.ServerKey, DisplayName = request.DisplayName, EnvironmentId = request.EnvironmentId, RuntimeServerId = request.RuntimeServerId, DesiredRevision = 1, DesiredConfigJson = configJson, ConfigSha256 = ControlProtocol.Hash(configJson), ReadOnlyPilot = !ProcessControlEnabled, CreatedAtUtc = Now, UpdatedAtUtc = Now };
        await ValidateServerAsync(row, request.Config, host, 1);
        var existing = await db.Servers.Where(s => s.HostId == host.HostId).ToListAsync();
        var configs = existing.Select(s => ControlJson.Deserialize<ServerConfig>(s.DesiredConfigJson)).Append(request.Config).ToArray();
        ValidateSnapshot(host, configs);
        db.Servers.Add(row); SaveRequest(key, hash, row.ServerId, "Server"); SaveInventory(host, configs); return row;
    });

    public async Task<InventoryServer> UpdateServerAsync(Guid id, ServerUpdateRequest request, long revision, string configJson) => await WriteAsync(async () =>
    {
        var row = await ServerAsync(id);
        if (row.DesiredRevision != revision) throw new InventoryFault(409, "StaleRevision");
        var host = await HostAsync(row.HostId);
        var nextRevision = checked(row.DesiredRevision + 1);
        var next = new InventoryServer { ServerId = id, HostId = row.HostId, EnvironmentId = request.EnvironmentId, RuntimeServerId = request.RuntimeServerId };
        ValidateDisplayName(request.DisplayName);
        await ValidateServerAsync(next, request.Config, host, nextRevision);
        var all = await db.Servers.Where(s => s.HostId == row.HostId).ToListAsync();
        var configs = all.Select(s => s.ServerId == id ? request.Config : ControlJson.Deserialize<ServerConfig>(s.DesiredConfigJson)).ToArray();
        ValidateSnapshot(host, configs);
        row.EnvironmentId = request.EnvironmentId; row.RuntimeServerId = request.RuntimeServerId; row.DisplayName = request.DisplayName;
        row.DesiredRevision = nextRevision; row.DesiredConfigJson = configJson; row.ConfigSha256 = ControlProtocol.Hash(configJson); row.UpdatedAtUtc = Now;
        row.ReadOnlyPilot = !ProcessControlEnabled;
        SaveInventory(host, configs); return row;
    });

    private async Task ValidateServerAsync(InventoryServer server, ServerConfig config, InventoryHost host, long revision)
    {
        if (!host.Enabled) throw new InventoryFault(409, "AgentDisabled");
        if (config.ServerId != server.ServerId || config.Revision != revision) throw new InventoryFault(409, "StaleRevision");
        InventoryValidation.Validate(config, host.HostId);
        if (config.DependencyReadiness != DependencyReadiness.Running) throw new InventoryFault(409, "UnsupportedCapability");
        if (ProcessControlEnabled && config.DependsOnServerIds.Length != 0) throw new InventoryFault(409, "UnsupportedCapability");
        if (config.AutoStart || config.RestartOnCrash)
        {
            var capabilities = ControlJson.Deserialize<string[]>(host.CapabilitiesJson);
            if (!ProcessControlEnabled || !capabilities.Contains("P1.Operations") || !capabilities.Contains("P1.LegacyGate") || config.Kind != ServerKind.Java) throw new InventoryFault(409, "UnsupportedCapability");
        }
        var resources = ControlJson.Deserialize<HostResources>(host.ResourcesJson);
        if (!resources.Roots.Any(r => r.Id == config.ManagedRootId && !r.BackupOnly) || !resources.Executables.Any(e => e.Id == config.ExecutableId && e.Kind == config.Kind)) throw new InventoryFault(400, "PathOutsideRoot");
        if (server.EnvironmentId.HasValue && !await db.Environments.AnyAsync(x => x.EnvironmentId == server.EnvironmentId)) throw new InventoryFault(404, "NotFound");
        if (server.RuntimeServerId is { Length: > 128 } || server.RuntimeServerId?.Any(char.IsControl) == true) throw new InventoryFault(400, "InvalidInput");
        foreach (var dependency in config.DependsOnServerIds)
        {
            var target = await db.Servers.SingleOrDefaultAsync(s => s.ServerId == dependency) ?? throw new InventoryFault(400, "InvalidDependency");
            if (target.HostId != host.HostId) throw new InventoryFault(409, "UnsupportedCapability");
        }
    }
    private static void ValidateSnapshot(InventoryHost host, ServerConfig[] configs)
    {
        InventoryValidation.Validate(new InventorySnapshot { HostId = host.HostId, Revision = host.DesiredInventoryRevision + 1, Servers = configs }, host.HostId);
        if (configs.Select(c => c.ManagedRootId + "/" + c.ServerDirectory).Distinct(StringComparer.OrdinalIgnoreCase).Count() != configs.Length) throw new InventoryFault(409, "OperationConflict");
    }
    private void SaveInventory(InventoryHost host, ServerConfig[] configs, JsonElement[]? backupProfiles = null, JsonElement[]? schedules = null)
    {
        if ((backupProfiles == null || schedules == null) && host.DesiredInventoryRevision > 0)
        {
            var previous = db.InventoryVersions.Local.SingleOrDefault(v => v.HostId == host.HostId && v.Revision == host.DesiredInventoryRevision)
                ?? db.InventoryVersions.AsNoTracking().Single(v => v.HostId == host.HostId && v.Revision == host.DesiredInventoryRevision);
            var old = ControlJson.Deserialize<InventorySnapshot>(previous.SnapshotJson);
            backupProfiles ??= old.BackupProfiles;
            schedules ??= old.Schedules;
        }
        host.DesiredInventoryRevision++;
        var snapshot = new InventorySnapshot { HostId = host.HostId, Revision = host.DesiredInventoryRevision, Servers = configs.OrderBy(c => c.ServerId).ToArray(), BackupProfiles = backupProfiles ?? [], Schedules = schedules ?? [] };
        InventoryValidation.Validate(snapshot, host.HostId);
        var json = ControlJson.Serialize(snapshot);
        if (System.Text.Encoding.UTF8.GetByteCount(json) > ControlProtocol.MaxInventoryBytes) throw new InventoryFault(413, "InventoryTooLarge");
        db.InventoryVersions.Add(new InventoryVersion { HostId = host.HostId, Revision = snapshot.Revision, SnapshotJson = json, Sha256 = ControlProtocol.Hash(json), CreatedAtUtc = Now });
    }
    private async Task<InventoryRequest?> ReplayAsync(Guid key, string hash)
    {
        if (key == Guid.Empty) throw new InventoryFault(400, "InvalidIdempotencyKey");
        var old = await db.InventoryRequests.FindAsync(key);
        if (old != null && old.RequestSha256 != hash) throw new InventoryFault(409, "OperationConflict");
        return old;
    }
    private void SaveRequest(Guid key, string hash, Guid id, string kind) => db.InventoryRequests.Add(new InventoryRequest { IdempotencyKey = key, RequestSha256 = hash, EntityId = id, EntityKind = kind, CreatedAtUtc = Now });
    public async Task<InventoryHost> HostAsync(Guid id) => await db.Hosts.SingleOrDefaultAsync(x => x.HostId == id) ?? throw new InventoryFault(404, "NotFound");
    public async Task<InventoryServer> ServerAsync(Guid id) => await db.Servers.SingleOrDefaultAsync(x => x.ServerId == id) ?? throw new InventoryFault(404, "NotFound");
    public async Task<InventoryEnvelope> InventoryAsync(Guid hostId)
    {
        var host = await HostAsync(hostId);
        var version = await db.InventoryVersions.AsNoTracking().SingleAsync(x => x.HostId == hostId && x.Revision == host.DesiredInventoryRevision);
        return new(1, hostId, version.Revision, version.SnapshotJson, version.Sha256);
    }
    public static void ValidateDisplayName(string value)
    {
        if (string.IsNullOrWhiteSpace(value) || value.Length > 128 || value.Any(char.IsControl)) throw new InventoryFault(400, "InvalidInput");
    }
    private static void ValidateName(string key, string displayName)
    {
        if (key is null || !ControlProtocol.IsKey(key)) throw new InventoryFault(400, "InvalidKey");
        ValidateDisplayName(displayName);
    }
    public async Task<object> OverviewAsync()
    {
        var hosts = await db.Hosts.AsNoTracking().OrderBy(x => x.HostKey).ToArrayAsync();
        foreach (var host in hosts)
            if (host.Enabled && host.ConnectionState is HostConnectionState.Online or HostConnectionState.Reconciling && (!host.LastSeenAtUtc.HasValue || Now - host.LastSeenAtUtc > TimeSpan.FromSeconds(30))) host.ConnectionState = HostConnectionState.Offline;
        return new { observedAtUtc = Now, stale = hosts.Any(h => h.ConnectionState != HostConnectionState.Online), hosts, servers = await db.Servers.AsNoTracking().OrderBy(x => x.ServerKey).ToArrayAsync(), environments = await db.Environments.AsNoTracking().OrderBy(x => x.EnvironmentKey).ToArrayAsync() };
    }
    public async Task ValidateTransferScopeAsync(Guid hostId, Guid transferId, string action)
    {
        var transfer = await db.TransferScopes.AsNoTracking().SingleOrDefaultAsync(x => x.TransferId == transferId) ?? throw new InventoryFault(404, "NotFound");
        var permitted = action == "content" ? transfer.DestinationHostId == hostId : action == "metadata" ? transfer.SourceHostId == hostId || transfer.DestinationHostId == hostId : transfer.SourceHostId == hostId;
        if (!permitted) throw new InventoryFault(403, "HostScopeMismatch");
        throw new InventoryFault(409, "UnsupportedCapability");
    }
}

public sealed record EnvironmentCreateRequest(string EnvironmentKey, string DisplayName);
public sealed record EnvironmentUpdateRequest(string DisplayName);
