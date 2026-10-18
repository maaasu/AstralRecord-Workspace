using AstralRecordControl.Contracts;
using Microsoft.Data.Sqlite;

namespace AstralRecordControl.Runner;

/// <summary>Single writer for Runner facts and durable process execution.</summary>
public sealed partial class RunnerStore : IDisposable
{
    private readonly SqliteConnection database;
    private readonly RunnerConfiguration configuration;
    private readonly object sync = new();
    private readonly string dataRoot;
    private readonly TimeProvider clock;
    private bool disposed;
    public Guid Epoch { get; } = Guid.NewGuid();
    public bool ProcessControlEnabled => configuration.EnableProcessControl;

    public RunnerStore(string dataRoot, RunnerConfiguration configuration, TimeProvider? clock = null)
    {
        this.dataRoot = dataRoot;
        this.clock = clock ?? TimeProvider.System;
        this.configuration = configuration;
        if (configuration.HostId == Guid.Empty) throw new ArgumentException("HostId is required.");
        if (configuration.EnableFileControl && !configuration.EnableProcessControl) throw new ArgumentException("File control requires process control.");
        LocalResourceBoundary.ValidateLocalPath(dataRoot);
        LocalResourceBoundary.ValidateResources(configuration.LocalResources);
        Directory.CreateDirectory(dataRoot);
        var path = Path.Combine(dataRoot, "runner.db");
        LocalResourceBoundary.ValidateLocalPath(path);
        var existed = File.Exists(path);
        database = new SqliteConnection(new SqliteConnectionStringBuilder { DataSource = path, Pooling = false }.ToString());
        try
        {
        database.Open();
        Execute("PRAGMA busy_timeout=5000; PRAGMA foreign_keys=ON; PRAGMA journal_mode=WAL;");
        var version = Convert.ToInt32(Scalar("PRAGMA user_version;"));
        if (version is < 0 or > 3) throw new RunnerFault(ErrorCode.ProtocolMismatch);
        if (version == 0)
        {
            if (Convert.ToInt64(Scalar("SELECT COUNT(*) FROM sqlite_master WHERE name NOT LIKE 'sqlite_%';")) != 0) throw new RunnerFault(ErrorCode.RecoveryRequired);
            if (existed)
            {
                using var backup = new SqliteConnection(new SqliteConnectionStringBuilder { DataSource = path + ".before-v1.bak" }.ToString());
                backup.Open(); database.BackupDatabase(backup);
            }
            using var transaction = database.BeginTransaction();
            Execute("CREATE TABLE State(id INTEGER PRIMARY KEY CHECK(id=1),hostId TEXT NOT NULL,epoch TEXT NOT NULL,bootUtc TEXT NOT NULL,revision INTEGER NOT NULL,hash TEXT NOT NULL,snapshot TEXT NOT NULL,nextFence INTEGER NOT NULL); CREATE TABLE Servers(id TEXT PRIMARY KEY,config TEXT NOT NULL,stateRevision INTEGER NOT NULL); CREATE TABLE Holds(resource TEXT PRIMARY KEY,reservation TEXT NOT NULL,workflow TEXT NOT NULL,fence INTEGER NOT NULL,mode TEXT NOT NULL,createdUtc TEXT NOT NULL); PRAGMA user_version=1;", transaction);
            Execute("CREATE TABLE ReleasedHolds(reservation TEXT PRIMARY KEY,workflow TEXT NOT NULL,fence INTEGER NOT NULL);", transaction);
            Execute("INSERT INTO State VALUES(1,$host,$epoch,$utc,0,'','',0);", transaction, ("$host", configuration.HostId.ToString()), ("$epoch", Epoch.ToString()), ("$utc", DateTimeOffset.UtcNow.ToString("O")));
            transaction.Commit();
        }
        if (version <= 1) MigrateV2(path, version == 1);
        if (version <= 2) MigrateV3(path, version == 2);
        if (!string.Equals((string?)Scalar("SELECT hostId FROM State WHERE id=1;"), configuration.HostId.ToString(), StringComparison.Ordinal)) throw new RunnerFault(ErrorCode.RecoveryRequired);
        Execute("UPDATE State SET epoch=$epoch,bootUtc=$utc WHERE id=1;", null, ("$epoch", Epoch.ToString()), ("$utc", DateTimeOffset.UtcNow.ToString("O")));
        ReconcileAfterRestart();
        }
        catch { database.Dispose(); throw; }
    }

