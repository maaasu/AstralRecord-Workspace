using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using Microsoft.Data.SqlClient;
using Microsoft.Extensions.Configuration;

var options = CommandLineOptions.Parse(args);
if (options.ShowHelp)
{
    Console.WriteLine("Usage: DbMigrateTool --config <path> [--validate-only]");
    return 0;
}

try
{
    var configPath = ResolveConfigPath(options.ConfigPath);
    var config = LoadConfig(configPath);
    var migrationsRootPath = ResolvePath(config.MigrationsRootPath, configPath, "migrationsRootPath");

    if (config.Migrations.Count == 0)
        throw new InvalidOperationException("At least one migration must be configured.");

    ValidateMigrationManifest(config, migrationsRootPath);
    PrintSummary(configPath, migrationsRootPath, config.Migrations);

    if (options.ValidateOnly)
    {
        Console.WriteLine("Migration manifest validation completed successfully.");
        return 0;
    }

    var connectionString = ResolveConnectionString(config, configPath);
    await using var connection = new SqlConnection(connectionString);
    await connection.OpenAsync();
    await AcquireLockAsync(connection);
    try
    {
        await EnsureMigrationHistoryTableAsync(connection);
        foreach (var migration in config.Migrations)
        {
            var script = await ReadMigrationScriptAsync(migration, migrationsRootPath);
            var scriptHash = ComputeSha256(script);
            var applied = await FindAppliedMigrationAsync(connection, migration.Id!);
            var shouldRecord = false;
            if (applied is not null)
            {
                if (!string.Equals(applied.FileName, migration.FileName, StringComparison.OrdinalIgnoreCase)
                    || !string.Equals(applied.ScriptSha256, scriptHash, StringComparison.OrdinalIgnoreCase))
                {
                    throw new InvalidOperationException(
                        $"Migration {migration.Id} was already applied with a different script hash. Create a new migration ID instead of changing an applied migration.");
                }

                Console.WriteLine($"Migration already applied: {migration.Id}");
            }
            else
            {
                await ApplyMigrationAsync(connection, migration, script);
                shouldRecord = true;
            }

            await ValidateSchemaAsync(connection, migration);
            if (shouldRecord)
                await RecordAppliedMigrationAsync(connection, migration, scriptHash);
        }
    }
    finally
    {
        await ReleaseLockAsync(connection);
    }

    Console.WriteLine("Database migration and schema validation completed successfully.");
    return 0;
}
catch (Exception exception)
{
    Console.Error.WriteLine($"Database migration failed: {exception.Message}");
    return 1;
}

static string ResolveConfigPath(string? configPath)
{
    if (!string.IsNullOrWhiteSpace(configPath))
        return Path.GetFullPath(configPath);

    return Path.Combine(AppContext.BaseDirectory, "db-migrate.config.json");
}

static MigrationConfig LoadConfig(string configPath)
{
    if (!File.Exists(configPath))
        throw new FileNotFoundException($"Config file was not found: {configPath}");

    var configuration = new ConfigurationBuilder()
        .AddJsonFile(configPath, optional: false, reloadOnChange: false)
        .Build();

    return configuration.Get<MigrationConfig>()
        ?? throw new InvalidOperationException($"Failed to load config: {configPath}");
}

static string ResolveConnectionString(MigrationConfig config, string configPath)
{
    var configured = config.ConnectionStrings.SqlServer;
    if (!string.IsNullOrWhiteSpace(configured))
        return configured;

    if (string.IsNullOrWhiteSpace(config.SourceApiAppsettingsPath))
        throw new InvalidOperationException(
            "ConnectionStrings:SqlServer or sourceApiAppsettingsPath must be configured.");

    var sourcePath = ResolvePath(config.SourceApiAppsettingsPath, configPath, "sourceApiAppsettingsPath");
    if (!File.Exists(sourcePath))
        throw new FileNotFoundException($"Source API appsettings was not found: {sourcePath}");

    var sourceConfiguration = new ConfigurationBuilder()
        .AddJsonFile(sourcePath, optional: false, reloadOnChange: false)
        .Build();
    var connectionString = sourceConfiguration.GetConnectionString("SqlServer");
    if (string.IsNullOrWhiteSpace(connectionString))
        throw new InvalidOperationException(
            "ConnectionStrings:SqlServer could not be resolved from the source API appsettings.");

    return connectionString;
}

