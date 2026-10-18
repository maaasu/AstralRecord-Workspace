using AstralRecordControl.Contracts;
using Microsoft.Data.Sqlite;

namespace AstralRecordControl.Agent;

public sealed record AgentState(Guid StoreId, long MaxEventSequence, long ControllerAckSequence, long AppliedInventoryRevision, string InventorySha256);

public sealed partial class AgentStore : IDisposable
{
    public string DataRoot { get; }
    private readonly SqliteConnection database;
    private readonly Guid hostId;
    private readonly object sync = new();
    public AgentStore(string dataRoot, Guid hostId)
    {
        DataRoot = dataRoot;
        if (hostId == Guid.Empty) throw new ArgumentException("HostId is required.");
        this.hostId = hostId;
        AgentResourceBoundary.ValidateLocalPath(dataRoot);
        Directory.CreateDirectory(dataRoot);
        var path = Path.Combine(dataRoot, "agent.db");
        AgentResourceBoundary.ValidateLocalPath(path);
        var existed = File.Exists(path);
        database = new SqliteConnection(new SqliteConnectionStringBuilder { DataSource = path, Pooling = false }.ToString());
        try
        {
        database.Open();
        Execute("PRAGMA busy_timeout=5000; PRAGMA foreign_keys=ON; PRAGMA journal_mode=WAL;");
        var version = Convert.ToInt32(Scalar("PRAGMA user_version;"));
        if (version is < 0 or > 4) throw new AgentFault(ErrorCode.ProtocolMismatch);
        if (version == 0)
        {
            if (Convert.ToInt64(Scalar("SELECT COUNT(*) FROM sqlite_master WHERE name NOT LIKE 'sqlite_%';")) != 0) throw new AgentFault(ErrorCode.RecoveryRequired);
            if (existed)
            {
                using var backup = new SqliteConnection(new SqliteConnectionStringBuilder { DataSource = path + ".before-v1.bak" }.ToString());
                backup.Open(); database.BackupDatabase(backup);
            }
            using var transaction = database.BeginTransaction();
            Execute("CREATE TABLE State(id INTEGER PRIMARY KEY CHECK(id=1),hostId TEXT NOT NULL,storeId TEXT NOT NULL,maxSeq INTEGER NOT NULL,ackSeq INTEGER NOT NULL,revision INTEGER NOT NULL,hash TEXT NOT NULL,snapshot TEXT NOT NULL); CREATE TABLE PendingInventory(id INTEGER PRIMARY KEY CHECK(id=1),envelope TEXT NOT NULL); CREATE TABLE EventOutbox(seq INTEGER PRIMARY KEY,eventJson TEXT NOT NULL); PRAGMA user_version=1;", transaction);
            Execute("INSERT INTO State VALUES(1,$host,$store,0,0,0,'','');", transaction, ("$host", hostId.ToString()), ("$store", Guid.NewGuid().ToString()));
            transaction.Commit();
            version = 1;
        }
        if (version == 1)
        {
            using (var backup = new SqliteConnection(new SqliteConnectionStringBuilder { DataSource = path + ".before-v2.bak", Pooling = false }.ToString()))
            {
                backup.Open(); database.BackupDatabase(backup);
            }
            using var transaction = database.BeginTransaction();
            Execute("CREATE TABLE OperationInbox(operationId TEXT PRIMARY KEY,commandJson TEXT NOT NULL,commandSha256 TEXT NOT NULL,status TEXT NOT NULL,statusVersion INTEGER NOT NULL,dispatchAttempted INTEGER NOT NULL DEFAULT 0,resultJson TEXT,errorCode TEXT,updatedAtUtc TEXT NOT NULL); PRAGMA user_version=2;", transaction);
            transaction.Commit();
        }
        if (version <= 2)
        {
            if (version == 2)
            {
                var backupPath = path + ".before-v3." + DateTimeOffset.UtcNow.ToString("yyyyMMddHHmmssfffffff") + "." + Guid.NewGuid().ToString("N") + ".bak";
                using var backup = new SqliteConnection(new SqliteConnectionStringBuilder { DataSource = backupPath, Pooling = false }.ToString());
                backup.Open(); database.BackupDatabase(backup);
            }
            using var transaction = database.BeginTransaction();
            Execute("ALTER TABLE OperationInbox ADD COLUMN phase TEXT NOT NULL DEFAULT 'Accepted';", transaction);
            Execute("ALTER TABLE OperationInbox ADD COLUMN serverSessionId TEXT;", transaction);
            Execute("CREATE TABLE PendingResults(operationId TEXT PRIMARY KEY,resultJson TEXT NOT NULL,resultSha256 TEXT NOT NULL,restartRequired INTEGER NOT NULL DEFAULT 0);", transaction);
            Execute("PRAGMA user_version=3;", transaction);
            transaction.Commit();
            version = 3;
        }
        if (version == 3)
        {
            var backupPath = path + ".before-v4." + Guid.NewGuid().ToString("N") + ".bak";
            using (var backup = new SqliteConnection(new SqliteConnectionStringBuilder { DataSource = backupPath, Pooling = false }.ToString()))
            {
                backup.Open(); database.BackupDatabase(backup);
            }
            using var transaction = database.BeginTransaction();
            InitializeScheduleSchema(transaction);
            Execute("CREATE TABLE RuntimeObservations(serverId TEXT PRIMARY KEY,snapshotJson TEXT NOT NULL);", transaction);
            Execute("PRAGMA user_version=4;", transaction);
            transaction.Commit();
        }
        if ((string?)Scalar("SELECT hostId FROM State WHERE id=1;") != hostId.ToString()) throw new AgentFault(ErrorCode.RecoveryRequired);
        GetState();
        }
        catch { database.Dispose(); throw; }
    }