    public RunnerSnapshot GetSnapshot()
    {
        lock (sync)
        {
            var servers = new List<ServerRuntimeSnapshot>();
            using (var command = Command("SELECT id,config,stateRevision,processState,currentSessionId FROM Servers ORDER BY id;"))
            using (var reader = command.ExecuteReader())
                while (reader.Read())
                {
                    var config = ControlJson.Deserialize<ServerConfig>(reader.GetString(1));
                    servers.Add(new ServerRuntimeSnapshot { ServerId = Guid.Parse(reader.GetString(0)), ConfigRevision = config.Revision, StateRevision = reader.GetInt64(2), ProcessState = Enum.Parse<ProcessState>(reader.GetString(3)), CurrentSessionId = reader.IsDBNull(4) ? null : Guid.Parse(reader.GetString(4)), Readiness = GameReadiness.Unknown });
                }
            return new RunnerSnapshot { RunnerEpoch = Epoch, AppliedInventoryRevision = Convert.ToInt64(Scalar("SELECT revision FROM State WHERE id=1;")), Servers = servers.ToArray(), Holds = ReadHolds() };
        }
    }

    public RunnerSnapshot ApplyInventory(InventoryEnvelope envelope)
    {
        lock (sync)
        {
            InventorySnapshot inventory;
            try { inventory = InventoryValidation.ReadEnvelope(envelope, configuration.HostId); }
            catch (Exception ex) when (ex is ArgumentException or System.Text.Json.JsonException) { throw new RunnerFault(ErrorCode.ProtocolMismatch); }
            LocalResourceBoundary.ValidateInventory(inventory, configuration.LocalResources);
            var revision = Convert.ToInt64(Scalar("SELECT revision FROM State WHERE id=1;"));
            var hash = (string)Scalar("SELECT hash FROM State WHERE id=1;")!;
            if (envelope.Revision < revision) throw new RunnerFault(ErrorCode.StaleRevision);
            if (envelope.Revision == revision && hash.Length != 0)
            {
                if (hash != envelope.Sha256) throw new RunnerFault(ErrorCode.OperationConflict);
                return GetSnapshot();
            }
            var holds = ReadHolds();
            if (holds.Length != 0 && BackupProfilesChanged((string?)Scalar("SELECT snapshot FROM State WHERE id=1;"), envelope.SnapshotJson)) throw new RunnerFault(ErrorCode.ReservationConflict);
            using var transaction = database.BeginTransaction();
            foreach (var server in inventory.Servers)
            {
                var nextJson = ControlJson.Serialize(server);
                var previousJson = (string?)Scalar("SELECT config FROM Servers WHERE id=$id;", transaction, ("$id", server.ServerId.ToString()));
                if (previousJson is not null)
                {
                    var previous = ControlJson.Deserialize<ServerConfig>(previousJson);
                    if (server.Revision < previous.Revision || (server.Revision == previous.Revision && nextJson != previousJson)) throw new RunnerFault(ErrorCode.StaleRevision);
                    if (nextJson != previousJson && (ConfigurationIsHeld(previous, holds) || ConfigurationIsHeld(server, holds))) throw new RunnerFault(ErrorCode.ReservationConflict);
                    if (nextJson != previousJson && (string?)Scalar("SELECT processState FROM Servers WHERE id=$id;", transaction, ("$id", server.ServerId.ToString())) != ProcessState.Stopped.ToString()) throw new RunnerFault(ErrorCode.StateConflict);
                }
                else if (ConfigurationIsHeld(server, holds)) throw new RunnerFault(ErrorCode.ReservationConflict);
                Execute("INSERT INTO Servers(id,config,stateRevision) VALUES($id,$config,0) ON CONFLICT(id) DO UPDATE SET stateRevision=Servers.stateRevision+CASE WHEN Servers.config<>excluded.config THEN 1 ELSE 0 END,config=excluded.config;", transaction, ("$id", server.ServerId.ToString()), ("$config", nextJson));
                if (configuration.EnableProcessControl && server.AutoStart && (previousJson is null || !ControlJson.Deserialize<ServerConfig>(previousJson).AutoStart))
                    Execute("UPDATE Servers SET desiredState='AutoStartPending',nextAutoStartUtc=$next WHERE id=$id AND processState='Stopped' AND recoveryRequired=0;", transaction,
                        ("$next", clock.GetUtcNow().AddSeconds(server.StartDelaySeconds).ToString("O")), ("$id", server.ServerId.ToString()));
            }
            // A definition can be removed only after its process and holds are settled.
            var keep = inventory.Servers.Select(s => s.ServerId.ToString()).ToHashSet(StringComparer.Ordinal);
            var existing = new List<string>();
            using (var command = Command("SELECT id FROM Servers;", transaction))
            using (var reader = command.ExecuteReader()) while (reader.Read()) existing.Add(reader.GetString(0));
            foreach (var id in existing.Where(id => !keep.Contains(id)))
            {
                var previous = ControlJson.Deserialize<ServerConfig>((string)Scalar("SELECT config FROM Servers WHERE id=$id;", transaction, ("$id", id))!);
                if (ConfigurationIsHeld(previous, holds)) throw new RunnerFault(ErrorCode.ReservationConflict);
                if ((string?)Scalar("SELECT processState FROM Servers WHERE id=$id;", transaction, ("$id", id)) != ProcessState.Stopped.ToString()) throw new RunnerFault(ErrorCode.StateConflict);
                Execute("DELETE FROM Servers WHERE id=$id;", transaction, ("$id", id));
            }
            Execute("UPDATE State SET revision=$revision,hash=$hash,snapshot=$snapshot WHERE id=1;", transaction, ("$revision", envelope.Revision), ("$hash", envelope.Sha256), ("$snapshot", envelope.SnapshotJson));
            transaction.Commit();
            return GetSnapshot();
        }
    }