static string ResolvePath(string? configuredPath, string configPath, string label)
{
    if (string.IsNullOrWhiteSpace(configuredPath))
        throw new InvalidOperationException($"{label} must be configured.");

    if (Path.IsPathRooted(configuredPath))
        return Path.GetFullPath(configuredPath);

    var configDirectory = Path.GetDirectoryName(Path.GetFullPath(configPath))
        ?? throw new InvalidOperationException($"Could not resolve config directory: {configPath}");
    return Path.GetFullPath(Path.Combine(configDirectory, configuredPath));
}

static void ValidateMigrationManifest(MigrationConfig config, string migrationsRootPath)
{
    var configuredNames = config.Migrations
        .Select(migration => migration.FileName)
        .Where(fileName => !string.IsNullOrWhiteSpace(fileName))
        .Select(fileName => fileName!)
        .ToHashSet(StringComparer.OrdinalIgnoreCase);
    if (configuredNames.Count != config.Migrations.Count)
        throw new InvalidOperationException("Every migration must have a unique fileName.");

    var managedNames = config.Migrations
        .Select(migration => migration.FileName!)
        .Concat(config.PreExistingMigrationFileNames)
        .ToList();
    if (managedNames.Count != managedNames.Distinct(StringComparer.OrdinalIgnoreCase).Count())
        throw new InvalidOperationException("A migration file cannot be listed in both migrations and preExistingMigrationFileNames.");

    foreach (var migration in config.Migrations)
    {
        if (string.IsNullOrWhiteSpace(migration.Id))
            throw new InvalidOperationException($"Migration ID is missing: {migration.FileName}");
        _ = GetMigrationPath(migration.FileName!, migrationsRootPath);
        if (migration.Expectation is null)
            throw new InvalidOperationException($"Schema expectation is missing: {migration.FileName}");
        var script = File.ReadAllText(GetMigrationPath(migration.FileName!, migrationsRootPath), Encoding.UTF8);
        if (string.IsNullOrWhiteSpace(script))
            throw new InvalidOperationException($"Migration file is empty: {migration.FileName}");
    }

    foreach (var file in Directory.EnumerateFiles(migrationsRootPath, "*.sql", SearchOption.TopDirectoryOnly))
    {
        var fileName = Path.GetFileName(file);
        if (!managedNames.Contains(fileName, StringComparer.OrdinalIgnoreCase))
        {
            throw new InvalidOperationException(
                $"Migration file is not registered in the manifest or preExistingMigrationFileNames: {fileName}");
        }
    }
}

static string GetMigrationPath(string fileName, string migrationsRootPath)
{
    var migrationPath = Path.GetFullPath(Path.Combine(migrationsRootPath, fileName));
    var rootWithSeparator = migrationsRootPath.TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar;
    if (!migrationPath.StartsWith(rootWithSeparator, StringComparison.OrdinalIgnoreCase))
        throw new InvalidOperationException($"Migration path escapes migrationsRootPath: {fileName}");
    if (!File.Exists(migrationPath))
        throw new FileNotFoundException($"Migration file was not found: {migrationPath}");
    return migrationPath;
}

static async Task<string> ReadMigrationScriptAsync(MigrationDefinition migration, string migrationsRootPath)
{
    var migrationPath = GetMigrationPath(migration.FileName!, migrationsRootPath);
    var script = await File.ReadAllTextAsync(migrationPath, Encoding.UTF8);
    if (string.IsNullOrWhiteSpace(script))
        throw new InvalidOperationException($"Migration file is empty: {migration.FileName}");
    return script;
}

static string ComputeSha256(string content)
    => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(content)));

static async Task ApplyMigrationAsync(
    SqlConnection connection,
    MigrationDefinition migration,
    string script)
{
    Console.WriteLine($"Applying migration: {migration.FileName}");
    var commandTimeout = migration.CommandTimeoutSeconds is > 0 and <= 600
        ? migration.CommandTimeoutSeconds.Value
        : 120;

    foreach (var batch in Regex.Split(script, @"(?im)^\s*GO\s*(?:--.*)?\s*$"))
    {
        if (string.IsNullOrWhiteSpace(batch))
            continue;

        await using var command = new SqlCommand(batch, connection)
        {
            CommandTimeout = commandTimeout,
        };
        await command.ExecuteNonQueryAsync();
    }
}

