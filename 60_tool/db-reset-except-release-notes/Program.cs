using Microsoft.Data.SqlClient;
using Microsoft.Extensions.Configuration;

return await RunAsync(args);

static async Task<int> RunAsync(string[] args)
{
    try
    {
        var options = CliOptions.Parse(args);
        var configPath = ResolveConfigPath(options.ConfigPath);
        var config = LoadConfig(configPath);
        var sourceConfiguration = LoadSourceConfiguration(config.SourceApiAppsettingsPath);
        var settings = ResolveEffectiveSettings(config, sourceConfiguration);
        var queryPath = Path.Combine(AppContext.BaseDirectory, "reset-db-except-release-notes.sql");
        var query = LoadQuery(queryPath);

        PrintSummary(settings, configPath, queryPath);

        if (!options.Yes)
        {
            Console.WriteLine();
            Console.Write("This will delete all data except release note publication and notification data. Type 'RESET' to continue: ");
            var confirmation = Console.ReadLine();
            if (!string.Equals(confirmation, "RESET", StringComparison.Ordinal))
            {
                Console.WriteLine("Canceled.");
                return 1;
            }
        }

        await ResetDatabasesAsync(settings, query, CancellationToken.None);
        Console.WriteLine("Database reset completed.");
        return 0;
    }
    catch (OperationCanceledException)
    {
        Console.Error.WriteLine("Database reset canceled.");
        return 1;
    }
    catch (Exception exception)
    {
        Console.Error.WriteLine($"Database reset failed: {exception.Message}");
        return 1;
    }
}

static string ResolveConfigPath(string? configPath)
{
    if (!string.IsNullOrWhiteSpace(configPath))
        return Path.GetFullPath(configPath);

    return Path.Combine(AppContext.BaseDirectory, "db-reset-except-release-notes.config.json");
}

static ResetToolConfig LoadConfig(string configPath)
{
    if (!File.Exists(configPath))
        throw new FileNotFoundException($"Config file was not found: {configPath}");

    var configuration = new ConfigurationBuilder()
        .AddJsonFile(configPath, optional: false, reloadOnChange: false)
        .Build();

    return configuration.Get<ResetToolConfig>()
        ?? throw new InvalidOperationException($"Failed to load config file: {configPath}");
}

static IConfigurationRoot LoadSourceConfiguration(string? sourceApiAppsettingsPath)
{
    if (string.IsNullOrWhiteSpace(sourceApiAppsettingsPath))
        return new ConfigurationBuilder().Build();

    var normalizedPath = Environment.ExpandEnvironmentVariables(sourceApiAppsettingsPath);
    if (!File.Exists(normalizedPath))
        return new ConfigurationBuilder().Build();

    return new ConfigurationBuilder()
        .AddJsonFile(normalizedPath, optional: false, reloadOnChange: false)
        .Build();
}

static EffectiveSettings ResolveEffectiveSettings(
    ResetToolConfig config,
    IConfiguration sourceConfiguration)
{
    var astralRecord = FirstNonEmpty(
        config.ConnectionStrings.SqlServer,
        sourceConfiguration.GetConnectionString("SqlServer"));
    var masterData = FirstNonEmpty(
        config.ConnectionStrings.MasterData,
        sourceConfiguration.GetConnectionString("MasterData"));
    var history = FirstNonEmpty(
        config.ConnectionStrings.History,
        sourceConfiguration.GetConnectionString("History"));

    if (string.IsNullOrWhiteSpace(astralRecord))
        throw new InvalidOperationException("ConnectionStrings:SqlServer could not be resolved.");
    if (string.IsNullOrWhiteSpace(masterData))
        throw new InvalidOperationException("ConnectionStrings:MasterData could not be resolved.");
    if (string.IsNullOrWhiteSpace(history))
        throw new InvalidOperationException("ConnectionStrings:History could not be resolved.");
    if (config.CommandTimeoutSeconds is < 1 or > 3600)
        throw new InvalidOperationException("commandTimeoutSeconds must be between 1 and 3600.");

    var targets = new[]
    {
        new DatabaseTarget("AstralRecord", astralRecord),
        new DatabaseTarget("MasterDataDB", masterData),
        new DatabaseTarget("HistoryDB", history),
    };

    var databaseNames = targets
        .Select(target => GetDatabaseName(target.ConnectionString))
        .ToArray();

    for (var index = 0; index < targets.Length; index++)
    {
        if (!string.Equals(databaseNames[index], targets[index].ExpectedDatabaseName, StringComparison.OrdinalIgnoreCase))
        {
            throw new InvalidOperationException(
                $"{targets[index].ExpectedDatabaseName} connection must target database '{targets[index].ExpectedDatabaseName}'.");
        }
    }

    if (databaseNames.Distinct(StringComparer.OrdinalIgnoreCase).Count() != databaseNames.Length)
        throw new InvalidOperationException("The three database connections must target distinct databases.");

    return new EffectiveSettings(
        targets,
        config.CommandTimeoutSeconds);
}

