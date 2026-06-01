using AstralRecordApi.Data;
using AstralRecordApi.Models;
using AstralRecordApi.Options;
using AstralRecordApi.Services;
using Microsoft.Data.SqlClient;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

var options = CliOptions.Parse(args);
var configPath = ResolveConfigPath(options.ConfigPath);
var config = LoadConfig(configPath);
var effectiveSettings = await ResolveEffectiveSettingsAsync(config, CancellationToken.None);

PrintSummary(effectiveSettings, configPath);

if (!options.Yes)
{
    Console.WriteLine();
    Console.Write("This will delete existing data and rebuild the databases. Type 'REBUILD' to continue: ");
    var confirmation = Console.ReadLine();
    if (!string.Equals(confirmation, "REBUILD", StringComparison.Ordinal))
    {
        Console.WriteLine("Canceled.");
        return 1;
    }
}

using var host = BuildHost(effectiveSettings);
using var scope = host.Services.CreateScope();
var services = scope.ServiceProvider;
var logger = services.GetRequiredService<ILoggerFactory>().CreateLogger("DbRebuildTool");

logger.LogInformation("Starting database rebuild.");

await DropDatabaseIfExistsAsync(effectiveSettings.ConnectionStrings.SqlServer, logger, CancellationToken.None);
await DropDatabaseIfExistsAsync(effectiveSettings.ConnectionStrings.MasterData, logger, CancellationToken.None);
await DropDatabaseIfExistsAsync(effectiveSettings.ConnectionStrings.History, logger, CancellationToken.None);

await EnsureCreatedAsync<AstralRecordDbContext>(services, "AstralRecord", logger, CancellationToken.None);
await EnsureCreatedAsync<MasterDataDbContext>(services, "MasterDataDB", logger, CancellationToken.None);
await EnsureCreatedAsync<HistoryDbContext>(services, "HistoryDB", logger, CancellationToken.None);

if (effectiveSettings.SeedMasterData)
{
    var seeder = services.GetRequiredService<IMasterDataSeeder>();
    var result = await seeder.RunAsync(
        MasterDataSeedTrigger.Manual,
        MasterDataSeedMode.Rebuild,
        CancellationToken.None);

    ReportSeedResult(result, logger);

    if (!string.Equals(result.Status, "SUCCEEDED", StringComparison.OrdinalIgnoreCase))
        return 1;
}

logger.LogInformation("Database rebuild completed.");
return 0;

static string ResolveConfigPath(string? configPath)
{
    if (!string.IsNullOrWhiteSpace(configPath))
        return Path.GetFullPath(configPath);

    return Path.Combine(AppContext.BaseDirectory, "db-rebuild.config.json");
}

static RebuildToolConfig LoadConfig(string configPath)
{
    if (!File.Exists(configPath))
        throw new FileNotFoundException($"Config file was not found: {configPath}");

    var configuration = new ConfigurationBuilder()
        .AddJsonFile(configPath, optional: false, reloadOnChange: false)
        .Build();

    return configuration.Get<RebuildToolConfig>()
        ?? throw new InvalidOperationException($"Failed to load config file: {configPath}");
}

