using System.Text;
using System.Text.RegularExpressions;
using Microsoft.Data.SqlClient;
using Microsoft.Extensions.Configuration;

const string ManagementDatabase = "ManagementDB";
const string WebCredentialsMigrationId = "20260917_web_credentials";
const string WebCredentialsMigrationFileName = "20260917_web_credentials.sql";
const string WebCredentialsMigrationSha256 = "F0A41E24D0364ADAC6BD058941A2DB54239B130A923139AE8CC4ABD6FF12058B";
const string TrustedBrowserMigrationId = "20260920_trusted_admin_browser";
const string TrustedBrowserMigrationFileName = "20260920_trusted_admin_browser.sql";
const string TrustedBrowserMigrationSha256 = "3F5F0DCD5872C60F3A4A2463C961053C5D36D255A88B34DBB6D209F5F4622B6B";
var options = CommandLineOptions.Parse(args);
if (options.ShowHelp)
{
    Console.WriteLine("Usage: ManagementDbMigrateTool --config <path> [--validate-only]");
    return 0;
}

try
{
    var configPath = ResolveConfigPath(options.ConfigPath);
    var config = LoadConfig(configPath);
    var migrationsRoot = ResolvePath(config.MigrationsRootPath, configPath, "migrationsRootPath");
    var targetDatabase = ResolveTargetDatabase(config);
    ValidateManifest(config, migrationsRoot, targetDatabase);
    Console.WriteLine($"Management migration manifest validated: {config.Migrations.Count} migration(s).");

    if (options.ValidateOnly)
        return 0;

    var connectionString = ResolveConnectionString(config, configPath);
    var builder = new SqlConnectionStringBuilder(connectionString);
    if (!string.Equals(builder.InitialCatalog, targetDatabase, StringComparison.OrdinalIgnoreCase))
        throw new InvalidOperationException("Management migration connection does not target its configured database.");

    await using var connection = new SqlConnection(builder.ConnectionString);
    await connection.OpenAsync();
    await AssertConnectedDatabaseAsync(connection, null, targetDatabase);
    await AssertPrerequisitesAsync(connection);

    await using var transaction = (SqlTransaction)await connection.BeginTransactionAsync();
    try
    {
        await AcquireLockAsync(connection, transaction);
        foreach (var migration in config.Migrations)
        {
            if (await IsAppliedAsync(connection, transaction, migration.Id!))
            {
                Console.WriteLine($"Management migration already applied: {migration.Id}");
            }
            else
            {
                Console.WriteLine($"Applying management migration: {migration.FileName}");
                var script = await ReadKnownMigrationScriptAsync(migrationsRoot, targetDatabase, migration.FileName!);
                await ExecuteMigrationAsync(connection, transaction, migration, script, targetDatabase);
                if (!await IsAppliedAsync(connection, transaction, migration.Id!))
                    throw new InvalidOperationException($"Migration did not record its completion: {migration.Id}");
            }

            await ValidateMigrationAsync(connection, transaction, migration);
        }

        await transaction.CommitAsync();
    }
    catch
    {
        await transaction.RollbackAsync();
        throw;
    }

    Console.WriteLine("ManagementDB migration and schema validation completed successfully.");
    return 0;
}
catch (Exception exception)
{
    Console.Error.WriteLine($"ManagementDB migration failed: {exception.Message}");
    return 1;
}

static string ResolveConfigPath(string? configuredPath) =>
    string.IsNullOrWhiteSpace(configuredPath)
        ? Path.Combine(AppContext.BaseDirectory, "management-db-migrate.config.json")
        : Path.GetFullPath(configuredPath);

static ManagementMigrationConfig LoadConfig(string configPath)
{
    if (!File.Exists(configPath))
        throw new FileNotFoundException($"Config file was not found: {configPath}");
    return new ConfigurationBuilder().AddJsonFile(configPath, optional: false).Build()
        .Get<ManagementMigrationConfig>()
        ?? throw new InvalidOperationException("Management migration configuration could not be loaded.");
}