static string LoadQuery(string queryPath)
{
    if (!File.Exists(queryPath))
        throw new FileNotFoundException($"Reset query was not found: {queryPath}");

    var query = File.ReadAllText(queryPath);
    if (string.IsNullOrWhiteSpace(query))
        throw new InvalidOperationException($"Reset query is empty: {queryPath}");

    return query;
}

static async Task ResetDatabasesAsync(
    EffectiveSettings settings,
    string query,
    CancellationToken cancellationToken)
{
    var connections = new List<SqlConnection>();

    try
    {
        // Open every connection before executing the first reset so configuration
        // and connectivity errors do not leave only an earlier database reset.
        foreach (var target in settings.Targets)
        {
            var connection = new SqlConnection(target.ConnectionString);
            connections.Add(connection);
            await connection.OpenAsync(cancellationToken);

            if (!string.Equals(connection.Database, target.ExpectedDatabaseName, StringComparison.OrdinalIgnoreCase))
            {
                throw new InvalidOperationException(
                    $"Connection labeled {target.ExpectedDatabaseName} opened database '{connection.Database}'.");
            }
        }

        for (var index = 0; index < settings.Targets.Count; index++)
        {
            await ExecuteResetAsync(
                settings.Targets[index],
                connections[index],
                query,
                settings.CommandTimeoutSeconds,
                cancellationToken);
        }
    }
    finally
    {
        foreach (var connection in connections)
            await connection.DisposeAsync();
    }
}

static async Task ExecuteResetAsync(
    DatabaseTarget target,
    SqlConnection connection,
    string query,
    int commandTimeoutSeconds,
    CancellationToken cancellationToken)
{
    Console.WriteLine($"Resetting {target.ExpectedDatabaseName}...");

    await using var command = connection.CreateCommand();
    command.CommandText = query;
    command.CommandTimeout = commandTimeoutSeconds;

    await using var reader = await command.ExecuteReaderAsync(cancellationToken);
    if (!await reader.ReadAsync(cancellationToken))
        throw new InvalidOperationException($"Reset query returned no result for {target.ExpectedDatabaseName}.");

    var databaseName = reader.GetString(reader.GetOrdinal("database_name"));
    var preserved = reader.GetBoolean(reader.GetOrdinal("release_note_data_preserved"));
    var deletedRows = reader.GetInt64(reader.GetOrdinal("deleted_rows"));

    Console.WriteLine(
        $"{databaseName}: deletedRows={deletedRows}, releaseNoteDataPreserved={preserved}");
}

static void PrintSummary(
    EffectiveSettings settings,
    string configPath,
    string queryPath)
{
    Console.WriteLine("=== DB Reset Except Release Notes Tool ===");
    Console.WriteLine($"Config: {configPath}");
    Console.WriteLine($"Query: {queryPath}");
    foreach (var target in settings.Targets)
        Console.WriteLine($"{target.ExpectedDatabaseName}: {GetDatabaseName(target.ConnectionString)}");
    Console.WriteLine("Preserved: AstralRecord.dbo.release_note");
    Console.WriteLine("Preserved: AstralRecord.dbo.release_notification_outbox");
    Console.WriteLine($"Command timeout: {settings.CommandTimeoutSeconds} seconds");
}

static string GetDatabaseName(string connectionString)
{
    var builder = new SqlConnectionStringBuilder(connectionString);
    if (string.IsNullOrWhiteSpace(builder.InitialCatalog))
        throw new InvalidOperationException("Each connection string must include Database or Initial Catalog.");

    return builder.InitialCatalog;
}

static string? FirstNonEmpty(params string?[] values)
    => values.FirstOrDefault(value => !string.IsNullOrWhiteSpace(value));

internal sealed class CliOptions
{
    public string? ConfigPath { get; init; }
    public bool Yes { get; init; }

    public static CliOptions Parse(string[] args)
    {
        string? configPath = null;
        var yes = false;

        for (var index = 0; index < args.Length; index++)
        {
            switch (args[index])
            {
                case "--config":
                    if (index + 1 >= args.Length)
                        throw new ArgumentException("--config requires a value.");

                    configPath = args[++index];
                    break;

                case "--yes":
                    yes = true;
                    break;

                default:
                    throw new ArgumentException($"Unsupported argument: {args[index]}");
            }
        }

        return new CliOptions
        {
            ConfigPath = configPath,
            Yes = yes,
        };
    }
}

internal sealed class ResetToolConfig
{
    public string? SourceApiAppsettingsPath { get; init; }
    public ResetConnectionStringsConfig ConnectionStrings { get; init; } = new();
    public int CommandTimeoutSeconds { get; init; } = 600;
}

internal sealed class ResetConnectionStringsConfig
{
    public string? SqlServer { get; init; }
    public string? MasterData { get; init; }
    public string? History { get; init; }
}

internal sealed record DatabaseTarget(
    string ExpectedDatabaseName,
    string ConnectionString);

internal sealed record EffectiveSettings(
    IReadOnlyList<DatabaseTarget> Targets,
    int CommandTimeoutSeconds);
