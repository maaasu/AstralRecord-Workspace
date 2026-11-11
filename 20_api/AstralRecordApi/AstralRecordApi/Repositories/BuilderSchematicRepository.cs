using System.Security.Cryptography;
using System.Text;
using AstralRecordApi.Models;
using Microsoft.AspNetCore.WebUtilities;
using Microsoft.Data.Sqlite;

namespace AstralRecordApi.Repositories;

public sealed class BuilderSchematicRepository(
    IConfiguration configuration, IWebHostEnvironment environment, TimeProvider timeProvider) : IBuilderSchematicRepository
{
    private static readonly TimeSpan TicketLifetime = TimeSpan.FromMinutes(5);
    private static readonly TimeSpan RejectedRetention = TimeSpan.FromHours(24);
    private const int MaxTickets = 256;
    private const int MaxPending = 128;
    private const int MaxPendingPerOwner = 4;
    private const int MaxStored = 1024;
    private const int MaxStoredPerOwner = 32;
    private const int MaxReceipts = 2048;
    private const int MaxReceiptsPerOwner = 64;

    private readonly string? _databasePath = GetDatabasePath(configuration["BuilderSchematics:RootPath"], environment);
    private readonly object _initializationLock = new();
    private volatile bool _initialized;

    public bool IsEnabled => _databasePath is not null;

    public string? IssueTicket(Guid userUuid)
    {
        using var connection = OpenDatabase();
        using var transaction = connection.BeginTransaction(deferred: false);
        var now = timeProvider.GetUtcNow();

        using var prune = Command(connection, transaction,
            "DELETE FROM tickets WHERE expires_at <= $now", ("$now", now.ToUnixTimeMilliseconds()));
        prune.ExecuteNonQuery();

        using var quota = Command(connection, transaction,
            "SELECT COUNT(*) FROM tickets WHERE user_uuid <> $owner", ("$owner", userUuid));
        if ((long)quota.ExecuteScalar()! >= MaxTickets)
            return null;

        var token = WebEncoders.Base64UrlEncode(RandomNumberGenerator.GetBytes(32));
        using var save = Command(connection, transaction, """
            INSERT INTO tickets (user_uuid, token_hash, expires_at)
            VALUES ($owner, $hash, $expires)
            ON CONFLICT (user_uuid) DO UPDATE SET token_hash = $hash, expires_at = $expires
            """, ("$owner", userUuid), ("$hash", HashToken(token)),
            ("$expires", now.Add(TicketLifetime).ToUnixTimeMilliseconds()));
        save.ExecuteNonQuery();
        transaction.Commit();
        return token;
    }

    public (BuilderSchematicEnqueueResult Result, Guid Id) Enqueue(Guid userUuid, string token, string name, byte[] content)
    {
        using var connection = OpenDatabase();
        using var transaction = connection.BeginTransaction(deferred: false);
        var now = timeProvider.GetUtcNow();

        using var lookup = Command(connection, transaction, """
            SELECT token_hash FROM tickets WHERE user_uuid = $owner AND expires_at > $now
            """, ("$owner", userUuid), ("$now", now.ToUnixTimeMilliseconds()));
        if (lookup.ExecuteScalar() is not byte[] hash
            || !CryptographicOperations.FixedTimeEquals(hash, HashToken(token)))
            return (BuilderSchematicEnqueueResult.InvalidTicket, Guid.Empty);

        using var prune = Command(connection, transaction, """
            DELETE FROM uploads WHERE status = $rejected AND updated_at <= $cutoff
            """, ("$rejected", BuilderSchematicStatus.Rejected),
            ("$cutoff", now.Subtract(RejectedRetention).ToUnixTimeMilliseconds()));
        prune.ExecuteNonQuery();

        if (UploadQuotaReached(connection, transaction, userUuid))
            return (BuilderSchematicEnqueueResult.QuotaExceeded, Guid.Empty);

        var id = Guid.NewGuid();
        using var enqueue = Command(connection, transaction, """
            INSERT INTO uploads (id, user_uuid, name, status, content, created_at, updated_at)
            VALUES ($id, $owner, $name, $pending, $content, $now, $now)
            """, ("$id", id), ("$owner", userUuid), ("$name", name), ("$pending", BuilderSchematicStatus.Pending),
            ("$content", content), ("$now", now.ToUnixTimeMilliseconds()));
        enqueue.ExecuteNonQuery();

        using var consume = Command(connection, transaction,
            "DELETE FROM tickets WHERE user_uuid = $owner", ("$owner", userUuid));
        consume.ExecuteNonQuery();

        transaction.Commit();
        return (BuilderSchematicEnqueueResult.Accepted, id);
    }

    public BuilderSchematicResponse? GetUpload(Guid id, Guid userUuid)
    {
        using var connection = OpenDatabase();
        using var command = Command(connection, null, """
            SELECT id, name, status, error FROM uploads
            WHERE id = $id AND user_uuid = $owner AND (status <> $rejected OR updated_at > $cutoff)
            """, ("$id", id), ("$owner", userUuid), ("$rejected", BuilderSchematicStatus.Rejected),
            ("$cutoff", timeProvider.GetUtcNow().Subtract(RejectedRetention).ToUnixTimeMilliseconds()));
        using var rows = command.ExecuteReader();
        return rows.Read() ? ReadResponse(rows) : null;
    }

    public IReadOnlyList<BuilderSchematicResponse> GetPending() =>
        GetList(BuilderSchematicStatus.Pending, null);

    public IReadOnlyList<BuilderSchematicResponse> GetRegistered(Guid userUuid) =>
        GetList(BuilderSchematicStatus.Registered, userUuid);

    public byte[]? GetFile(Guid id, Guid? userUuid)
    {
        var status = userUuid is null ? BuilderSchematicStatus.Pending : BuilderSchematicStatus.Registered;
        using var connection = OpenDatabase();
        using var command = Command(connection, null, """
            SELECT content FROM uploads WHERE id = $id AND status = $status AND ($owner IS NULL OR user_uuid = $owner)
            """, ("$id", id), ("$status", status), ("$owner", userUuid));
        return command.ExecuteScalar() as byte[];
    }

    public BuilderSchematicDecisionResult Decide(Guid id, bool accepted, string? error)
    {
        var status = accepted ? BuilderSchematicStatus.Registered : BuilderSchematicStatus.Rejected;
        using var connection = OpenDatabase();
        using var transaction = connection.BeginTransaction(deferred: false);

        using var lookup = Command(connection, transaction,
            "SELECT status FROM uploads WHERE id = $id", ("$id", id));
        var currentStatus = lookup.ExecuteScalar() as string;
        if (currentStatus is null)
            return BuilderSchematicDecisionResult.Missing;

        if (currentStatus != BuilderSchematicStatus.Pending)
            return currentStatus == status ? BuilderSchematicDecisionResult.Applied : BuilderSchematicDecisionResult.Conflict;

        using var update = Command(connection, transaction, """
            UPDATE uploads
            SET status = $status, error = $error, updated_at = $now,
                content = CASE WHEN $accepted THEN content ELSE NULL END
            WHERE id = $id
            """, ("$status", status), ("$error", error), ("$now", timeProvider.GetUtcNow().ToUnixTimeMilliseconds()),
            ("$accepted", accepted), ("$id", id));
        update.ExecuteNonQuery();
        transaction.Commit();
        return BuilderSchematicDecisionResult.Applied;
    }

    private IReadOnlyList<BuilderSchematicResponse> GetList(string status, Guid? userUuid)
    {
        using var connection = OpenDatabase();
        using var command = Command(connection, null, """
            SELECT id, name, status, error FROM uploads
            WHERE status = $status AND ($owner IS NULL OR user_uuid = $owner)
            ORDER BY created_at, id
            """, ("$status", status), ("$owner", userUuid));
        using var rows = command.ExecuteReader();
        var results = new List<BuilderSchematicResponse>();
        while (rows.Read())
            results.Add(ReadResponse(rows));
        return results;
    }

    private static bool UploadQuotaReached(SqliteConnection connection, SqliteTransaction transaction, Guid userUuid)
    {
        using var command = Command(connection, transaction, """
            SELECT COUNT(*), COUNT(*) FILTER (WHERE user_uuid = $owner),
                COUNT(*) FILTER (WHERE status <> $rejected),
                COUNT(*) FILTER (WHERE status <> $rejected AND user_uuid = $owner),
                COUNT(*) FILTER (WHERE status = $pending),
                COUNT(*) FILTER (WHERE status = $pending AND user_uuid = $owner)
            FROM uploads
            """, ("$owner", userUuid), ("$pending", BuilderSchematicStatus.Pending),
            ("$rejected", BuilderSchematicStatus.Rejected));
        using var counts = command.ExecuteReader();
        counts.Read();
        return counts.GetInt32(0) >= MaxReceipts || counts.GetInt32(1) >= MaxReceiptsPerOwner
            || counts.GetInt32(2) >= MaxStored || counts.GetInt32(3) >= MaxStoredPerOwner
            || counts.GetInt32(4) >= MaxPending || counts.GetInt32(5) >= MaxPendingPerOwner;
    }

    private SqliteConnection OpenDatabase()
    {
        var path = _databasePath ?? throw new InvalidOperationException("Schematic storage is disabled.");
        if (!_initialized)
        {
            lock (_initializationLock)
            {
                if (!_initialized)
                {
                    Directory.CreateDirectory(Path.GetDirectoryName(path)!);
                    using var connection = OpenConnection(path, SqliteOpenMode.ReadWriteCreate);
                    using var transaction = connection.BeginTransaction(deferred: false);
                    using var create = Command(connection, transaction, """
                        CREATE TABLE IF NOT EXISTS tickets (
                            user_uuid TEXT PRIMARY KEY NOT NULL,
                            token_hash BLOB NOT NULL,
                            expires_at INTEGER NOT NULL
                        );
                        CREATE TABLE IF NOT EXISTS uploads (
                            id TEXT PRIMARY KEY NOT NULL,
                            user_uuid TEXT NOT NULL,
                            name TEXT NOT NULL,
                            status TEXT NOT NULL CHECK (status IN ('pending', 'registered', 'rejected')),
                            content BLOB,
                            error TEXT,
                            created_at INTEGER NOT NULL,
                            updated_at INTEGER NOT NULL,
                            CHECK (
                                (status = 'rejected' AND content IS NULL AND error IS NOT NULL)
                                OR (status <> 'rejected' AND content IS NOT NULL AND error IS NULL)
                            )
                        );
                        CREATE INDEX IF NOT EXISTS uploads_status_owner
                            ON uploads (status, user_uuid, created_at, id);
                        """);
                    create.ExecuteNonQuery();
                    transaction.Commit();
                    _initialized = true;
                }
            }
        }
        return OpenConnection(path, SqliteOpenMode.ReadWrite);
    }

    private static SqliteConnection OpenConnection(string path, SqliteOpenMode mode)
    {
        var connectionString = new SqliteConnectionStringBuilder
        {
            DataSource = path,
            Mode = mode,
            Pooling = false,
            DefaultTimeout = 5
        };
        var connection = new SqliteConnection(connectionString.ToString());
        try
        {
            connection.Open();
            return connection;
        }
        catch
        {
            connection.Dispose();
            throw;
        }
    }

    private static SqliteCommand Command(SqliteConnection connection, SqliteTransaction? transaction, string sql,
        params (string Name, object? Value)[] parameters)
    {
        var command = connection.CreateCommand();
        command.Transaction = transaction;
        command.CommandText = sql;
        foreach (var (name, value) in parameters)
            command.Parameters.AddWithValue(name, value ?? DBNull.Value);
        return command;
    }

    private static byte[] HashToken(string token) => SHA256.HashData(Encoding.UTF8.GetBytes(token));

    private static BuilderSchematicResponse ReadResponse(SqliteDataReader rows) =>
        new(rows.GetGuid(0), rows.GetString(1), rows.GetString(2), rows.IsDBNull(3) ? null : rows.GetString(3));

    private static string? GetDatabasePath(string? configuredRoot, IWebHostEnvironment environment)
    {
        if (string.IsNullOrWhiteSpace(configuredRoot) || !Path.IsPathFullyQualified(configuredRoot))
            return null;

        try
        {
            var root = ResolveDirectoryPath(configuredRoot);
            var webRoot = ResolveDirectoryPath(environment.WebRootPath
                ?? Path.Combine(environment.ContentRootPath, "wwwroot"));
            var relative = Path.GetRelativePath(webRoot, root);
            if (relative != ".." && !relative.StartsWith($"..{Path.DirectorySeparatorChar}", StringComparison.Ordinal)
                && !Path.IsPathRooted(relative))
                return null;

            var databasePath = Path.Combine(root, "builder-schematics.db");
            return new FileInfo(databasePath).LinkTarget is null ? databasePath : null;
        }
        catch (Exception exception) when (exception is ArgumentException or IOException or UnauthorizedAccessException
            or NotSupportedException)
        {
            return null;
        }
    }

    private static string ResolveDirectoryPath(string path)
    {
        var absolute = Path.GetFullPath(path);
        var resolved = Path.GetPathRoot(absolute)!;
        foreach (var part in absolute[resolved.Length..].Split(Path.DirectorySeparatorChar, StringSplitOptions.RemoveEmptyEntries))
        {
            var directory = new DirectoryInfo(Path.Combine(resolved, part));
            resolved = directory.LinkTarget is null
                ? directory.FullName
                : directory.ResolveLinkTarget(returnFinalTarget: true)!.FullName;
        }
        return resolved;
    }
}
