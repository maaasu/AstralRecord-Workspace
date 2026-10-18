using System.Text.Json;

namespace AstralRecordControl.Contracts;

public sealed record ManagedRoot
{
    public Guid Id { get; init; }
    public string DisplayName { get; init; } = "";
    public string Path { get; init; } = "";
    public bool BackupOnly { get; init; }
}
public sealed record ManagedExecutable
{
    public Guid Id { get; init; }
    public string DisplayName { get; init; } = "";
    public string Path { get; init; } = "";
    public ServerKind Kind { get; init; }
}
public sealed record HostResources
{
    public ManagedRoot[] Roots { get; init; } = [];
    public ManagedExecutable[] Executables { get; init; } = [];
}
public sealed record ServerConfig
{
    public int SchemaVersion { get; init; } = 1;
    public Guid ServerId { get; init; }
    public Guid HostId { get; init; }
    public long Revision { get; init; }
    public ServerKind Kind { get; init; }
    public Guid ManagedRootId { get; init; }
    public string ServerDirectory { get; init; } = "";
    public Guid ExecutableId { get; init; }
    public string[] ArgumentList { get; init; } = [];
    public string StopCommand { get; init; } = "stop";
    public int? ListenPort { get; init; }
    public bool AutoStart { get; init; }
    public int StartDelaySeconds { get; init; }
    public int StartupTimeoutSeconds { get; init; } = 900;
    public int StopTimeoutSeconds { get; init; } = 120;
    public bool RestartOnCrash { get; init; }
    public int RestartDelaySeconds { get; init; } = 60;
    public int RestartMaxCount { get; init; } = 3;
    public int RestartWindowSeconds { get; init; } = 600;
    public int[] IgnoredExitCodes { get; init; } = [0];
    public Guid[] DependsOnServerIds { get; init; } = [];
    public DependencyReadiness DependencyReadiness { get; init; }
    public int LogRetentionDays { get; init; } = 30;
}
public sealed record InventorySnapshot
{
    public int SchemaVersion { get; init; } = 1;
    public Guid HostId { get; init; }
    public long Revision { get; init; }
    public ServerConfig[] Servers { get; init; } = [];
    public JsonElement[] Schedules { get; init; } = [];
    public JsonElement[] BackupProfiles { get; init; } = [];
}
public sealed record InventoryEnvelope(int ProtocolVersion, Guid HostId, long Revision, string SnapshotJson, string Sha256);