    public AgentState GetState()
    {
        lock (sync)
        {
            using var command = Command("SELECT storeId,maxSeq,ackSeq,revision,hash FROM State WHERE id=1;");
            using var reader = command.ExecuteReader();
            if (!reader.Read() || !Guid.TryParse(reader.GetString(0), out var id) || id == Guid.Empty) throw new AgentFault(ErrorCode.RecoveryRequired);
            var state = new AgentState(id, reader.GetInt64(1), reader.GetInt64(2), reader.GetInt64(3), reader.GetString(4));
            if (state.MaxEventSequence < 0 || state.ControllerAckSequence < 0 || state.ControllerAckSequence > state.MaxEventSequence || state.AppliedInventoryRevision < 0) throw new AgentFault(ErrorCode.RecoveryRequired);
            return state;
        }
    }

    public void Acknowledge(long sequence)
    {
        lock (sync)
        {
            var state = GetState();
            if (sequence < state.ControllerAckSequence || sequence > state.MaxEventSequence) throw new AgentFault(ErrorCode.RecoveryRequired);
            using var transaction = database.BeginTransaction();
            Execute("UPDATE State SET ackSeq=$seq WHERE id=1;", transaction, ("$seq", sequence));
            Execute("DELETE FROM EventOutbox WHERE seq<=$seq;", transaction, ("$seq", sequence));
            transaction.Commit();
        }
    }

    public AgentEvent AppendEvent(string type, System.Text.Json.JsonElement body)
    {
        lock (sync)
        {
            using var transaction = database.BeginTransaction();
            var sequence = checked(Convert.ToInt64(Scalar("SELECT maxSeq FROM State WHERE id=1;", transaction)) + 1);
            var item = new AgentEvent(sequence, null, null, type, DateTimeOffset.UtcNow, body);
            Execute("INSERT INTO EventOutbox VALUES($seq,$json);", transaction, ("$seq", sequence), ("$json", ControlJson.Serialize(item)));
            Execute("UPDATE State SET maxSeq=$seq WHERE id=1;", transaction, ("$seq", sequence));
            transaction.Commit();
            return item;
        }
    }

    public AgentEvent[] ReadEvents(int limit = 100)
    {
        lock (sync)
        {
            using var command = Command("SELECT eventJson FROM EventOutbox ORDER BY seq LIMIT $limit;", null, ("$limit", Math.Clamp(limit, 1, 100)));
            using var reader = command.ExecuteReader();
            var events = new List<AgentEvent>();
            while (reader.Read()) events.Add(ControlJson.Deserialize<AgentEvent>(reader.GetString(0)));
            return events.ToArray();
        }
    }

    public void PrepareInventory(InventoryEnvelope envelope)
    {
        lock (sync)
        {
            InventoryValidation.ReadEnvelope(envelope, hostId);
            var state = GetState();
            if (envelope.Revision < state.AppliedInventoryRevision) throw new AgentFault(ErrorCode.StaleRevision);
            if (envelope.Revision == state.AppliedInventoryRevision && state.InventorySha256.Length != 0 && envelope.Sha256 != state.InventorySha256) throw new AgentFault(ErrorCode.OperationConflict);
            Execute("INSERT INTO PendingInventory VALUES(1,$json) ON CONFLICT(id) DO UPDATE SET envelope=excluded.envelope;", null, ("$json", ControlJson.Serialize(envelope)));
        }
    }
    public InventoryEnvelope? GetPendingInventory()
    {
        lock (sync)
        {
            var json = (string?)Scalar("SELECT envelope FROM PendingInventory WHERE id=1;");
            return json is null ? null : ControlJson.Deserialize<InventoryEnvelope>(json);
        }
    }
    public void CommitInventory(InventoryEnvelope envelope)
    {
        lock (sync)
        {
            var pending = GetPendingInventory();
            if (pending is null || pending.Revision != envelope.Revision || pending.Sha256 != envelope.Sha256) throw new AgentFault(ErrorCode.RecoveryRequired);
            using var transaction = database.BeginTransaction();
            Execute("UPDATE State SET revision=$revision,hash=$hash,snapshot=$snapshot WHERE id=1;", transaction, ("$revision", envelope.Revision), ("$hash", envelope.Sha256), ("$snapshot", envelope.SnapshotJson));
            Execute("DELETE FROM PendingInventory WHERE id=1;", transaction);
            transaction.Commit();
        }
    }
    public InventorySnapshot GetAppliedInventory()
    {
        lock (sync)
        {
            var snapshot = (string?)Scalar("SELECT snapshot FROM State WHERE id=1;");
            if (string.IsNullOrEmpty(snapshot)) throw new AgentFault(ErrorCode.StaleRevision);
            return InventoryValidation.ReadEnvelope(new InventoryEnvelope(ControlProtocol.Version, hostId, GetState().AppliedInventoryRevision, snapshot, ControlProtocol.Hash(snapshot)), hostId);
        }
    }
    private SqliteCommand Command(string sql, SqliteTransaction? transaction = null, params (string, object)[] parameters)
    {
        var command = database.CreateCommand(); command.CommandText = sql; command.Transaction = transaction;
        foreach (var (key, value) in parameters) command.Parameters.AddWithValue(key, value);
        return command;
    }
    private void Execute(string sql, SqliteTransaction? transaction = null, params (string, object)[] parameters) { using var command = Command(sql, transaction, parameters); command.ExecuteNonQuery(); }
    private object? Scalar(string sql, SqliteTransaction? transaction = null, params (string, object)[] parameters) { using var command = Command(sql, transaction, parameters); return command.ExecuteScalar(); }
    public void Dispose() => database.Dispose();
}