static async Task EnsureMigrationHistoryTableAsync(SqlConnection connection)
{
    const string commandText = """
        IF OBJECT_ID(N'[dbo].[schema_migration]', N'U') IS NULL
        BEGIN
            CREATE TABLE [dbo].[schema_migration] (
                [migration_id]  NVARCHAR(128) NOT NULL,
                [file_name]     NVARCHAR(260) NOT NULL,
                [script_sha256] CHAR(64)      NOT NULL,
                [applied_at]    DATETIME2(3)  NOT NULL CONSTRAINT [DF_schema_migration_applied_at] DEFAULT (SYSUTCDATETIME()),
                CONSTRAINT [PK_schema_migration] PRIMARY KEY CLUSTERED ([migration_id]),
                CONSTRAINT [CK_schema_migration_script_sha256]
                    CHECK ([script_sha256] LIKE '[0-9A-Fa-f]' + REPLICATE('[0-9A-Fa-f]', 63))
            );
        END;
        """;
    await using var command = new SqlCommand(commandText, connection);
    await command.ExecuteNonQueryAsync();
}

static async Task<AppliedMigration?> FindAppliedMigrationAsync(SqlConnection connection, string migrationId)
{
    const string commandText = """
        SELECT [file_name], [script_sha256]
        FROM [dbo].[schema_migration]
        WHERE [migration_id] = @migrationId;
        """;
    await using var command = new SqlCommand(commandText, connection);
    command.Parameters.AddWithValue("@migrationId", migrationId);
    await using var reader = await command.ExecuteReaderAsync();
    if (!await reader.ReadAsync())
        return null;
    return new AppliedMigration(reader.GetString(0), reader.GetString(1));
}

static async Task RecordAppliedMigrationAsync(
    SqlConnection connection,
    MigrationDefinition migration,
    string scriptHash)
{
    const string commandText = """
        INSERT INTO [dbo].[schema_migration] ([migration_id], [file_name], [script_sha256])
        VALUES (@migrationId, @fileName, @scriptSha256);
        """;
    await using var command = new SqlCommand(commandText, connection);
    command.Parameters.AddWithValue("@migrationId", migration.Id!);
    command.Parameters.AddWithValue("@fileName", migration.FileName!);
    command.Parameters.AddWithValue("@scriptSha256", scriptHash);
    await command.ExecuteNonQueryAsync();
}