static async Task<EffectiveSettings> ResolveEffectiveSettingsAsync(
    RebuildToolConfig config,
    CancellationToken cancellationToken)
{
    var sourceConfiguration = await LoadSourceConfigurationAsync(config.SourceApiAppsettingsPath, cancellationToken);

    var sqlServer = FirstNonEmpty(
        config.ConnectionStrings.SqlServer,
        sourceConfiguration.GetConnectionString("SqlServer"));
    var masterData = FirstNonEmpty(
        config.ConnectionStrings.MasterData,
        sourceConfiguration.GetConnectionString("MasterData"));
    var history = FirstNonEmpty(
        config.ConnectionStrings.History,
        sourceConfiguration.GetConnectionString("History"));
    var fileDatabaseRootPath = FirstNonEmpty(
        config.FileDatabase.RootPath,
        sourceConfiguration["FileDatabase:RootPath"]);
    var systemUserIdText = FirstNonEmpty(
        config.MasterData.SystemUserId,
        sourceConfiguration["MasterData:SystemUserId"]);

    if (string.IsNullOrWhiteSpace(sqlServer))
        throw new InvalidOperationException("ConnectionStrings:SqlServer could not be resolved.");
    if (string.IsNullOrWhiteSpace(masterData))
        throw new InvalidOperationException("ConnectionStrings:MasterData could not be resolved.");
    if (string.IsNullOrWhiteSpace(history))
        throw new InvalidOperationException("ConnectionStrings:History could not be resolved.");

    if (config.SeedMasterData && string.IsNullOrWhiteSpace(fileDatabaseRootPath))
        throw new InvalidOperationException("FileDatabase:RootPath is required when SeedMasterData is true.");

    var systemUserId = Guid.TryParse(systemUserIdText, out var parsedSystemUserId)
        ? parsedSystemUserId
        : new Guid("00000000-0000-0000-0000-000000000001");

    return new EffectiveSettings(
        new EffectiveConnectionStrings(sqlServer, masterData, history),
        fileDatabaseRootPath ?? string.Empty,
        systemUserId,
        config.SeedMasterData);
}

static async Task<IConfigurationRoot> LoadSourceConfigurationAsync(
    string? sourceApiAppsettingsPath,
    CancellationToken cancellationToken)
{
    if (string.IsNullOrWhiteSpace(sourceApiAppsettingsPath))
        return new ConfigurationBuilder().Build();

    var normalizedPath = Environment.ExpandEnvironmentVariables(sourceApiAppsettingsPath);
    if (!File.Exists(normalizedPath))
        throw new FileNotFoundException($"sourceApiAppsettingsPath was not found: {normalizedPath}");

    await using var stream = File.OpenRead(normalizedPath);

    return new ConfigurationBuilder()
        .AddJsonStream(stream)
        .Build();
}

static IHost BuildHost(EffectiveSettings settings)
{
    var builder = Host.CreateApplicationBuilder();
    builder.Logging.ClearProviders();
    builder.Logging.AddSimpleConsole(logging =>
    {
        logging.TimestampFormat = "yyyy-MM-dd HH:mm:ss ";
    });

    builder.Services.Configure<FileDatabaseOptions>(options =>
    {
        options.RootPath = settings.FileDatabaseRootPath;
    });

    builder.Services.Configure<MasterDataOptions>(options =>
    {
        options.AutoSeedOnStartup = false;
        options.SystemUserId = settings.SystemUserId;
    });

    builder.Services.AddDbContext<AstralRecordDbContext>(db => db.UseSqlServer(settings.ConnectionStrings.SqlServer));
    builder.Services.AddDbContext<MasterDataDbContext>(db => db.UseSqlServer(settings.ConnectionStrings.MasterData));
    builder.Services.AddDbContext<HistoryDbContext>(db => db.UseSqlServer(settings.ConnectionStrings.History));
    builder.Services.AddScoped<IMasterDataSeeder, MasterDataSeeder>();

    return builder.Build();
}

static async Task DropDatabaseIfExistsAsync(
    string connectionString,
    ILogger logger,
    CancellationToken cancellationToken)
{
    var builder = new SqlConnectionStringBuilder(connectionString);
    var databaseName = builder.InitialCatalog;
    if (string.IsNullOrWhiteSpace(databaseName))
        throw new InvalidOperationException("The connection string must include Database or Initial Catalog.");

    var masterBuilder = new SqlConnectionStringBuilder(connectionString)
    {
        InitialCatalog = "master"
    };

    await using var connection = new SqlConnection(masterBuilder.ConnectionString);
    await connection.OpenAsync(cancellationToken);

    await using var command = connection.CreateCommand();
    command.CommandText = """
        IF DB_ID(@databaseName) IS NOT NULL
        BEGIN
            DECLARE @sql nvarchar(max);
            SET @sql = N'ALTER DATABASE ' + QUOTENAME(@databaseName) + N' SET SINGLE_USER WITH ROLLBACK IMMEDIATE';
            EXEC(@sql);
            SET @sql = N'DROP DATABASE ' + QUOTENAME(@databaseName);
            EXEC(@sql);
        END
        """;
    command.Parameters.AddWithValue("@databaseName", databaseName);

    logger.LogInformation("Dropping database: {DatabaseName}", databaseName);
    await command.ExecuteNonQueryAsync(cancellationToken);
}