    public ResourceHold AcquireHold(AcquireHoldRequest request)
    {
        lock (sync)
        {
            if (request.ReservationId == Guid.Empty || request.OwnerWorkflowId == Guid.Empty || request.ResourceKeys.Length is < 1 or > 64 || !Enum.IsDefined(request.Mode)) throw new RunnerFault(ErrorCode.ReservationConflict);
            if (request.ResourceKeys.Distinct(StringComparer.Ordinal).Count() != request.ResourceKeys.Length) throw new RunnerFault(ErrorCode.ReservationConflict);
            var keys = ExpandResourceKeys(request.ResourceKeys);
            if (Scalar("SELECT reservation FROM ReleasedHolds WHERE reservation=$id;", null, ("$id", request.ReservationId.ToString())) is not null) throw new RunnerFault(ErrorCode.ReservationConflict);
            var existing = ReadHolds().SingleOrDefault(h => h.ReservationId == request.ReservationId);
            if (existing is not null)
            {
                if (existing.OwnerWorkflowId == request.OwnerWorkflowId && existing.Mode == request.Mode && existing.ResourceKeys.SequenceEqual(keys)) return existing;
                throw new RunnerFault(ErrorCode.ReservationConflict);
            }
            using var transaction = database.BeginTransaction();
            foreach (var hold in ReadHolds(transaction))
                if (keys.Any(key => hold.ResourceKeys.Any(held => ResourcesConflict(key, held)))) throw new RunnerFault(ErrorCode.ReservationConflict);
            var fence = checked(Convert.ToInt64(Scalar("SELECT nextFence FROM State WHERE id=1;", transaction)) + 1);
            Execute("UPDATE State SET nextFence=$fence WHERE id=1;", transaction, ("$fence", fence));
            foreach (var key in keys)
            {
                Execute("INSERT INTO Holds VALUES($resource,$reservation,$workflow,$fence,$mode,$utc);", transaction, ("$resource", key), ("$reservation", request.ReservationId.ToString()), ("$workflow", request.OwnerWorkflowId.ToString()), ("$fence", fence), ("$mode", request.Mode.ToString()), ("$utc", DateTimeOffset.UtcNow.ToString("O")));
            }
            BumpState(keys, transaction);
            transaction.Commit();
            return new ResourceHold { ReservationId = request.ReservationId, OwnerWorkflowId = request.OwnerWorkflowId, ResourceKeys = keys, Fence = fence, Mode = request.Mode };
        }
    }