static async Task ValidateSchemaAsync(SqlConnection connection, MigrationDefinition migration)
{
    var expectation = migration.Expectation
        ?? throw new InvalidOperationException($"Schema expectation is missing: {migration.FileName}");
    var schema = string.IsNullOrWhiteSpace(expectation.Schema) ? "dbo" : expectation.Schema;
    if (string.IsNullOrWhiteSpace(expectation.Table))
        throw new InvalidOperationException($"Expected table is missing: {migration.FileName}");

    var fullName = $"{schema}.{expectation.Table}";
    var objectId = await ExecuteScalarIntAsync(
        connection,
        "SELECT OBJECT_ID(@fullName, N'U');",
        ("@fullName", fullName));
    if (objectId is null)
        throw new InvalidOperationException($"Expected table was not found: {fullName}");

    foreach (var column in expectation.Columns)
    {
        const string columnSql = """
            SELECT t.[name], c.[max_length], c.[precision], c.[scale], c.[is_nullable]
            FROM sys.columns AS c
            INNER JOIN sys.types AS t ON t.[user_type_id] = c.[user_type_id]
            WHERE c.[object_id] = @objectId AND c.[name] = @name;
            """;
        await using var columnCommand = new SqlCommand(columnSql, connection);
        columnCommand.Parameters.AddWithValue("@objectId", objectId.Value);
        columnCommand.Parameters.AddWithValue("@name", column.Name!);
        await using var reader = await columnCommand.ExecuteReaderAsync();
        if (!await reader.ReadAsync())
            throw new InvalidOperationException($"Expected column was not found: {fullName}.{column.Name}");

        var actualType = reader.GetString(0);
        var actualMaxLength = reader.GetInt16(1);
        var actualPrecision = reader.GetByte(2);
        var actualScale = reader.GetByte(3);
        var actualNullable = reader.GetBoolean(4);
        if (!string.Equals(actualType, column.SqlType, StringComparison.OrdinalIgnoreCase)
            || (column.MaxLengthBytes.HasValue && actualMaxLength != column.MaxLengthBytes.Value)
            || (column.Precision.HasValue && actualPrecision != column.Precision.Value)
            || (column.Scale.HasValue && actualScale != column.Scale.Value)
            || (column.IsNullable.HasValue && actualNullable != column.IsNullable.Value))
        {
            throw new InvalidOperationException(
                $"Expected column definition does not match: {fullName}.{column.Name} "
                + $"(actual type={actualType}, maxLengthBytes={actualMaxLength}, precision={actualPrecision}, scale={actualScale}, nullable={actualNullable})");
        }
    }

    foreach (var checkConstraint in expectation.CheckConstraints)
    {
        var count = await ExecuteScalarIntAsync(
            connection,
            "SELECT COUNT(*) FROM sys.check_constraints WHERE parent_object_id = @objectId AND name = @name;",
            ("@objectId", objectId.Value),
            ("@name", checkConstraint));
        if (count != 1)
            throw new InvalidOperationException($"Expected check constraint was not found: {checkConstraint}");
    }

    if (!string.IsNullOrWhiteSpace(expectation.PrimaryKey))
    {
        var count = await ExecuteScalarIntAsync(
            connection,
            "SELECT COUNT(*) FROM sys.indexes WHERE object_id = @objectId AND name = @name AND is_primary_key = 1;",
            ("@objectId", objectId.Value),
            ("@name", expectation.PrimaryKey));
        if (count != 1)
            throw new InvalidOperationException($"Expected primary key was not found: {expectation.PrimaryKey}");
    }

    foreach (var index in expectation.Indexes)
    {
        var count = await ExecuteScalarIntAsync(
            connection,
            "SELECT COUNT(*) FROM sys.indexes WHERE object_id = @objectId AND name = @name AND is_disabled = 0;",
            ("@objectId", objectId.Value),
            ("@name", index));
        if (count != 1)
            throw new InvalidOperationException($"Expected index was not found: {index}");

        var expectedIndex = expectation.IndexDefinitions.FirstOrDefault(item =>
            string.Equals(item.Name, index, StringComparison.OrdinalIgnoreCase));
        if (expectedIndex is not null)
        {
            const string indexColumnSql = """
                SELECT c.[name]
                FROM sys.index_columns AS ic
                INNER JOIN sys.columns AS c
                    ON c.[object_id] = ic.[object_id] AND c.[column_id] = ic.[column_id]
                INNER JOIN sys.indexes AS i
                    ON i.[object_id] = ic.[object_id] AND i.[index_id] = ic.[index_id]
                WHERE ic.[object_id] = @objectId
                  AND i.[name] = @name
                  AND ic.[is_included_column] = 0
                ORDER BY ic.[key_ordinal];
                """;
            await using var indexCommand = new SqlCommand(indexColumnSql, connection);
            indexCommand.Parameters.AddWithValue("@objectId", objectId.Value);
            indexCommand.Parameters.AddWithValue("@name", index);
            await using var indexReader = await indexCommand.ExecuteReaderAsync();
            var actualColumns = new List<string>();
            while (await indexReader.ReadAsync())
                actualColumns.Add(indexReader.GetString(0));
            if (!actualColumns.SequenceEqual(expectedIndex.Columns, StringComparer.OrdinalIgnoreCase))
            {
                throw new InvalidOperationException(
                    $"Expected index key order does not match: {index} (actual: {string.Join(", ", actualColumns)})");
            }
        }
    }

    Console.WriteLine($"Schema validated: {fullName}");
}

static async Task<int?> ExecuteScalarIntAsync(
    SqlConnection connection,
    string commandText,
    params (string Name, object? Value)[] parameters)
{
    await using var command = new SqlCommand(commandText, connection);
    foreach (var parameter in parameters)
        command.Parameters.AddWithValue(parameter.Name, parameter.Value ?? DBNull.Value);

    var value = await command.ExecuteScalarAsync();
    if (value is null || value is DBNull)
        return null;
    return Convert.ToInt32(value);
}