static string ResolvePath(string? configuredPath, string configPath, string label)
{
    if (string.IsNullOrWhiteSpace(configuredPath))
        throw new InvalidOperationException($"{label} must be configured.");
    return Path.IsPathRooted(configuredPath)
        ? Path.GetFullPath(configuredPath)
        : Path.GetFullPath(Path.Combine(Path.GetDirectoryName(Path.GetFullPath(configPath))!, configuredPath));
}

static string ResolveConnectionString(ManagementMigrationConfig config, string configPath)
{
    if (!string.IsNullOrWhiteSpace(config.ConnectionStrings.Management))
        return config.ConnectionStrings.Management;
    if (string.IsNullOrWhiteSpace(config.SourceApiAppsettingsPath))
        throw new InvalidOperationException("ConnectionStrings:Management or sourceApiAppsettingsPath must be configured.");

    var path = ResolvePath(config.SourceApiAppsettingsPath, configPath, "sourceApiAppsettingsPath");
    if (!File.Exists(path))
        throw new FileNotFoundException("Source API appsettings was not found.");
    var source = new ConfigurationBuilder().AddJsonFile(path, optional: false).Build();
    var management = source.GetConnectionString("Management");
    if (!string.IsNullOrWhiteSpace(management))
        return management;
    var game = source.GetConnectionString("SqlServer");
    if (string.IsNullOrWhiteSpace(game))
        throw new InvalidOperationException("ConnectionStrings:Management and ConnectionStrings:SqlServer are both unavailable in source API appsettings.");
    return new SqlConnectionStringBuilder(game) { InitialCatalog = ResolveTargetDatabase(config) }.ConnectionString;
}

static string ResolveTargetDatabase(ManagementMigrationConfig config)
{
    var target = string.IsNullOrWhiteSpace(config.ExpectedDatabase) ? ManagementDatabase : config.ExpectedDatabase.Trim();
    if (string.Equals(target, ManagementDatabase, StringComparison.OrdinalIgnoreCase)) return ManagementDatabase;
    if (Regex.IsMatch(target, @"\AAR_ManagementDbMigrate_Test_[0-9a-f]{32}\z", RegexOptions.IgnoreCase)) return target;
    throw new InvalidOperationException("Management migration may target only ManagementDB. Isolated integration tests require an AR_ManagementDbMigrate_Test_<guid> database.");
}

static void ValidateManifest(ManagementMigrationConfig config, string migrationsRoot, string targetDatabase)
{
    if (!Directory.Exists(migrationsRoot))
        throw new DirectoryNotFoundException("Management migration directory was not found.");
    if (config.Migrations.Count == 0)
        throw new InvalidOperationException("At least one explicit management migration must be configured.");
    if (config.Migrations.Count != 2
        || !string.Equals(config.Migrations[0].Id, WebCredentialsMigrationId, StringComparison.Ordinal)
        || !string.Equals(config.Migrations[0].FileName, WebCredentialsMigrationFileName, StringComparison.Ordinal)
        || !string.Equals(config.Migrations[1].Id, TrustedBrowserMigrationId, StringComparison.Ordinal)
        || !string.Equals(config.Migrations[1].FileName, TrustedBrowserMigrationFileName, StringComparison.Ordinal))
        throw new InvalidOperationException($"Only the reviewed ManagementDB migrations {WebCredentialsMigrationFileName} and {TrustedBrowserMigrationFileName} may be applied by this runner.");
    var ids = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
    var files = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
    foreach (var migration in config.Migrations)
    {
        if (string.IsNullOrWhiteSpace(migration.Id) || !ids.Add(migration.Id))
            throw new InvalidOperationException("Every management migration needs a unique ID.");
        if (string.IsNullOrWhiteSpace(migration.FileName) || !files.Add(migration.FileName))
            throw new InvalidOperationException("Every management migration needs a unique fileName.");
        if (migration.Tables.Count == 0)
            throw new InvalidOperationException($"Schema expectations are missing: {migration.FileName}");
        var script = ReadKnownMigrationScript(migrationsRoot, targetDatabase, migration.FileName);
        if (string.IsNullOrWhiteSpace(script))
            throw new InvalidOperationException($"Migration file is empty: {migration.FileName}");
        foreach (Match match in Regex.Matches(script, @"(?im)^\s*USE\s+\[?([A-Za-z0-9_]+)\]?\s*;?\s*$"))
            if (!string.Equals(match.Groups[1].Value, targetDatabase, StringComparison.OrdinalIgnoreCase))
                throw new InvalidOperationException($"Management migration may not change to another database: {migration.FileName}");
    }
}