    public void ReleaseHold(ReleaseHoldRequest request)
    {
        lock (sync)
        {
            var existing = ReadHolds().SingleOrDefault(h => h.ReservationId == request.ReservationId);
            if (existing is null)
            {
                using var command = Command("SELECT workflow,fence FROM ReleasedHolds WHERE reservation=$id;", null, ("$id", request.ReservationId.ToString()));
                using var reader = command.ExecuteReader();
                if (reader.Read() && reader.GetString(0) == request.OwnerWorkflowId.ToString() && reader.GetInt64(1) == request.ExpectedFence) return;
                throw new RunnerFault(ErrorCode.ReservationConflict);
            }
            if (existing.OwnerWorkflowId != request.OwnerWorkflowId || existing.Fence != request.ExpectedFence) throw new RunnerFault(ErrorCode.ReservationConflict);
            if (Convert.ToInt64(Scalar("SELECT COUNT(*) FROM ExecutionLedger WHERE reservation=$reservation AND status IN ('Accepted','Running','NeedsAttention');", null, ("$reservation", request.ReservationId.ToString()))) != 0) throw new RunnerFault(ErrorCode.RecoveryRequired);
            if (Convert.ToInt64(Scalar("SELECT COUNT(*) FROM DataOperationLedger WHERE reservation=$reservation AND status IN ('Accepted','Running','NeedsAttention');", null, ("$reservation", request.ReservationId.ToString()))) != 0) throw new RunnerFault(ErrorCode.RecoveryRequired);
            using var transaction = database.BeginTransaction();
            Execute("INSERT INTO ReleasedHolds VALUES($reservation,$workflow,$fence);", transaction, ("$reservation", request.ReservationId.ToString()), ("$workflow", request.OwnerWorkflowId.ToString()), ("$fence", request.ExpectedFence));
            Execute("DELETE FROM Holds WHERE reservation=$reservation;", transaction, ("$reservation", request.ReservationId.ToString()));
            BumpState(existing.ResourceKeys, transaction);
            transaction.Commit();
        }
    }