static async Task AcquireLockAsync(SqlConnection connection)
{
    const string commandText = """
        DECLARE @result int;
        EXEC @result = sp_getapplock
            @Resource = N'AstralRecord.SchemaMigrationRunner',
            @LockMode = N'Exclusive',
            @LockOwner = N'Session',
            @LockTimeout = 120000;
        IF @result < 0
            THROW 51000, N'Could not acquire the database migration lock.', 1;
        """;
    await using var command = new SqlCommand(commandText, connection);
    await command.ExecuteNonQueryAsync();
}

static async Task ReleaseLockAsync(SqlConnection connection)
{
    if (connection.State != System.Data.ConnectionState.Open)
        return;

    const string commandText = "EXEC sp_releaseapplock @Resource = N'AstralRecord.SchemaMigrationRunner', @LockOwner = N'Session';";
    await using var command = new SqlCommand(commandText, connection);
    await command.ExecuteNonQueryAsync();
}

static void PrintSummary(
    string configPath,
    string migrationsRootPath,
    IReadOnlyCollection<MigrationDefinition> migrations)
{
    Console.WriteLine("=== Database Migration Tool ===");
    Console.WriteLine($"Config: {configPath}");
    Console.WriteLine($"Migrations root: {migrationsRootPath}");
    Console.WriteLine($"Migration count: {migrations.Count}");
}

internal sealed record AppliedMigration(string FileName, string ScriptSha256);

internal sealed class MigrationConfig
{
    public string? SourceApiAppsettingsPath { get; init; }
    public MigrationConnectionStrings ConnectionStrings { get; init; } = new();
    public string? MigrationsRootPath { get; init; }
    public List<MigrationDefinition> Migrations { get; init; } = new();
    public List<string> PreExistingMigrationFileNames { get; init; } = new();
}

internal sealed class MigrationConnectionStrings
{
    public string? SqlServer { get; init; }
}

internal sealed class MigrationDefinition
{
    public string? Id { get; init; }
    public string? FileName { get; init; }
    public int? CommandTimeoutSeconds { get; init; }
    public SchemaExpectation? Expectation { get; init; }
}

internal sealed class SchemaExpectation
{
    public string? Schema { get; init; }
    public string? Table { get; init; }
    public string? PrimaryKey { get; init; }
    public ColumnExpectation[] Columns { get; init; } = Array.Empty<ColumnExpectation>();
    public string[] CheckConstraints { get; init; } = Array.Empty<string>();
    public string[] Indexes { get; init; } = Array.Empty<string>();
    public IndexExpectation[] IndexDefinitions { get; init; } = Array.Empty<IndexExpectation>();
}

internal sealed class ColumnExpectation
{
    public string? Name { get; init; }
    public string? SqlType { get; init; }
    public int? MaxLengthBytes { get; init; }
    public byte? Precision { get; init; }
    public byte? Scale { get; init; }
    public bool? IsNullable { get; init; }
}

internal sealed class IndexExpectation
{
    public string? Name { get; init; }
    public string[] Columns { get; init; } = Array.Empty<string>();
}

internal sealed record CommandLineOptions(string? ConfigPath, bool ShowHelp, bool ValidateOnly)
{
    public static CommandLineOptions Parse(string[] args)
    {
        string? configPath = null;
        var validateOnly = false;
        for (var index = 0; index < args.Length; index++)
        {
            var argument = args[index];
            if (string.Equals(argument, "--help", StringComparison.OrdinalIgnoreCase)
                || string.Equals(argument, "-h", StringComparison.OrdinalIgnoreCase))
            {
                return new CommandLineOptions(null, true, false);
            }

            if (string.Equals(argument, "--config", StringComparison.OrdinalIgnoreCase)
                || string.Equals(argument, "-ConfigPath", StringComparison.OrdinalIgnoreCase))
            {
                if (++index >= args.Length)
                    throw new ArgumentException("A path is required after --config.");
                configPath = args[index];
                continue;
            }

            if (string.Equals(argument, "--validate-only", StringComparison.OrdinalIgnoreCase))
            {
                validateOnly = true;
                continue;
            }

            throw new ArgumentException($"Unknown argument: {argument}");
        }

        return new CommandLineOptions(configPath, false, validateOnly);
    }
}