static async Task<string> ReadKnownMigrationScriptAsync(string migrationsRoot, string targetDatabase, string fileName)
{
    var path = GetMigrationPath(migrationsRoot, fileName);
    var bytes = await File.ReadAllBytesAsync(path);
    return PrepareKnownMigrationScript(bytes, targetDatabase, fileName);
}

static string ReadKnownMigrationScript(string migrationsRoot, string targetDatabase, string? fileName)
{
    if (string.IsNullOrWhiteSpace(fileName))
        throw new InvalidOperationException("Migration fileName is required.");
    var bytes = File.ReadAllBytes(GetMigrationPath(migrationsRoot, fileName));
    return PrepareKnownMigrationScript(bytes, targetDatabase, fileName);
}

static string PrepareKnownMigrationScript(byte[] bytes, string targetDatabase, string fileName)
{
    var offset = bytes.Length >= 3 && bytes[0] == 0xEF && bytes[1] == 0xBB && bytes[2] == 0xBF ? 3 : 0;
    var script = new UTF8Encoding(encoderShouldEmitUTF8Identifier: false, throwOnInvalidBytes: true)
        .GetString(bytes, offset, bytes.Length - offset)
        .Replace("\r\n", "\n", StringComparison.Ordinal)
        .Replace("\r", "\n", StringComparison.Ordinal);
    var hash = Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(Encoding.UTF8.GetBytes(script)));
    if (!string.Equals(hash, ExpectedMigrationHash(fileName), StringComparison.Ordinal))
        throw new InvalidOperationException($"Management migration script hash does not match the reviewed {fileName}.");
    return string.Equals(targetDatabase, ManagementDatabase, StringComparison.OrdinalIgnoreCase)
        ? script
        : script.Replace("USE [ManagementDB];", $"USE [{targetDatabase}];", StringComparison.Ordinal);
}

static string ExpectedMigrationHash(string fileName) => fileName switch
{
    WebCredentialsMigrationFileName => WebCredentialsMigrationSha256,
    TrustedBrowserMigrationFileName => TrustedBrowserMigrationSha256,
    _ => throw new InvalidOperationException($"Migration file is not approved by this runner: {fileName}"),
};

static string GetMigrationPath(string root, string fileName)
{
    var path = Path.GetFullPath(Path.Combine(root, fileName));
    if (!path.StartsWith(root.TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase)
        || !File.Exists(path))
        throw new FileNotFoundException("Management migration file was not found.");
    return path;
}

static async Task AssertConnectedDatabaseAsync(SqlConnection connection, SqlTransaction? transaction, string targetDatabase)
{
    await using var command = new SqlCommand("SELECT DB_NAME();", connection, transaction);
    var actual = (string?)await command.ExecuteScalarAsync();
    if (!string.Equals(actual, targetDatabase, StringComparison.OrdinalIgnoreCase))
        throw new InvalidOperationException("Connected database changed during management migration.");
}