    private string[] ExpandResourceKeys(string[] requested)
    {
        var keys = new HashSet<string>(StringComparer.Ordinal);
        foreach (var key in requested)
        {
            if (key is null) throw new RunnerFault(ErrorCode.ReservationConflict);
            var parts = key.Split(':', 4);
            if (parts.Length == 2 && parts[0] == "server" && Guid.TryParseExact(parts[1], "D", out var id) && id != Guid.Empty && id.ToString() == parts[1])
            {
                var json = (string?)Scalar("SELECT config FROM Servers WHERE id=$id;", null, ("$id", id.ToString()));
                if (json is null) throw new RunnerFault(ErrorCode.ReservationConflict);
                var server = ControlJson.Deserialize<ServerConfig>(json);
                keys.Add(key); keys.Add(DirectoryKey(server));
            }
            else if (parts.Length == 4 && parts[0] == "directory" && parts[1] == configuration.HostId.ToString()
                && Guid.TryParseExact(parts[2], "D", out var rootId) && rootId.ToString() == parts[2])
            {
                var root = configuration.LocalResources.Roots.SingleOrDefault(r => r.Id == rootId) ?? throw new RunnerFault(ErrorCode.PathOutsideRoot);
                LocalResourceBoundary.ResolveManagedRoot(root, parts[3]);
                keys.Add($"directory:{configuration.HostId}:{rootId}:{parts[3].ToLowerInvariant()}");
            }
            else throw new RunnerFault(ErrorCode.ReservationConflict);
        }
        return keys.Order(StringComparer.Ordinal).ToArray();
    }
    private static bool BackupProfilesChanged(string? previous, string next)
    {
        static string Profiles(string? json)
        {
            if (string.IsNullOrEmpty(json)) return "[]";
            using var doc = System.Text.Json.JsonDocument.Parse(json);
            return doc.RootElement.TryGetProperty("backupProfiles", out var value) ? value.GetRawText() : "[]";
        }
        return !string.Equals(Profiles(previous), Profiles(next), StringComparison.Ordinal);
    }
    private string DirectoryKey(ServerConfig server) => $"directory:{configuration.HostId}:{server.ManagedRootId}:{server.ServerDirectory.ToLowerInvariant()}";
    private bool ConfigurationIsHeld(ServerConfig server, ResourceHold[] holds) => holds.Any(hold => hold.ResourceKeys.Any(key => key == "server:" + server.ServerId || ResourcesConflict(DirectoryKey(server), key)));
    private string? DirectoryPath(string key)
    {
        var parts = key.Split(':', 4);
        if (parts.Length != 4 || parts[0] != "directory") return null;
        var root = configuration.LocalResources.Roots.Single(r => r.Id.ToString() == parts[2]);
        return LocalResourceBoundary.ResolveManagedRoot(root, parts[3]);
    }
    private bool ResourcesConflict(string left, string right)
    {
        if (left == right) return true;
        var leftPath = DirectoryPath(left); var rightPath = DirectoryPath(right);
        return leftPath is not null && rightPath is not null && PathsOverlap(leftPath, rightPath);
    }
    private static bool PathsOverlap(string left, string right) => string.Equals(left, right, StringComparison.OrdinalIgnoreCase)
        || left.StartsWith(right + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase)
        || right.StartsWith(left + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase);
    private void BumpState(string[] keys, SqliteTransaction transaction)
    {
        var affected = new List<string>();
        using (var command = Command("SELECT id,config FROM Servers;", transaction))
        using (var reader = command.ExecuteReader())
            while (reader.Read())
            {
                var server = ControlJson.Deserialize<ServerConfig>(reader.GetString(1));
                if (keys.Any(key => key == "server:" + server.ServerId || ResourcesConflict(DirectoryKey(server), key))) affected.Add(reader.GetString(0));
            }
        foreach (var id in affected) Execute("UPDATE Servers SET stateRevision=stateRevision+1 WHERE id=$id;", transaction, ("$id", id));
    }
    private ResourceHold[] ReadHolds(SqliteTransaction? transaction = null)
    {
        var rows = new List<(string Resource, Guid Reservation, Guid Workflow, long Fence, HoldMode Mode)>();
        using var command = Command("SELECT resource,reservation,workflow,fence,mode FROM Holds ORDER BY resource;", transaction);
        using var reader = command.ExecuteReader();
        while (reader.Read()) rows.Add((reader.GetString(0), Guid.Parse(reader.GetString(1)), Guid.Parse(reader.GetString(2)), reader.GetInt64(3), Enum.Parse<HoldMode>(reader.GetString(4))));
        return rows.GroupBy(r => r.Reservation).Select(g => new ResourceHold { ReservationId = g.Key, OwnerWorkflowId = g.First().Workflow, Fence = g.First().Fence, Mode = g.First().Mode, ResourceKeys = g.Select(r => r.Resource).ToArray() }).ToArray();
    }
    private SqliteCommand Command(string sql, SqliteTransaction? transaction = null, params (string, object)[] parameters)
    {
        var command = database.CreateCommand(); command.CommandText = sql; command.Transaction = transaction;
        foreach (var (key, value) in parameters) command.Parameters.AddWithValue(key, value);
        return command;
    }
    private void Execute(string sql, SqliteTransaction? transaction = null, params (string, object)[] parameters) { using var command = Command(sql, transaction, parameters); command.ExecuteNonQuery(); }
    private object? Scalar(string sql, SqliteTransaction? transaction = null, params (string, object)[] parameters) { using var command = Command(sql, transaction, parameters); return command.ExecuteScalar(); }
    public void Dispose()
    {
        lock (sync)
        {
            disposed = true;
            database.Dispose();
        }
    }
}