static async Task EnsureCreatedAsync<TContext>(
    IServiceProvider services,
    string label,
    ILogger logger,
    CancellationToken cancellationToken)
    where TContext : DbContext
{
    await using var scope = services.CreateAsyncScope();
    var dbContext = scope.ServiceProvider.GetRequiredService<TContext>();

    logger.LogInformation("Creating database schema: {Label}", label);
    await dbContext.Database.EnsureCreatedAsync(cancellationToken);
}

static void ReportSeedResult(MasterDataSeedResultResponse result, ILogger logger)
{
    if (string.Equals(result.Status, "SUCCEEDED", StringComparison.OrdinalIgnoreCase))
    {
        logger.LogInformation(
            "MasterData seed completed: files={FileCount}, upserted={UpsertedCount}, deleted={DeletedCount}, skipped={SkippedCount}",
            result.FileCount,
            result.UpsertedCount,
            result.DeletedCount,
            result.SkippedCount);

        foreach (var warning in result.Warnings)
            logger.LogWarning("MasterData seed warning: {Warning}", warning);

        return;
    }

    logger.LogError("MasterData seed failed: {ErrorMessage}", result.ErrorMessage);
    foreach (var warning in result.Warnings)
        logger.LogWarning("MasterData seed warning: {Warning}", warning);
}

static void PrintSummary(EffectiveSettings settings, string configPath)
{
    Console.WriteLine("=== DB Rebuild Tool ===");
    Console.WriteLine($"Config: {configPath}");
    Console.WriteLine($"AstralRecord DB: {GetDatabaseName(settings.ConnectionStrings.SqlServer)}");
    Console.WriteLine($"MasterData DB: {GetDatabaseName(settings.ConnectionStrings.MasterData)}");
    Console.WriteLine($"History DB: {GetDatabaseName(settings.ConnectionStrings.History)}");
    Console.WriteLine($"MasterData seed: {(settings.SeedMasterData ? "enabled" : "disabled")}");

    if (settings.SeedMasterData)
        Console.WriteLine($"FileDatabase root: {settings.FileDatabaseRootPath}");
}

static string GetDatabaseName(string connectionString)
{
    var builder = new SqlConnectionStringBuilder(connectionString);
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

        for (var i = 0; i < args.Length; i++)
        {
            var arg = args[i];
            switch (arg)
            {
                case "--config":
                    if (i + 1 >= args.Length)
                        throw new ArgumentException("--config requires a value.");

                    configPath = args[++i];
                    break;

                case "--yes":
                    yes = true;
                    break;

                default:
                    throw new ArgumentException($"Unsupported argument: {arg}");
            }
        }

        return new CliOptions
        {
            ConfigPath = configPath,
            Yes = yes
        };
    }
}

internal sealed class RebuildToolConfig
{
    public string? SourceApiAppsettingsPath { get; init; }
    public RebuildConnectionStringsConfig ConnectionStrings { get; init; } = new();
    public RebuildFileDatabaseConfig FileDatabase { get; init; } = new();
    public RebuildMasterDataConfig MasterData { get; init; } = new();
    public bool SeedMasterData { get; init; } = true;
}

internal sealed class RebuildConnectionStringsConfig
{
    public string? SqlServer { get; init; }
    public string? MasterData { get; init; }
    public string? History { get; init; }
}

internal sealed class RebuildFileDatabaseConfig
{
    public string? RootPath { get; init; }
}

internal sealed class RebuildMasterDataConfig
{
    public string? SystemUserId { get; init; }
}

internal sealed record EffectiveSettings(
    EffectiveConnectionStrings ConnectionStrings,
    string FileDatabaseRootPath,
    Guid SystemUserId,
    bool SeedMasterData);

internal sealed record EffectiveConnectionStrings(
    string SqlServer,
    string MasterData,
    string History);