static async Task AssertPrerequisitesAsync(SqlConnection connection)
{
    const string sql = "SELECT OBJECT_ID(N'dbo.player', N'U'), OBJECT_ID(N'dbo.schema_migration', N'U');";
    await using var command = new SqlCommand(sql, connection);
    await using var reader = await command.ExecuteReaderAsync();
    if (!await reader.ReadAsync() || reader.IsDBNull(0) || reader.IsDBNull(1))
        throw new InvalidOperationException("ManagementDB is not initialized. Apply 00_docs/40_Database設計書/table-definitions/ManagementDB/init.sql first, then rerun this tool.");
}

static async Task AcquireLockAsync(SqlConnection connection, SqlTransaction transaction)
{
    const string sql = "DECLARE @result INT; EXEC @result = sys.sp_getapplock @Resource=N'ManagementDB.schema_migration', @LockMode=N'Exclusive', @LockOwner=N'Transaction', @LockTimeout=60000; SELECT @result;";
    await using var command = new SqlCommand(sql, connection, transaction);
    var result = Convert.ToInt32(await command.ExecuteScalarAsync());
    if (result < 0)
        throw new InvalidOperationException("ManagementDB migration lock unavailable.");
}

static async Task<bool> IsAppliedAsync(SqlConnection connection, SqlTransaction transaction, string id)
{
    await using var command = new SqlCommand("SELECT COUNT(*) FROM dbo.schema_migration WHERE migration_id=@id;", connection, transaction);
    command.Parameters.AddWithValue("@id", id);
    return Convert.ToInt32(await command.ExecuteScalarAsync()) == 1;
}

static async Task ExecuteMigrationAsync(SqlConnection connection, SqlTransaction transaction, ManagementMigration migration, string script, string targetDatabase)
{
    var timeout = migration.CommandTimeoutSeconds is > 0 and <= 600 ? migration.CommandTimeoutSeconds.Value : 120;
    foreach (var batch in Regex.Split(script, @"(?im)^\s*GO\s*(?:--.*)?\s*$"))
    {
        if (string.IsNullOrWhiteSpace(batch)) continue;
        await using var command = new SqlCommand(batch, connection, transaction) { CommandTimeout = timeout };
        await command.ExecuteNonQueryAsync();
        await AssertConnectedDatabaseAsync(connection, transaction, targetDatabase);
    }
}

static async Task ValidateMigrationAsync(SqlConnection connection, SqlTransaction transaction, ManagementMigration migration)
{
    foreach (var table in migration.Tables)
    {
        var objectId = await ScalarIntAsync(connection, transaction, "SELECT OBJECT_ID(@name, N'U');", ("@name", $"dbo.{table.Name}"));
        if (objectId is null) throw new InvalidOperationException($"Expected table was not found: dbo.{table.Name}");
        foreach (var column in table.Columns)
        {
            const string sql = "SELECT t.name, c.max_length, c.precision, c.scale, c.is_nullable FROM sys.columns c JOIN sys.types t ON t.user_type_id=c.user_type_id WHERE c.object_id=@objectId AND c.name=@name;";
            await using var command = new SqlCommand(sql, connection, transaction);
            command.Parameters.AddWithValue("@objectId", objectId.Value); command.Parameters.AddWithValue("@name", column.Name!);
            await using var reader = await command.ExecuteReaderAsync();
            if (!await reader.ReadAsync() || !string.Equals(reader.GetString(0), column.SqlType, StringComparison.OrdinalIgnoreCase)
                || (column.MaxLengthBytes.HasValue && reader.GetInt16(1) != column.MaxLengthBytes)
                || (column.Precision.HasValue && reader.GetByte(2) != column.Precision)
                || (column.Scale.HasValue && reader.GetByte(3) != column.Scale)
                || (column.IsNullable.HasValue && reader.GetBoolean(4) != column.IsNullable))
                throw new InvalidOperationException($"Expected column definition does not match: dbo.{table.Name}.{column.Name}");
        }
        if (!string.IsNullOrWhiteSpace(table.PrimaryKey) && await ScalarIntAsync(connection, transaction, "SELECT COUNT(*) FROM sys.indexes WHERE object_id=@id AND name=@name AND is_primary_key=1;", ("@id", objectId.Value), ("@name", table.PrimaryKey)) != 1)
            throw new InvalidOperationException($"Expected primary key was not found: {table.PrimaryKey}");
        foreach (var name in table.CheckConstraints)
            if (await ScalarIntAsync(connection, transaction, "SELECT COUNT(*) FROM sys.check_constraints WHERE parent_object_id=@id AND name=@name;", ("@id", objectId.Value), ("@name", name)) != 1)
                throw new InvalidOperationException($"Expected check constraint was not found: {name}");
        foreach (var name in table.FilteredUniqueIndexes)
            if (await ScalarIntAsync(connection, transaction, "SELECT COUNT(*) FROM sys.indexes WHERE object_id=@id AND name=@name AND is_disabled=0 AND is_unique=1 AND has_filter=1;", ("@id", objectId.Value), ("@name", name)) != 1)
                throw new InvalidOperationException($"Expected filtered unique index was not found: {name}");
        foreach (var name in table.UniqueIndexes)
            if (await ScalarIntAsync(connection, transaction, "SELECT COUNT(*) FROM sys.indexes WHERE object_id=@id AND name=@name AND is_disabled=0 AND is_unique=1 AND has_filter=0;", ("@id", objectId.Value), ("@name", name)) != 1)
                throw new InvalidOperationException($"Expected unique index was not found: {name}");
    }
    if (!await IsAppliedAsync(connection, transaction, migration.Id!))
        throw new InvalidOperationException($"Migration history row was not found: {migration.Id}");
}