public static class InventoryValidation
{
    public static void Validate(InventorySnapshot inventory, Guid hostId)
    {
        ArgumentNullException.ThrowIfNull(inventory);
        if (inventory.SchemaVersion != 1 || inventory.HostId != hostId || hostId == Guid.Empty || inventory.Revision < 0) throw new ArgumentException("Invalid inventory identity/version.");
        if (inventory.Servers is null || inventory.Schedules is null || inventory.BackupProfiles is null || inventory.Servers.Any(s => s is null)) throw new ArgumentException("Missing inventory arrays.");
        if (inventory.Servers.Length > 32 || inventory.Schedules.Length != 0 || inventory.BackupProfiles.Length > 128) throw new ArgumentException("Unsupported inventory capability.");
        if (inventory.Servers.Select(s => s.ServerId).Distinct().Count() != inventory.Servers.Length) throw new ArgumentException("Duplicate server ID.");
        foreach (var config in inventory.Servers) Validate(config, hostId);
        var profiles = inventory.BackupProfiles.Select(p => ControlJson.Deserialize<BackupProfile>(p.GetRawText())).ToArray();
        if (profiles.Select(p => p.BackupProfileId).Distinct().Count() != profiles.Length) throw new ArgumentException("Duplicate backup profile ID.");
        foreach (var profile in profiles)
        {
            BackupValidation.Validate(profile);
            if (!inventory.Servers.Any(s => s.ServerId == profile.ServerId)) throw new ArgumentException("Backup profile server mismatch.");
        }
        var ports = inventory.Servers.Where(s => s.ListenPort.HasValue).Select(s => s.ListenPort!.Value).ToArray();
        if (ports.Distinct().Count() != ports.Length) throw new ArgumentException("Duplicate port.");
        var nodes = inventory.Servers.ToDictionary(s => s.ServerId);
        var complete = new HashSet<Guid>();
        var visiting = new HashSet<Guid>();
        void Visit(Guid id)
        {
            if (complete.Contains(id)) return;
            if (!visiting.Add(id)) throw new ArgumentException("Dependency cycle.");
            foreach (var dependency in nodes[id].DependsOnServerIds)
                if (nodes.ContainsKey(dependency)) Visit(dependency);
            visiting.Remove(id); complete.Add(id);
        }
        foreach (var id in nodes.Keys) Visit(id);
    }
    public static void Validate(ServerConfig c, Guid hostId)
    {
        ArgumentNullException.ThrowIfNull(c);
        if (c.ArgumentList is null || c.DependsOnServerIds is null || c.IgnoredExitCodes is null || c.IgnoredExitCodes.Length > 128 || c.DependsOnServerIds.Length > 32) throw new ArgumentException("Invalid server arrays.");
        if (c.SchemaVersion != 1 || c.HostId != hostId || c.HostId == Guid.Empty || c.ServerId == Guid.Empty || c.Revision < 1 || c.ManagedRootId == Guid.Empty || c.ExecutableId == Guid.Empty) throw new ArgumentException("Invalid server identity/version.");
        if (!Enum.IsDefined(c.Kind) || !Enum.IsDefined(c.DependencyReadiness) || !ControlProtocol.IsRelativePath(c.ServerDirectory)) throw new ArgumentException("Invalid server kind/path.");
        if (string.IsNullOrWhiteSpace(c.StopCommand) || System.Text.Encoding.UTF8.GetByteCount(c.StopCommand) > 4096 || c.StopCommand.Any(ch => ch is '\r' or '\n' or '\0')) throw new ArgumentException("Invalid stop command.");
        if (c.ArgumentList.Length > 128 || c.ArgumentList.Any(a => a is null || a.Length > 4096 || a.Contains('\0'))) throw new ArgumentException("Invalid arguments.");
        if (c.ListenPort is < 1 or > 65535 || c.StartDelaySeconds is < 0 or > 900 || c.StartupTimeoutSeconds is < 10 or > 3600 || c.StopTimeoutSeconds is < 10 or > 600 || c.RestartDelaySeconds is < 1 or > 3600 || c.RestartMaxCount is < 0 or > 20 || c.RestartWindowSeconds is < 60 or > 86400 || c.LogRetentionDays is < 1 or > 365) throw new ArgumentException("Invalid server limits.");
        if (c.DependsOnServerIds.Contains(c.ServerId) || c.DependsOnServerIds.Any(id => id == Guid.Empty) || c.DependsOnServerIds.Distinct().Count() != c.DependsOnServerIds.Length) throw new ArgumentException("Invalid dependencies.");
    }
    public static InventorySnapshot ReadEnvelope(InventoryEnvelope envelope, Guid hostId)
    {
        ArgumentNullException.ThrowIfNull(envelope);
        if (envelope.SnapshotJson is null) throw new ArgumentException("Missing inventory JSON.");
        if (envelope.ProtocolVersion != 1 || envelope.HostId != hostId || !ControlProtocol.IsHash(envelope.Sha256) || ControlProtocol.Hash(envelope.SnapshotJson) != envelope.Sha256 || System.Text.Encoding.UTF8.GetByteCount(envelope.SnapshotJson) > ControlProtocol.MaxInventoryBytes) throw new ArgumentException("Invalid inventory envelope/hash.");
        var snapshot = ControlJson.Deserialize<InventorySnapshot>(envelope.SnapshotJson);
        Validate(snapshot, hostId);
        if (snapshot.Revision != envelope.Revision) throw new ArgumentException("Revision mismatch.");
        return snapshot;
    }
}
