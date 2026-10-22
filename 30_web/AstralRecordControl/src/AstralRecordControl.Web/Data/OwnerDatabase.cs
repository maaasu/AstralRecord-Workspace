using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using AstralRecordControl.Web.Authentication;

namespace AstralRecordControl.Web.Data;

public static class ControlDatabase
{
    public const int SchemaVersion = 5;
    public static async Task InitializeAsync(ControlDbContext db, CancellationToken cancellationToken = default)
    {
        await db.Database.OpenConnectionAsync(cancellationToken);
        try
        {
            var connection = db.Database.GetDbConnection();
            await using var command = connection.CreateCommand();
            command.CommandText = "PRAGMA busy_timeout=5000; PRAGMA journal_mode=WAL; PRAGMA foreign_keys=ON;";
            await command.ExecuteNonQueryAsync(cancellationToken);
            command.CommandText = "PRAGMA user_version;";
            var version = Convert.ToInt32(await command.ExecuteScalarAsync(cancellationToken));
            if (version == SchemaVersion) return;
            if (version == 1)
            {
                if (connection is not SqliteConnection source) throw new InvalidOperationException("control.db requires SQLite.");
                var sourcePath = source.DataSource;
                await using (var backup = new SqliteConnection(new SqliteConnectionStringBuilder { DataSource = sourcePath + ".before-v2.bak", Pooling = false }.ToString()))
                {
                    await backup.OpenAsync(cancellationToken);
                    source.BackupDatabase(backup);
                }
                await using var migration = await db.Database.BeginTransactionAsync(cancellationToken);
                await db.Database.ExecuteSqlRawAsync("""
                    CREATE TABLE Workflows (WorkflowId TEXT NOT NULL PRIMARY KEY, Kind TEXT NOT NULL, Status TEXT NOT NULL, RequestJson TEXT NOT NULL, ResourceKeysJson TEXT NOT NULL, Revision INTEGER NOT NULL, RecoveryRequired INTEGER NOT NULL, CreatedAtUtc TEXT NOT NULL, UpdatedAtUtc TEXT NOT NULL);
                    CREATE TABLE Reservations (ResourceKey TEXT NOT NULL PRIMARY KEY, ReservationId TEXT NOT NULL, OwnerWorkflowId TEXT NOT NULL, Mode TEXT NOT NULL, Fence INTEGER NOT NULL, State TEXT NOT NULL, HostId TEXT NULL, CreatedAtUtc TEXT NOT NULL, ReleasedAtUtc TEXT NULL);
                    CREATE INDEX IX_Reservations_ReservationId ON Reservations(ReservationId);
                    CREATE INDEX IX_Reservations_ReservationId_OwnerWorkflowId ON Reservations(ReservationId, OwnerWorkflowId);
                    CREATE TABLE ReservationLeases (ReservationId TEXT NOT NULL PRIMARY KEY, WorkflowId TEXT NOT NULL, ResourceKeysJson TEXT NOT NULL, FencesJson TEXT NOT NULL, PreparedOnce INTEGER NOT NULL DEFAULT 0, LastStepKey TEXT NULL, ReleaseOutcome INTEGER NULL, Status TEXT NOT NULL, AdapterId TEXT NULL, RunReference TEXT NULL, CreatedAtUtc TEXT NOT NULL, ReleasedAtUtc TEXT NULL);
                    CREATE UNIQUE INDEX IX_ReservationLeases_WorkflowId ON ReservationLeases(WorkflowId);
                    CREATE TABLE Operations (OperationId TEXT NOT NULL PRIMARY KEY, IdempotencyKey TEXT NOT NULL, HostId TEXT NOT NULL, ServerId TEXT NULL, WorkflowId TEXT NOT NULL, ReservationId TEXT NOT NULL, Kind TEXT NOT NULL, RequestSha256 TEXT NOT NULL, CommandJson TEXT NOT NULL, CommandSha256 TEXT NOT NULL, Status TEXT NOT NULL, StatusVersion INTEGER NOT NULL, EventSeq INTEGER NOT NULL, CreatedAtUtc TEXT NOT NULL, AcceptedAtUtc TEXT NULL, StartedAtUtc TEXT NULL, FinishedAtUtc TEXT NULL, ResultJson TEXT NULL, ErrorCode TEXT NULL, FOREIGN KEY(HostId) REFERENCES Hosts(HostId) ON DELETE RESTRICT, FOREIGN KEY(ServerId) REFERENCES Servers(ServerId) ON DELETE RESTRICT, FOREIGN KEY(WorkflowId) REFERENCES Workflows(WorkflowId) ON DELETE RESTRICT);
                    CREATE UNIQUE INDEX IX_Operations_IdempotencyKey ON Operations(IdempotencyKey);
                    CREATE INDEX IX_Operations_HostId ON Operations(HostId);
                    CREATE INDEX IX_Operations_ServerId ON Operations(ServerId);
                    CREATE INDEX IX_Operations_WorkflowId ON Operations(WorkflowId);
                    PRAGMA user_version=2;
                    """, cancellationToken);
                await db.Database.ExecuteSqlRawAsync(CliAuthentication.SchemaSql, cancellationToken);
                await migration.CommitAsync(cancellationToken);
                version = 2;
            }
            if (version == 2)
            {
                if (connection is not SqliteConnection source) throw new InvalidOperationException("control.db requires SQLite.");
                await using (var backup = new SqliteConnection(new SqliteConnectionStringBuilder { DataSource = source.DataSource + ".before-v3.bak", Pooling = false }.ToString()))
                {
                    await backup.OpenAsync(cancellationToken);
                    source.BackupDatabase(backup);
                }
                await using var migration = await db.Database.BeginTransactionAsync(cancellationToken);
                await db.Database.ExecuteSqlRawAsync("""
                    CREATE TABLE Transfers (TransferId TEXT NOT NULL PRIMARY KEY, IdempotencyKey TEXT NOT NULL, RequestSha256 TEXT NOT NULL, SourceHostId TEXT NULL, DestinationHostId TEXT NULL, OperationId TEXT NOT NULL, ServerId TEXT NOT NULL, ConsumingOperationId TEXT NULL, Status TEXT NOT NULL, SizeBytes INTEGER NULL, Sha256 TEXT NULL, CommittedOffset INTEGER NOT NULL, CreatedAtUtc TEXT NOT NULL, ExpiresAtUtc TEXT NOT NULL, VerifiedAtUtc TEXT NULL, ConsumedAtUtc TEXT NULL, FOREIGN KEY(ServerId) REFERENCES Servers(ServerId) ON DELETE RESTRICT);
                    CREATE UNIQUE INDEX IX_Transfers_IdempotencyKey ON Transfers(IdempotencyKey);
                    CREATE INDEX IX_Transfers_ExpiresAtUtc ON Transfers(ExpiresAtUtc);
                    CREATE TABLE TransferChunks (TransferId TEXT NOT NULL, Offset INTEGER NOT NULL, SizeBytes INTEGER NOT NULL, Sha256 TEXT NOT NULL, PRIMARY KEY(TransferId, Offset), FOREIGN KEY(TransferId) REFERENCES Transfers(TransferId) ON DELETE CASCADE);
                    PRAGMA user_version=3;
                    """, cancellationToken);
                await migration.CommitAsync(cancellationToken);
                version = 3;
            }
            if (version == 3)
            {
                if (connection is not SqliteConnection source) throw new InvalidOperationException("control.db requires SQLite.");
                await using (var backup = new SqliteConnection(new SqliteConnectionStringBuilder { DataSource = source.DataSource + ".before-v4." + Guid.NewGuid().ToString("N") + ".bak", Pooling = false }.ToString()))
                {
                    await backup.OpenAsync(cancellationToken);
                    source.BackupDatabase(backup);
                }
                await using var migration = await db.Database.BeginTransactionAsync(cancellationToken);
                await db.Database.ExecuteSqlRawAsync(ScheduleSchema.Sql, cancellationToken);
                await db.Database.ExecuteSqlRawAsync(MetricsSchema.Sql, cancellationToken);
                await db.Database.ExecuteSqlRawAsync(NotificationSchema.Sql, cancellationToken);
                await db.Database.ExecuteSqlRawAsync("PRAGMA user_version=4;", cancellationToken);
                await migration.CommitAsync(cancellationToken);
                version = 4;
            }
            if (version == 4)
            {
                if (connection is not SqliteConnection source) throw new InvalidOperationException("control.db requires SQLite.");
                await using (var backup = new SqliteConnection(new SqliteConnectionStringBuilder { DataSource = source.DataSource + ".before-v5." + Guid.NewGuid().ToString("N") + ".bak", Pooling = false }.ToString()))
                {
                    await backup.OpenAsync(cancellationToken);
                    source.BackupDatabase(backup);
                }
                await using var migration = await db.Database.BeginTransactionAsync(cancellationToken);
                await db.Database.ExecuteSqlRawAsync("ALTER TABLE Workflows ADD COLUMN HiddenAtUtc TEXT NULL; PRAGMA user_version=5;", cancellationToken);
                await migration.CommitAsync(cancellationToken);
                return;
            }
            if (version != 0) throw new InvalidOperationException($"Unsupported control.db schema: {version}");
            command.CommandText = "SELECT COUNT(*) FROM sqlite_master WHERE name NOT LIKE 'sqlite_%';";
            if (Convert.ToInt32(await command.ExecuteScalarAsync(cancellationToken)) != 0)
                throw new InvalidOperationException("Unversioned existing control.db is not supported.");
            await using var transaction = await db.Database.BeginTransactionAsync(cancellationToken);
            await db.Database.ExecuteSqlRawAsync(db.Database.GenerateCreateScript(), cancellationToken);
            await db.Database.ExecuteSqlRawAsync($"PRAGMA user_version={SchemaVersion};", cancellationToken);
            await transaction.CommitAsync(cancellationToken);
        }
        finally { await db.Database.CloseConnectionAsync(); }
    }
}

public sealed class ControlStorage
{
    public string DataRoot { get; }
    public string DatabasePath => Path.Combine(DataRoot, "control.db");
    public ControlStorage(string dataRoot)
    {
        if (!Path.IsPathFullyQualified(dataRoot) || dataRoot.StartsWith(@"\\", StringComparison.Ordinal))
            throw new InvalidOperationException("Control:DataRoot must be an absolute local path.");
        DataRoot = Path.GetFullPath(dataRoot);
        if (DataRoot.StartsWith(@"\\", StringComparison.Ordinal)) throw new InvalidOperationException("Control:DataRoot must be an absolute local path.");
        Directory.CreateDirectory(DataRoot);
    }
    public string ConnectionString => new SqliteConnectionStringBuilder
    {
        DataSource = DatabasePath, DefaultTimeout = 5, ForeignKeys = true
    }.ToString();
    // Both the Web process and offline owner CLI must hold this exclusive lease.
    public FileStream AcquireExclusiveLease() => new(Path.Combine(DataRoot, "web.lock"), FileMode.OpenOrCreate, FileAccess.ReadWrite, FileShare.None);
}