static async Task<int?> ScalarIntAsync(SqlConnection connection, SqlTransaction transaction, string sql, params (string Name, object Value)[] parameters)
{
    await using var command = new SqlCommand(sql, connection, transaction);
    foreach (var parameter in parameters) command.Parameters.AddWithValue(parameter.Name, parameter.Value);
    var value = await command.ExecuteScalarAsync();
    return value is null || value is DBNull ? null : Convert.ToInt32(value);
}

internal sealed class ManagementMigrationConfig { public string? SourceApiAppsettingsPath { get; init; } public string? ExpectedDatabase { get; init; } public ManagementConnections ConnectionStrings { get; init; } = new(); public string? MigrationsRootPath { get; init; } public List<ManagementMigration> Migrations { get; init; } = new(); }
internal sealed class ManagementConnections { public string? Management { get; init; } }
internal sealed class ManagementMigration { public string? Id { get; init; } public string? FileName { get; init; } public int? CommandTimeoutSeconds { get; init; } public List<TableExpectation> Tables { get; init; } = new(); }
internal sealed class TableExpectation { public string? Name { get; init; } public string? PrimaryKey { get; init; } public List<ColumnExpectation> Columns { get; init; } = new(); public List<string> CheckConstraints { get; init; } = new(); public List<string> FilteredUniqueIndexes { get; init; } = new(); public List<string> UniqueIndexes { get; init; } = new(); }
internal sealed class ColumnExpectation { public string? Name { get; init; } public string? SqlType { get; init; } public int? MaxLengthBytes { get; init; } public byte? Precision { get; init; } public byte? Scale { get; init; } public bool? IsNullable { get; init; } }
internal sealed record CommandLineOptions(string? ConfigPath, bool ShowHelp, bool ValidateOnly) { public static CommandLineOptions Parse(string[] args) { string? config = null; var validate = false; for (var i=0;i<args.Length;i++) { if (args[i] is "--help" or "-h") return new(null, true, false); if (args[i].Equals("--config", StringComparison.OrdinalIgnoreCase)) { if (++i >= args.Length) throw new ArgumentException("A path is required after --config."); config=args[i]; continue; } if (args[i].Equals("--validate-only", StringComparison.OrdinalIgnoreCase)) { validate=true; continue; } throw new ArgumentException($"Unknown argument: {args[i]}"); } return new(config, false, validate); } }
