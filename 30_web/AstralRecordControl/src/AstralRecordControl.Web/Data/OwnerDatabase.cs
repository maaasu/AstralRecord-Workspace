using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordControl.Web.Data;

public static class ControlDatabase
{
    public const int SchemaVersion = 1;
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
            if (version != 0) throw new InvalidOperationException($"Unsupported control.db schema: {version}");
            command.CommandText = "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%';";
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
        Directory.CreateDirectory(DataRoot);
    }
    public string ConnectionString => new SqliteConnectionStringBuilder
    {
        DataSource = DatabasePath, DefaultTimeout = 5, ForeignKeys = true
    }.ToString();
    // Both the Web process and offline owner CLI must hold this exclusive lease.
    public FileStream AcquireExclusiveLease() => new(Path.Combine(DataRoot, "web.lock"), FileMode.OpenOrCreate, FileAccess.ReadWrite, FileShare.None);
}
