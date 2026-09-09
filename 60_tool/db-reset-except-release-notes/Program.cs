using System.Data;
using System.Text;
using System.Text.RegularExpressions;
using Microsoft.Data.SqlClient;
using Microsoft.Extensions.Configuration;

return await RunAsync(args);

static async Task<int> RunAsync(string[] args)
{
    ReleaseNoteBackup? backup = null;
    DatabaseTarget? astralRecordTarget = null;
    SqlConnection? astralRecordIsolation = null;
    ApplicationOfflineGuard? applicationOfflineGuard = null;
    string? maintenanceOperationId = null;
    var rebuildStarted = false;

    try
    {
        var options = CliOptions.Parse(args);
        maintenanceOperationId = ResolveMaintenanceOperationId(options);
        var configPath = ResolveConfigPath(options.ConfigPath);
        var config = LoadConfig(configPath);
        var sourceConfiguration = LoadSourceConfiguration(config.SourceApiAppsettingsPath);
        var settings = ResolveEffectiveSettings(
            config,
            sourceConfiguration,
            options.IntegrationTestSuffix,
            options.IntegrationApplicationRoots);
        astralRecordTarget = settings.AstralRecord;
        var schemas = LoadSchemaScripts(settings.Targets);

        if (options.FailAfterAstralRecordRebuild && string.IsNullOrWhiteSpace(options.IntegrationTestSuffix))
            throw new InvalidOperationException("Failure injection is available only with --integration-test-suffix.");

        PrintSummary(settings, configPath, schemas, options.RestoreBackupDatabaseName);

        if (!options.Yes)
        {
            Console.WriteLine();
            Console.Write(
                "This will rebuild all databases from the latest init.sql files while preserving release note data. Type 'RESET' to continue: ");
            var confirmation = Console.ReadLine();
            if (!string.Equals(confirmation, "RESET", StringComparison.Ordinal))
            {
                Console.WriteLine("Canceled.");
                return 1;
            }
        }

        applicationOfflineGuard = await EnterApplicationOfflineAsync(
            settings.ApplicationOffline,
            settings.AstralRecord,
            maintenanceOperationId,
            options.IsMaintenanceResume,
            options.IsMarkerOnlyResume,
            settings.CommandTimeoutSeconds,
            CancellationToken.None);
        await ValidateConnectivityAsync(settings, CancellationToken.None);
        if (string.IsNullOrWhiteSpace(options.RestoreBackupDatabaseName))
        {
            await EnsureNoPendingBackupsAsync(
                settings.AstralRecord,
                settings.CommandTimeoutSeconds,
                CancellationToken.None);
            if (string.IsNullOrWhiteSpace(options.ResumeOperationId))
            {
                backup = await BackupReleaseNotesAsync(
                    settings.AstralRecord,
                    maintenanceOperationId,
                    settings.CommandTimeoutSeconds,
                    CancellationToken.None);
            }
            else
            {
                Console.WriteLine(
                    "Resuming a failed rebuild that had no release note backup; the partial databases will be rebuilt again.");
            }
        }
        else
        {
            backup = await LoadExistingBackupAsync(
                settings.AstralRecord,
                options.RestoreBackupDatabaseName,
                settings.CommandTimeoutSeconds,
                CancellationToken.None);
        }

        foreach (var schema in schemas)
        {
            rebuildStarted = true;
            var isolation = await RebuildDatabaseAsync(
                schema,
                settings.CommandTimeoutSeconds,
                CancellationToken.None);
            if (isolation is not null)
            {
                astralRecordIsolation = isolation;
                if (options.FailAfterAstralRecordRebuild)
                    throw new InvalidOperationException("Injected integration-test failure after AstralRecord rebuild.");
            }
        }

        if (backup is not null)
        {
            await RestoreReleaseNotesAsync(
                astralRecordIsolation
                ?? throw new InvalidOperationException("AstralRecord maintenance isolation was not acquired."),
                settings.AstralRecord.ExpectedDatabaseName,
                backup,
                settings.CommandTimeoutSeconds,
                CancellationToken.None);
            await DropDatabaseIfExistsAsync(
                settings.AstralRecord.ConnectionString,
                backup.DatabaseName,
                settings.CommandTimeoutSeconds,
                CancellationToken.None);
            Console.WriteLine($"Removed completed backup database: {backup.DatabaseName}");
            backup = null;
        }

        if (astralRecordIsolation is not null)
            await ReleaseMaintenanceIsolationAsync(
                astralRecordIsolation,
                settings.AstralRecord.ExpectedDatabaseName,
                settings.CommandTimeoutSeconds,
                CancellationToken.None);

        await DisposeConnectionAsync(astralRecordIsolation);
        astralRecordIsolation = null;
        await ExitApplicationOfflineAsync(applicationOfflineGuard, CancellationToken.None);
        if (applicationOfflineGuard is not null)
            await applicationOfflineGuard.DisposeAsync();
        applicationOfflineGuard = null;
        backup = null;

        Console.WriteLine("Database schema rebuild completed.");
        return 0;
    }
    catch (OperationCanceledException)
    {
        Console.Error.WriteLine("Database schema rebuild canceled.");
        await IsolateFailedDatabaseAsync(
            astralRecordTarget,
            astralRecordIsolation,
            rebuildStarted,
            CancellationToken.None);
        if (!rebuildStarted && applicationOfflineGuard?.HasAdoptedMarkers != true)
            await TryExitApplicationOfflineAsync(applicationOfflineGuard);
        else
            PrintRetainedOfflineMessage(applicationOfflineGuard);
        PrintBackupRecoveryMessage(
            backup,
            astralRecordTarget,
            backup is not null || rebuildStarted ? maintenanceOperationId : null);
        return 1;
    }
    catch (Exception exception)
    {
        Console.Error.WriteLine($"Database schema rebuild failed: {exception.Message}");
        await IsolateFailedDatabaseAsync(
            astralRecordTarget,
            astralRecordIsolation,
            rebuildStarted,
            CancellationToken.None);
        if (!rebuildStarted && applicationOfflineGuard?.HasAdoptedMarkers != true)
            await TryExitApplicationOfflineAsync(applicationOfflineGuard);
        else
            PrintRetainedOfflineMessage(applicationOfflineGuard);
        PrintBackupRecoveryMessage(
            backup,
            astralRecordTarget,
            backup is not null || rebuildStarted ? maintenanceOperationId : null);
        return 1;
    }
    finally
    {
        await DisposeConnectionAsync(astralRecordIsolation);
        if (applicationOfflineGuard is not null)
            await applicationOfflineGuard.DisposeAsync();
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
    IConfiguration sourceConfiguration,
    string? integrationTestSuffix,
    IReadOnlyList<string> integrationApplicationRoots)
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

    ValidateIntegrationTestSuffix(integrationTestSuffix);

    var targets = new[]
    {
        CreateDatabaseTarget("AstralRecord", astralRecord, integrationTestSuffix),
        CreateDatabaseTarget("MasterDataDB", masterData, integrationTestSuffix),
        CreateDatabaseTarget("HistoryDB", history, integrationTestSuffix),
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

    var applicationOffline = ResolveApplicationOfflineSettings(
        config.ApplicationOffline,
        integrationTestSuffix,
        integrationApplicationRoots);
    return new EffectiveSettings(targets, applicationOffline, config.CommandTimeoutSeconds);
}

static ApplicationOfflineSettings ResolveApplicationOfflineSettings(
    ApplicationOfflineConfig config,
    string? integrationTestSuffix,
    IReadOnlyList<string> integrationApplicationRoots)
{
    if (config.WaitSeconds is < 0 or > 60)
        throw new InvalidOperationException("applicationOffline.waitSeconds must be between 0 and 60.");

    if (!string.IsNullOrWhiteSpace(integrationTestSuffix))
    {
        var testRoots = NormalizeApplicationRoots(integrationApplicationRoots);
        return new ApplicationOfflineSettings(testRoots, 0);
    }

    if (integrationApplicationRoots.Count > 0)
    {
        throw new InvalidOperationException(
            "--integration-app-root is available only with --integration-test-suffix.");
    }

    var roots = NormalizeApplicationRoots(config.Roots);
    if (roots.Count == 0)
    {
        throw new InvalidOperationException(
            "applicationOffline.roots must include the API and Web application roots for a production reset.");
    }

    return new ApplicationOfflineSettings(roots, config.WaitSeconds);
}

static IReadOnlyList<string> NormalizeApplicationRoots(IEnumerable<string> configuredRoots)
    => configuredRoots
        .Where(root => !string.IsNullOrWhiteSpace(root))
        .Select(root => Path.GetFullPath(Environment.ExpandEnvironmentVariables(root)))
        .Distinct(StringComparer.OrdinalIgnoreCase)
        .ToArray();

static DatabaseTarget CreateDatabaseTarget(
    string baseDatabaseName,
    string connectionString,
    string? integrationTestSuffix)
{
    var expectedDatabaseName = baseDatabaseName + (integrationTestSuffix ?? string.Empty);
    var builder = new SqlConnectionStringBuilder(connectionString)
    {
        InitialCatalog = expectedDatabaseName,
    };
    return new DatabaseTarget(baseDatabaseName, expectedDatabaseName, builder.ConnectionString);
}

static void ValidateIntegrationTestSuffix(string? integrationTestSuffix)
{
    if (string.IsNullOrWhiteSpace(integrationTestSuffix))
        return;

    if (!Regex.IsMatch(integrationTestSuffix, @"^_Integration_[0-9a-f]{8,32}$", RegexOptions.CultureInvariant))
    {
        throw new InvalidOperationException(
            "--integration-test-suffix must match _Integration_[0-9a-f]{8,32}.");
    }
}

static IReadOnlyList<DatabaseSchema> LoadSchemaScripts(IReadOnlyList<DatabaseTarget> targets)
{
    var schemaRoot = Path.Combine(AppContext.BaseDirectory, "schemas");
    var schemas = new List<DatabaseSchema>(targets.Count);

    foreach (var target in targets)
    {
        var scriptPath = Path.Combine(schemaRoot, target.BaseDatabaseName, "init.sql");
        if (!File.Exists(scriptPath))
            throw new FileNotFoundException($"Initialization script was not found: {scriptPath}");

        var script = File.ReadAllText(scriptPath, Encoding.UTF8);
        if (string.IsNullOrWhiteSpace(script))
            throw new InvalidOperationException($"Initialization script is empty: {scriptPath}");

        ValidateSchemaScript(target.BaseDatabaseName, script, scriptPath);
        if (!string.Equals(target.BaseDatabaseName, target.ExpectedDatabaseName, StringComparison.Ordinal))
        {
            script = script.Replace(
                QuoteIdentifier(target.BaseDatabaseName),
                QuoteIdentifier(target.ExpectedDatabaseName),
                StringComparison.Ordinal);
            script = script.Replace(
                $"N'{target.BaseDatabaseName}'",
                $"N'{target.ExpectedDatabaseName}'",
                StringComparison.Ordinal);
        }
        schemas.Add(new DatabaseSchema(target, scriptPath, script));
    }

    return schemas;
}

static void ValidateSchemaScript(string databaseName, string script, string scriptPath)
{
    var escapedName = Regex.Escape(databaseName);
    if (!Regex.IsMatch(script, $@"(?im)^\s*CREATE\s+DATABASE\s+\[{escapedName}\]\s*;"))
        throw new InvalidOperationException($"Initialization script does not create {databaseName}: {scriptPath}");
    if (!Regex.IsMatch(script, $@"(?im)^\s*USE\s+\[{escapedName}\]\s*;"))
        throw new InvalidOperationException($"Initialization script does not select {databaseName}: {scriptPath}");
}

static async Task<ApplicationOfflineGuard?> EnterApplicationOfflineAsync(
    ApplicationOfflineSettings settings,
    DatabaseTarget target,
    string operationId,
    bool allowExistingOwnedMarkers,
    bool requireExistingOwnedMarkers,
    int commandTimeoutSeconds,
    CancellationToken cancellationToken)
{
    if (settings.Roots.Count == 0)
        return null;

    var paths = settings.Roots
        .Select(root => Path.Combine(root, "app_offline.htm"))
        .ToArray();
    foreach (var root in settings.Roots)
    {
        if (!Directory.Exists(root))
            throw new DirectoryNotFoundException($"Application root was not found: {root}");
    }

    var expectedContent = MaintenanceFiles.CreateContent(operationId);
    var createdPaths = new List<string>();
    SqlConnection? lockConnection = null;
    ResetLockHandle? resetLock = null;
    try
    {
        lockConnection = CreateMasterConnection(target.ConnectionString);
        await lockConnection.OpenAsync(cancellationToken);
        var lockResource = GetResetLockResource(target.ExpectedDatabaseName);
        await AcquireResetLockAsync(
            lockConnection,
            lockResource,
            commandTimeoutSeconds,
            cancellationToken);
        resetLock = new ResetLockHandle(lockConnection, lockResource, commandTimeoutSeconds);
        lockConnection = null;

        var hasAdoptedMarkers = false;
        foreach (var path in paths)
        {
            if (File.Exists(path))
            {
                var existingContent = await File.ReadAllTextAsync(path, Encoding.UTF8, cancellationToken);
                if (!allowExistingOwnedMarkers
                    || !string.Equals(existingContent, expectedContent, StringComparison.Ordinal))
                {
                    throw new InvalidOperationException(
                        $"Application is already offline for another operation: {path}");
                }

                hasAdoptedMarkers = true;
                continue;
            }

            if (requireExistingOwnedMarkers)
            {
                throw new InvalidOperationException(
                    $"The failed-operation marker required by --resume-operation was not found: {path}");
            }

            await CreateOwnedMarkerAsync(path, expectedContent, cancellationToken);
            createdPaths.Add(path);
        }

        if (settings.WaitSeconds > 0)
            await Task.Delay(TimeSpan.FromSeconds(settings.WaitSeconds), cancellationToken);

        foreach (var path in paths)
        {
            if (!File.Exists(path))
                throw new IOException($"Application offline marker disappeared: {path}");
            var content = await File.ReadAllTextAsync(path, Encoding.UTF8, cancellationToken);
            if (!string.Equals(content, expectedContent, StringComparison.Ordinal))
                throw new IOException($"Application offline marker was replaced: {path}");
        }

        Console.WriteLine($"API/Web application offline markers are active (operation={operationId}).");
        var guard = new ApplicationOfflineGuard(
            paths,
            expectedContent,
            operationId,
            hasAdoptedMarkers,
            resetLock);
        resetLock = null;
        return guard;
    }
    catch
    {
        await TryDeleteOwnedMarkersAsync(createdPaths, expectedContent);
        if (resetLock is not null)
            await resetLock.DisposeAsync();
        if (lockConnection is not null)
            await lockConnection.DisposeAsync();

        throw;
    }
}

static async Task AcquireResetLockAsync(
    SqlConnection connection,
    string lockResource,
    int commandTimeoutSeconds,
    CancellationToken cancellationToken)
{
    const string sql = """
        DECLARE @result int;
        EXEC @result = [sys].[sp_getapplock]
            @Resource = @resource,
            @LockMode = N'Exclusive',
            @LockOwner = N'Session',
            @LockTimeout = 0,
            @DbPrincipal = N'public';
        SELECT @result;
        """;

    await using var command = connection.CreateCommand();
    command.CommandText = sql;
    command.CommandTimeout = commandTimeoutSeconds;
    command.Parameters.AddWithValue("@resource", lockResource);
    var result = Convert.ToInt32(await command.ExecuteScalarAsync(cancellationToken));
    if (result < 0)
    {
        throw new InvalidOperationException(
            $"Another database reset or recovery operation is already running (lock result={result}).");
    }
}

static async Task CreateOwnedMarkerAsync(
    string path,
    string content,
    CancellationToken cancellationToken)
{
    await using var stream = new FileStream(
        path,
        FileMode.CreateNew,
        FileAccess.Write,
        FileShare.Read,
        bufferSize: 4096,
        useAsync: true);
    await using var writer = new StreamWriter(stream, new UTF8Encoding(encoderShouldEmitUTF8Identifier: false));
    await writer.WriteAsync(content.AsMemory(), cancellationToken);
    await writer.FlushAsync(cancellationToken);
}

static async Task TryDeleteOwnedMarkersAsync(
    IEnumerable<string> paths,
    string expectedContent)
{
    foreach (var path in paths)
    {
        try
        {
            if (!File.Exists(path))
                continue;
            var content = await File.ReadAllTextAsync(path, Encoding.UTF8, CancellationToken.None);
            if (string.Equals(content, expectedContent, StringComparison.Ordinal))
                File.Delete(path);
        }
        catch
        {
            // The original failure is more useful; a remaining marker keeps the application safe.
        }
    }
}

static async Task ExitApplicationOfflineAsync(
    ApplicationOfflineGuard? guard,
    CancellationToken cancellationToken)
{
    if (guard is null)
        return;

    foreach (var path in guard.Paths)
    {
        if (!File.Exists(path))
            throw new IOException($"Application offline marker disappeared: {path}");

        var content = await File.ReadAllTextAsync(path, Encoding.UTF8, cancellationToken);
        if (!string.Equals(content, guard.ExpectedContent, StringComparison.Ordinal))
            throw new IOException($"Refusing to remove an application offline marker owned by another operation: {path}");
    }

    foreach (var path in guard.Paths)
        File.Delete(path);

    Console.WriteLine("API/Web application offline markers were removed.");
}

static async Task TryExitApplicationOfflineAsync(ApplicationOfflineGuard? guard)
{
    try
    {
        await ExitApplicationOfflineAsync(guard, CancellationToken.None);
    }
    catch (Exception exception)
    {
        Console.Error.WriteLine($"Failed to remove application offline marker: {exception.Message}");
    }
}

static void PrintRetainedOfflineMessage(ApplicationOfflineGuard? guard)
{
    if (guard is null)
        return;

    Console.Error.WriteLine(
        $"API/Web application offline markers were retained until database recovery succeeds (operation={guard.OperationId}).");
    foreach (var path in guard.Paths)
        Console.Error.WriteLine($"Retained offline marker: {path}");
}

static async Task ValidateConnectivityAsync(EffectiveSettings settings, CancellationToken cancellationToken)
{
    foreach (var target in settings.Targets)
    {
        await using var connection = CreateMasterConnection(target.ConnectionString);
        await connection.OpenAsync(cancellationToken);
        Console.WriteLine($"Connection ready: {target.ExpectedDatabaseName}");
    }
}

static async Task EnsureNoPendingBackupsAsync(
    DatabaseTarget target,
    int commandTimeoutSeconds,
    CancellationToken cancellationToken)
{
    await using var connection = CreateMasterConnection(target.ConnectionString);
    await connection.OpenAsync(cancellationToken);
    var backupNames = await FindPendingBackupNamesAsync(
        connection,
        target.ExpectedDatabaseName,
        commandTimeoutSeconds,
        cancellationToken);
    if (backupNames.Count == 0)
        return;

    throw new InvalidOperationException(
        "Unfinished release note backup database detected. Run recovery with --restore-backup before starting a new reset: " +
        string.Join(", ", backupNames));
}

static async Task<IReadOnlyList<string>> FindPendingBackupNamesAsync(
    SqlConnection connection,
    string sourceDatabaseName,
    int commandTimeoutSeconds,
    CancellationToken cancellationToken)
{
    const string sql = """
        SELECT [name]
        FROM [sys].[databases]
        WHERE LEFT([name], LEN(@prefix)) = @prefix
        ORDER BY [create_date], [name];
        """;

    await using var command = connection.CreateCommand();
    command.CommandText = sql;
    command.CommandTimeout = commandTimeoutSeconds;
    command.Parameters.AddWithValue("@prefix", GetBackupDatabasePrefix(sourceDatabaseName));
    var names = new List<string>();
    await using var reader = await command.ExecuteReaderAsync(cancellationToken);
    while (await reader.ReadAsync(cancellationToken))
        names.Add(reader.GetString(0));
    return names;
}

static async Task<ReleaseNoteBackup> LoadExistingBackupAsync(
    DatabaseTarget target,
    string backupDatabaseName,
    int commandTimeoutSeconds,
    CancellationToken cancellationToken)
{
    var backupPrefix = GetBackupDatabasePrefix(target.ExpectedDatabaseName);
    if (!backupDatabaseName.StartsWith(backupPrefix, StringComparison.Ordinal)
        || backupDatabaseName.Length > 128)
    {
        throw new InvalidOperationException(
            $"Recovery database name must start with {backupPrefix}.");
    }

    await using var connection = CreateMasterConnection(target.ConnectionString);
    await connection.OpenAsync(cancellationToken);
    var pendingBackupNames = await FindPendingBackupNamesAsync(
        connection,
        target.ExpectedDatabaseName,
        commandTimeoutSeconds,
        cancellationToken);
    if (!pendingBackupNames.Contains(backupDatabaseName, StringComparer.Ordinal))
        throw new InvalidOperationException($"Recovery database was not found: {backupDatabaseName}");

    var quotedBackupDatabaseName = QuoteIdentifier(backupDatabaseName);
    var releaseNoteCount = await CountRowsAsync(
        connection,
        $"{quotedBackupDatabaseName}.[dbo].[release_note]",
        commandTimeoutSeconds,
        cancellationToken);
    var outboxCount = await CountRowsAsync(
        connection,
        $"{quotedBackupDatabaseName}.[dbo].[release_notification_outbox]",
        commandTimeoutSeconds,
        cancellationToken);

    Console.WriteLine(
        $"Using recovery database: {backupDatabaseName} (releaseNoteRows={releaseNoteCount}, outboxRows={outboxCount})");
    return new ReleaseNoteBackup(backupDatabaseName, releaseNoteCount, outboxCount);
}

static async Task<ReleaseNoteBackup?> BackupReleaseNotesAsync(
    DatabaseTarget target,
    string operationId,
    int commandTimeoutSeconds,
    CancellationToken cancellationToken)
{
    await using var connection = CreateMasterConnection(target.ConnectionString);
    await connection.OpenAsync(cancellationToken);

    var tableState = await GetReleaseTableStateAsync(
        connection,
        target.ExpectedDatabaseName,
        commandTimeoutSeconds,
        cancellationToken);
    if (!tableState.DatabaseExists)
    {
        Console.WriteLine("AstralRecord does not exist; there is no release note data to preserve.");
        return null;
    }

    if (!tableState.ReleaseNoteExists && !tableState.OutboxExists)
    {
        Console.WriteLine("Release note tables do not exist; there is no release note data to preserve.");
        return null;
    }

    if (!tableState.ReleaseNoteExists || !tableState.OutboxExists)
        throw new InvalidOperationException("Release note tables are incomplete; refusing to rebuild without a complete backup.");

    var backupDatabaseName = CreateBackupDatabaseName(target.ExpectedDatabaseName, operationId);
    var quotedBackupDatabaseName = QuoteIdentifier(backupDatabaseName);
    var quotedSourceDatabaseName = QuoteIdentifier(target.ExpectedDatabaseName);

    try
    {
        await ExecuteNonQueryAsync(
            connection,
            $"CREATE DATABASE {quotedBackupDatabaseName};",
            commandTimeoutSeconds,
            cancellationToken);

        long releaseNoteCount;
        long outboxCount;
        await using (var transaction = (SqlTransaction)await connection.BeginTransactionAsync(
                         IsolationLevel.Serializable,
                         cancellationToken))
        {
            try
            {
                var acquireSourceLocksSql = $"""
                    SELECT COUNT_BIG(*)
                    FROM {quotedSourceDatabaseName}.[dbo].[release_note] WITH (TABLOCKX, HOLDLOCK);

                    SELECT COUNT_BIG(*)
                    FROM {quotedSourceDatabaseName}.[dbo].[release_notification_outbox] WITH (TABLOCKX, HOLDLOCK);
                    """;
                await ExecuteNonQueryAsync(
                    connection,
                    acquireSourceLocksSql,
                    commandTimeoutSeconds,
                    cancellationToken,
                    transaction);

                var backupSql = $"""
                    SELECT *
                    INTO {quotedBackupDatabaseName}.[dbo].[release_note]
                    FROM {quotedSourceDatabaseName}.[dbo].[release_note] WITH (TABLOCKX, HOLDLOCK);

                    SELECT *
                    INTO {quotedBackupDatabaseName}.[dbo].[release_notification_outbox]
                    FROM {quotedSourceDatabaseName}.[dbo].[release_notification_outbox] WITH (TABLOCKX, HOLDLOCK);
                    """;
                await ExecuteNonQueryAsync(
                    connection,
                    backupSql,
                    commandTimeoutSeconds,
                    cancellationToken,
                    transaction);

                releaseNoteCount = await CountRowsAsync(
                    connection,
                    $"{quotedBackupDatabaseName}.[dbo].[release_note]",
                    commandTimeoutSeconds,
                    cancellationToken,
                    transaction);
                outboxCount = await CountRowsAsync(
                    connection,
                    $"{quotedBackupDatabaseName}.[dbo].[release_notification_outbox]",
                    commandTimeoutSeconds,
                    cancellationToken,
                    transaction);
                await transaction.CommitAsync(cancellationToken);
            }
            catch
            {
                await transaction.RollbackAsync(cancellationToken);
                throw;
            }
        }

        Console.WriteLine(
            $"Release note backup created: {backupDatabaseName} (releaseNoteRows={releaseNoteCount}, outboxRows={outboxCount})");
        return new ReleaseNoteBackup(backupDatabaseName, releaseNoteCount, outboxCount);
    }
    catch
    {
        await TryDropIncompleteBackupAsync(
            target.ConnectionString,
            backupDatabaseName,
            commandTimeoutSeconds,
            cancellationToken);
        throw;
    }
}

static async Task<ReleaseTableState> GetReleaseTableStateAsync(
    SqlConnection connection,
    string databaseName,
    int commandTimeoutSeconds,
    CancellationToken cancellationToken)
{
    var quotedDatabaseName = QuoteIdentifier(databaseName);
    var sql = $"""
        SELECT
            CASE WHEN DB_ID(@databaseName) IS NULL THEN CAST(0 AS bit) ELSE CAST(1 AS bit) END,
            CASE WHEN OBJECT_ID(N'{quotedDatabaseName}.[dbo].[release_note]', N'U') IS NULL THEN CAST(0 AS bit) ELSE CAST(1 AS bit) END,
            CASE WHEN OBJECT_ID(N'{quotedDatabaseName}.[dbo].[release_notification_outbox]', N'U') IS NULL THEN CAST(0 AS bit) ELSE CAST(1 AS bit) END;
        """;

    await using var command = connection.CreateCommand();
    command.CommandText = sql;
    command.CommandTimeout = commandTimeoutSeconds;
    command.Parameters.AddWithValue("@databaseName", databaseName);
    await using var reader = await command.ExecuteReaderAsync(cancellationToken);
    if (!await reader.ReadAsync(cancellationToken))
        throw new InvalidOperationException("Release note table check returned no result.");

    return new ReleaseTableState(reader.GetBoolean(0), reader.GetBoolean(1), reader.GetBoolean(2));
}

static async Task<SqlConnection?> RebuildDatabaseAsync(
    DatabaseSchema schema,
    int commandTimeoutSeconds,
    CancellationToken cancellationToken)
{
    Console.WriteLine($"Rebuilding {schema.Target.ExpectedDatabaseName} from {schema.ScriptPath}...");
    await DropDatabaseIfExistsAsync(
        schema.Target.ConnectionString,
        schema.Target.ExpectedDatabaseName,
        commandTimeoutSeconds,
        cancellationToken);

    var connection = CreateMasterConnection(schema.Target.ConnectionString);
    try
    {
        await connection.OpenAsync(cancellationToken);
        foreach (var batch in SplitSqlBatches(schema.Script))
            await ExecuteNonQueryAsync(connection, batch, commandTimeoutSeconds, cancellationToken);

        await using var verifyCommand = connection.CreateCommand();
        verifyCommand.CommandText = "SELECT CASE WHEN DB_ID(@databaseName) IS NULL THEN 0 ELSE 1 END;";
        verifyCommand.CommandTimeout = commandTimeoutSeconds;
        verifyCommand.Parameters.AddWithValue("@databaseName", schema.Target.ExpectedDatabaseName);
        var exists = Convert.ToInt32(await verifyCommand.ExecuteScalarAsync(cancellationToken)) == 1;
        if (!exists)
            throw new InvalidOperationException($"Initialization script did not create {schema.Target.ExpectedDatabaseName}.");

        if (string.Equals(schema.Target.BaseDatabaseName, "AstralRecord", StringComparison.Ordinal))
        {
            await AcquireMaintenanceIsolationAsync(
                connection,
                schema.Target.ExpectedDatabaseName,
                commandTimeoutSeconds,
                cancellationToken);
            Console.WriteLine("Rebuilt AstralRecord and acquired maintenance isolation.");
            return connection;
        }

        Console.WriteLine($"Rebuilt {schema.Target.ExpectedDatabaseName}.");
        await connection.DisposeAsync();
        return null;
    }
    catch
    {
        await connection.DisposeAsync();
        throw;
    }
}

static IEnumerable<string> SplitSqlBatches(string script)
{
    return Regex
        .Split(script, @"(?im)^\s*GO\s*(?:--.*)?\s*$")
        .Where(batch => !string.IsNullOrWhiteSpace(batch));
}

static async Task RestoreReleaseNotesAsync(
    SqlConnection connection,
    string targetDatabaseName,
    ReleaseNoteBackup backup,
    int commandTimeoutSeconds,
    CancellationToken cancellationToken)
{
    await using var transaction = (SqlTransaction)await connection.BeginTransactionAsync(cancellationToken);

    try
    {
        await RestoreTableAsync(
            connection,
            transaction,
            backup.DatabaseName,
            targetDatabaseName,
            "release_note",
            commandTimeoutSeconds,
            cancellationToken);
        await RestoreTableAsync(
            connection,
            transaction,
            backup.DatabaseName,
            targetDatabaseName,
            "release_notification_outbox",
            commandTimeoutSeconds,
            cancellationToken);

        var restoredReleaseNoteCount = await CountRowsAsync(
            connection,
            $"{QuoteIdentifier(targetDatabaseName)}.[dbo].[release_note]",
            commandTimeoutSeconds,
            cancellationToken,
            transaction);
        var restoredOutboxCount = await CountRowsAsync(
            connection,
            $"{QuoteIdentifier(targetDatabaseName)}.[dbo].[release_notification_outbox]",
            commandTimeoutSeconds,
            cancellationToken,
            transaction);

        if (restoredReleaseNoteCount != backup.ReleaseNoteCount || restoredOutboxCount != backup.OutboxCount)
        {
            throw new InvalidOperationException(
                $"Release note restore count mismatch. Expected releaseNote={backup.ReleaseNoteCount}, outbox={backup.OutboxCount}; " +
                $"actual releaseNote={restoredReleaseNoteCount}, outbox={restoredOutboxCount}.");
        }

        await transaction.CommitAsync(cancellationToken);
        Console.WriteLine(
            $"Release note data restored (releaseNoteRows={restoredReleaseNoteCount}, outboxRows={restoredOutboxCount}).");
    }
    catch
    {
        await transaction.RollbackAsync(cancellationToken);
        throw;
    }
}

static async Task RestoreTableAsync(
    SqlConnection connection,
    SqlTransaction transaction,
    string backupDatabaseName,
    string targetDatabaseName,
    string tableName,
    int commandTimeoutSeconds,
    CancellationToken cancellationToken)
{
    var sourceColumns = await LoadColumnsAsync(
        connection,
        transaction,
        backupDatabaseName,
        tableName,
        commandTimeoutSeconds,
        cancellationToken);
    var targetColumns = await LoadColumnsAsync(
        connection,
        transaction,
        targetDatabaseName,
        tableName,
        commandTimeoutSeconds,
        cancellationToken);

    foreach (var sourceColumn in sourceColumns)
    {
        var targetColumn = targetColumns.FirstOrDefault(column =>
            string.Equals(column.Name, sourceColumn.Name, StringComparison.OrdinalIgnoreCase));
        if (targetColumn is null)
            throw new InvalidOperationException($"Restore target {tableName} is missing preserved column {sourceColumn.Name}.");
        if (!CanRestoreColumn(tableName, sourceColumn, targetColumn))
        {
            throw new InvalidOperationException(
                $"Restore column type changed for {tableName}.{sourceColumn.Name}; backup={sourceColumn.StorageDescription}, " +
                $"target={targetColumn.StorageDescription}.");
        }
    }

    foreach (var targetColumn in targetColumns)
    {
        var sourceColumn = sourceColumns.FirstOrDefault(column =>
            string.Equals(column.Name, targetColumn.Name, StringComparison.OrdinalIgnoreCase));
        if (sourceColumn is null
            && !targetColumn.IsNullable
            && !targetColumn.IsIdentity
            && !targetColumn.IsComputed
            && !targetColumn.HasDefault)
        {
            throw new InvalidOperationException(
                $"Restore target {tableName}.{targetColumn.Name} is required but is absent from the preserved data.");
        }
    }

    var sourceNames = sourceColumns.Select(column => column.Name).ToHashSet(StringComparer.OrdinalIgnoreCase);
    var restoredColumns = targetColumns.Where(column => sourceNames.Contains(column.Name)).ToArray();
    if (restoredColumns.Length == 0)
        throw new InvalidOperationException($"No compatible columns were found for release note table {tableName}.");

    var columnList = string.Join(", ", restoredColumns.Select(column => QuoteIdentifier(column.Name)));
    var selectList = string.Join(", ", restoredColumns.Select(targetColumn =>
    {
        var sourceColumn = sourceColumns.First(column =>
            string.Equals(column.Name, targetColumn.Name, StringComparison.OrdinalIgnoreCase));
        return BuildRestoreSelectExpression(tableName, sourceColumn, targetColumn);
    }));
    var qualifiedTarget = $"{QuoteIdentifier(targetDatabaseName)}.[dbo].{QuoteIdentifier(tableName)}";
    var qualifiedSource = $"{QuoteIdentifier(backupDatabaseName)}.[dbo].{QuoteIdentifier(tableName)}";
    var hasIdentity = restoredColumns.Any(column => column.IsIdentity);
    var insertSql = $"INSERT INTO {qualifiedTarget} ({columnList}) SELECT {selectList} FROM {qualifiedSource};";
    var sql = hasIdentity
        ? $"SET IDENTITY_INSERT {qualifiedTarget} ON; {insertSql} SET IDENTITY_INSERT {qualifiedTarget} OFF;"
        : insertSql;

    await ExecuteNonQueryAsync(
        connection,
        sql,
        commandTimeoutSeconds,
        cancellationToken,
        transaction);
}

static bool CanRestoreColumn(
    string tableName,
    ColumnDefinition sourceColumn,
    ColumnDefinition targetColumn)
{
    if (sourceColumn.HasSameStorageType(targetColumn))
        return true;

    if (!sourceColumn.HasDateTime2Type(targetColumn))
        return false;

    if (sourceColumn.Scale <= targetColumn.Scale)
        return true;

    return sourceColumn.Scale == 7
           && targetColumn.Scale == 3
           && IsLegacyReleaseTimestampColumn(tableName, sourceColumn.Name);
}

static bool IsLegacyReleaseTimestampColumn(string tableName, string columnName)
{
    if (string.Equals(tableName, "release_note", StringComparison.OrdinalIgnoreCase))
    {
        return columnName.Equals("published_at_utc", StringComparison.OrdinalIgnoreCase)
               || columnName.Equals("created_at_utc", StringComparison.OrdinalIgnoreCase)
               || columnName.Equals("updated_at_utc", StringComparison.OrdinalIgnoreCase);
    }

    if (!string.Equals(tableName, "release_notification_outbox", StringComparison.OrdinalIgnoreCase))
        return false;

    return columnName.Equals("next_attempt_at_utc", StringComparison.OrdinalIgnoreCase)
           || columnName.Equals("lease_until_utc", StringComparison.OrdinalIgnoreCase)
           || columnName.Equals("sent_at_utc", StringComparison.OrdinalIgnoreCase)
           || columnName.Equals("created_at_utc", StringComparison.OrdinalIgnoreCase)
           || columnName.Equals("updated_at_utc", StringComparison.OrdinalIgnoreCase);
}

static string BuildRestoreSelectExpression(
    string tableName,
    ColumnDefinition sourceColumn,
    ColumnDefinition targetColumn)
{
    var sourceIdentifier = QuoteIdentifier(sourceColumn.Name);
    if (sourceColumn.HasSameStorageType(targetColumn))
        return sourceIdentifier;

    if (CanRestoreColumn(tableName, sourceColumn, targetColumn))
        return $"CONVERT(datetime2({targetColumn.Scale}), {sourceIdentifier})";

    throw new InvalidOperationException(
        $"Restore expression is unavailable for {sourceColumn.Name}; backup={sourceColumn.StorageDescription}, " +
        $"target={targetColumn.StorageDescription}.");
}

static async Task<IReadOnlyList<ColumnDefinition>> LoadColumnsAsync(
    SqlConnection connection,
    SqlTransaction transaction,
    string databaseName,
    string tableName,
    int commandTimeoutSeconds,
    CancellationToken cancellationToken)
{
    var quotedDatabaseName = QuoteIdentifier(databaseName);
    var sql = $"""
        SELECT
            column_definition.[name],
            type_definition.[name],
            column_definition.[max_length],
            column_definition.[precision],
            column_definition.[scale],
            column_definition.[is_nullable],
            column_definition.[is_identity],
            column_definition.[is_computed],
            CASE WHEN column_definition.[default_object_id] = 0 THEN CAST(0 AS bit) ELSE CAST(1 AS bit) END
        FROM {quotedDatabaseName}.[sys].[columns] AS column_definition
        INNER JOIN {quotedDatabaseName}.[sys].[tables] AS table_definition
            ON table_definition.[object_id] = column_definition.[object_id]
        INNER JOIN {quotedDatabaseName}.[sys].[schemas] AS schema_definition
            ON schema_definition.[schema_id] = table_definition.[schema_id]
        INNER JOIN {quotedDatabaseName}.[sys].[types] AS type_definition
            ON type_definition.[user_type_id] = column_definition.[user_type_id]
        WHERE schema_definition.[name] = N'dbo'
          AND table_definition.[name] = @tableName
        ORDER BY column_definition.[column_id];
        """;

    await using var command = connection.CreateCommand();
    command.Transaction = transaction;
    command.CommandText = sql;
    command.CommandTimeout = commandTimeoutSeconds;
    command.Parameters.AddWithValue("@tableName", tableName);

    var columns = new List<ColumnDefinition>();
    await using var reader = await command.ExecuteReaderAsync(cancellationToken);
    while (await reader.ReadAsync(cancellationToken))
    {
        columns.Add(new ColumnDefinition(
            reader.GetString(0),
            reader.GetString(1),
            reader.GetInt16(2),
            reader.GetByte(3),
            reader.GetByte(4),
            reader.GetBoolean(5),
            reader.GetBoolean(6),
            reader.GetBoolean(7),
            reader.GetBoolean(8)));
    }

    if (columns.Count == 0)
        throw new InvalidOperationException($"Release note table was not found: {databaseName}.dbo.{tableName}");

    return columns;
}

static async Task<long> CountRowsAsync(
    SqlConnection connection,
    string qualifiedTableName,
    int commandTimeoutSeconds,
    CancellationToken cancellationToken,
    SqlTransaction? transaction = null)
{
    await using var command = connection.CreateCommand();
    command.Transaction = transaction;
    command.CommandText = $"SELECT COUNT_BIG(*) FROM {qualifiedTableName};";
    command.CommandTimeout = commandTimeoutSeconds;
    return Convert.ToInt64(await command.ExecuteScalarAsync(cancellationToken));
}

static async Task AcquireMaintenanceIsolationAsync(
    SqlConnection connection,
    string databaseName,
    int commandTimeoutSeconds,
    CancellationToken cancellationToken)
{
    var quotedDatabaseName = QuoteIdentifier(databaseName);
    var sql = $"""
        USE [master];
        ALTER DATABASE {quotedDatabaseName} SET SINGLE_USER WITH ROLLBACK IMMEDIATE;
        USE {quotedDatabaseName};
        """;
    await ExecuteNonQueryAsync(connection, sql, commandTimeoutSeconds, cancellationToken);
}

static async Task ReleaseMaintenanceIsolationAsync(
    SqlConnection connection,
    string databaseName,
    int commandTimeoutSeconds,
    CancellationToken cancellationToken)
{
    var quotedDatabaseName = QuoteIdentifier(databaseName);
    var sql = $"""
        USE [master];
        ALTER DATABASE {quotedDatabaseName} SET MULTI_USER;
        """;
    await ExecuteNonQueryAsync(connection, sql, commandTimeoutSeconds, cancellationToken);
    Console.WriteLine($"Released maintenance isolation: {databaseName}");
}

static async Task IsolateFailedDatabaseAsync(
    DatabaseTarget? target,
    SqlConnection? isolationConnection,
    bool rebuildStarted,
    CancellationToken cancellationToken)
{
    if (target is null || !rebuildStarted)
        return;

    try
    {
        bool isolated;
        if (isolationConnection is not null && isolationConnection.State == ConnectionState.Open)
        {
            isolated = await ExecuteFailureIsolationAsync(
                isolationConnection,
                target.ExpectedDatabaseName,
                cancellationToken);
        }
        else
        {
            await using var connection = CreateMasterConnection(target.ConnectionString);
            await connection.OpenAsync(cancellationToken);
            isolated = await ExecuteFailureIsolationAsync(
                connection,
                target.ExpectedDatabaseName,
                cancellationToken);
        }

        if (isolated)
        {
            Console.Error.WriteLine(
                $"{target.ExpectedDatabaseName} was left OFFLINE to prevent access to a partial rebuild.");
        }
    }
    catch (Exception isolationException)
    {
        Console.Error.WriteLine(
            $"Failed to isolate partial database {target.ExpectedDatabaseName}: {isolationException.Message}");
    }
}

static async Task<bool> ExecuteFailureIsolationAsync(
    SqlConnection connection,
    string databaseName,
    CancellationToken cancellationToken)
{
    var quotedDatabaseName = QuoteIdentifier(databaseName);
    var sql = $"""
        USE [master];
        IF DB_ID(@databaseName) IS NOT NULL
        BEGIN
            ALTER DATABASE {quotedDatabaseName} SET OFFLINE WITH ROLLBACK IMMEDIATE;
            SELECT CAST(1 AS bit);
        END
        ELSE
        BEGIN
            SELECT CAST(0 AS bit);
        END;
        """;
    await using var command = connection.CreateCommand();
    command.CommandText = sql;
    command.CommandTimeout = 60;
    command.Parameters.AddWithValue("@databaseName", databaseName);
    return Convert.ToBoolean(await command.ExecuteScalarAsync(cancellationToken));
}

static async Task DisposeConnectionAsync(SqlConnection? connection)
{
    if (connection is not null)
        await connection.DisposeAsync();
}

static async Task DropDatabaseIfExistsAsync(
    string connectionString,
    string databaseName,
    int commandTimeoutSeconds,
    CancellationToken cancellationToken)
{
    await using var connection = CreateMasterConnection(connectionString);
    await connection.OpenAsync(cancellationToken);

    const string sql = """
        IF DB_ID(@databaseName) IS NOT NULL
        BEGIN
            DECLARE @statement nvarchar(max);
            IF DATABASEPROPERTYEX(@databaseName, N'Status') = N'OFFLINE'
            BEGIN
                SET @statement = N'ALTER DATABASE ' + QUOTENAME(@databaseName) + N' SET ONLINE';
                EXEC(@statement);
            END;
            SET @statement = N'ALTER DATABASE ' + QUOTENAME(@databaseName) + N' SET SINGLE_USER WITH ROLLBACK IMMEDIATE';
            EXEC(@statement);
            SET @statement = N'DROP DATABASE ' + QUOTENAME(@databaseName);
            EXEC(@statement);
        END;
        """;

    await using var command = connection.CreateCommand();
    command.CommandText = sql;
    command.CommandTimeout = commandTimeoutSeconds;
    command.Parameters.AddWithValue("@databaseName", databaseName);
    await command.ExecuteNonQueryAsync(cancellationToken);
}

static async Task TryDropIncompleteBackupAsync(
    string connectionString,
    string databaseName,
    int commandTimeoutSeconds,
    CancellationToken cancellationToken)
{
    try
    {
        await DropDatabaseIfExistsAsync(
            connectionString,
            databaseName,
            commandTimeoutSeconds,
            cancellationToken);
    }
    catch (Exception cleanupException)
    {
        Console.Error.WriteLine(
            $"Incomplete backup database cleanup failed for {databaseName}: {cleanupException.Message}");
    }
}

static async Task ExecuteNonQueryAsync(
    SqlConnection connection,
    string sql,
    int commandTimeoutSeconds,
    CancellationToken cancellationToken,
    SqlTransaction? transaction = null)
{
    await using var command = connection.CreateCommand();
    command.Transaction = transaction;
    command.CommandText = sql;
    command.CommandTimeout = commandTimeoutSeconds;
    await command.ExecuteNonQueryAsync(cancellationToken);
}

static SqlConnection CreateMasterConnection(string connectionString)
{
    var builder = new SqlConnectionStringBuilder(connectionString)
    {
        InitialCatalog = "master",
    };
    return new SqlConnection(builder.ConnectionString);
}

static string CreateBackupDatabaseName(string sourceDatabaseName, string operationId)
    => $"{GetBackupDatabasePrefix(sourceDatabaseName)}{DateTime.UtcNow:yyyyMMddHHmmss}_{operationId}";

static string GetBackupDatabasePrefix(string sourceDatabaseName)
    => $"{sourceDatabaseName}_ReleaseNotesBackup_";

static string GetResetLockResource(string sourceDatabaseName)
    => $"AstralRecord:DbReset:{sourceDatabaseName}";

static string ResolveMaintenanceOperationId(CliOptions options)
{
    string? backupOperationId = null;
    if (!string.IsNullOrWhiteSpace(options.RestoreBackupDatabaseName))
    {
        var match = Regex.Match(
            options.RestoreBackupDatabaseName,
            @"_(?<operationId>[0-9a-fA-F]{32})$",
            RegexOptions.CultureInvariant);
        if (!match.Success)
        {
            throw new InvalidOperationException(
                "--restore-backup must end with the 32-character maintenance operation ID.");
        }

        backupOperationId = match.Groups["operationId"].Value.ToLowerInvariant();
    }

    string? resumeOperationId = null;
    if (!string.IsNullOrWhiteSpace(options.ResumeOperationId))
    {
        if (!Regex.IsMatch(
                options.ResumeOperationId,
                @"^[0-9a-fA-F]{32}$",
                RegexOptions.CultureInvariant))
        {
            throw new InvalidOperationException(
                "--resume-operation must be a 32-character hexadecimal maintenance operation ID.");
        }

        resumeOperationId = options.ResumeOperationId.ToLowerInvariant();
    }

    if (backupOperationId is not null
        && resumeOperationId is not null
        && !string.Equals(backupOperationId, resumeOperationId, StringComparison.Ordinal))
    {
        throw new InvalidOperationException(
            "--resume-operation does not match the operation ID encoded in --restore-backup.");
    }

    return backupOperationId ?? resumeOperationId ?? Guid.NewGuid().ToString("N");
}

static string QuoteIdentifier(string identifier)
{
    if (string.IsNullOrWhiteSpace(identifier) || identifier.Length > 128)
        throw new InvalidOperationException($"Invalid SQL identifier: {identifier}");
    return $"[{identifier.Replace("]", "]]", StringComparison.Ordinal)}]";
}

static void PrintBackupRecoveryMessage(
    ReleaseNoteBackup? backup,
    DatabaseTarget? target,
    string? operationId)
{
    if (target is null || string.IsNullOrWhiteSpace(operationId))
        return;

    var testSuffix = target.ExpectedDatabaseName[target.BaseDatabaseName.Length..];
    var testArgument = string.IsNullOrEmpty(testSuffix)
        ? string.Empty
        : $" --integration-test-suffix {testSuffix}";
    if (backup is not null)
    {
        Console.Error.WriteLine(
            $"Release note backup database was retained for recovery: {backup.DatabaseName} " +
            $"(releaseNoteRows={backup.ReleaseNoteCount}, outboxRows={backup.OutboxCount}).");
        Console.Error.WriteLine(
            $"Recovery command: 11-db-reset-except-release-notes.bat --yes{testArgument} --restore-backup {backup.DatabaseName}");
        return;
    }

    Console.Error.WriteLine(
        $"Recovery command: 11-db-reset-except-release-notes.bat --yes{testArgument} --resume-operation {operationId}");
}

static void PrintSummary(
    EffectiveSettings settings,
    string configPath,
    IReadOnlyList<DatabaseSchema> schemas,
    string? restoreBackupDatabaseName)
{
    Console.WriteLine("=== DB Reset Except Release Notes Tool ===");
    Console.WriteLine($"Config: {configPath}");
    foreach (var schema in schemas)
        Console.WriteLine($"{schema.Target.ExpectedDatabaseName}: {schema.ScriptPath}");
    Console.WriteLine($"Preserved: {settings.AstralRecord.ExpectedDatabaseName}.dbo.release_note");
    Console.WriteLine($"Preserved: {settings.AstralRecord.ExpectedDatabaseName}.dbo.release_notification_outbox");
    Console.WriteLine(
        string.IsNullOrWhiteSpace(restoreBackupDatabaseName)
            ? "Mode: create a new backup"
            : $"Mode: recover from {restoreBackupDatabaseName}");
    foreach (var applicationRoot in settings.ApplicationOffline.Roots)
        Console.WriteLine($"Application offline root: {applicationRoot}");
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
    public string? RestoreBackupDatabaseName { get; init; }
    public string? ResumeOperationId { get; init; }
    public string? IntegrationTestSuffix { get; init; }
    public IReadOnlyList<string> IntegrationApplicationRoots { get; init; } = Array.Empty<string>();
    public bool FailAfterAstralRecordRebuild { get; init; }
    public bool Yes { get; init; }
    public bool IsMaintenanceResume
        => !string.IsNullOrWhiteSpace(RestoreBackupDatabaseName)
           || !string.IsNullOrWhiteSpace(ResumeOperationId);
    public bool IsMarkerOnlyResume
        => string.IsNullOrWhiteSpace(RestoreBackupDatabaseName)
           && !string.IsNullOrWhiteSpace(ResumeOperationId);

    public static CliOptions Parse(string[] args)
    {
        string? configPath = null;
        string? restoreBackupDatabaseName = null;
        string? resumeOperationId = null;
        string? integrationTestSuffix = null;
        var integrationApplicationRoots = new List<string>();
        var failAfterAstralRecordRebuild = false;
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

                case "--restore-backup":
                    if (index + 1 >= args.Length)
                        throw new ArgumentException("--restore-backup requires a value.");

                    restoreBackupDatabaseName = args[++index];
                    break;

                case "--resume-operation":
                    if (index + 1 >= args.Length)
                        throw new ArgumentException("--resume-operation requires a value.");

                    resumeOperationId = args[++index];
                    break;

                case "--integration-test-suffix":
                    if (index + 1 >= args.Length)
                        throw new ArgumentException("--integration-test-suffix requires a value.");

                    integrationTestSuffix = args[++index];
                    break;

                case "--integration-app-root":
                    if (index + 1 >= args.Length)
                        throw new ArgumentException("--integration-app-root requires a value.");

                    integrationApplicationRoots.Add(args[++index]);
                    break;

                case "--fail-after-astral-record-rebuild":
                    failAfterAstralRecordRebuild = true;
                    break;

                default:
                    throw new ArgumentException($"Unsupported argument: {args[index]}");
            }
        }

        return new CliOptions
        {
            ConfigPath = configPath,
            RestoreBackupDatabaseName = restoreBackupDatabaseName,
            ResumeOperationId = resumeOperationId,
            IntegrationTestSuffix = integrationTestSuffix,
            IntegrationApplicationRoots = integrationApplicationRoots,
            FailAfterAstralRecordRebuild = failAfterAstralRecordRebuild,
            Yes = yes,
        };
    }
}

internal sealed class ResetToolConfig
{
    public string? SourceApiAppsettingsPath { get; init; }
    public ResetConnectionStringsConfig ConnectionStrings { get; init; } = new();
    public ApplicationOfflineConfig ApplicationOffline { get; init; } = new();
    public int CommandTimeoutSeconds { get; init; } = 600;
}

internal sealed class ApplicationOfflineConfig
{
    public IReadOnlyList<string> Roots { get; init; } = Array.Empty<string>();
    public int WaitSeconds { get; init; } = 5;
}

internal sealed class ResetConnectionStringsConfig
{
    public string? SqlServer { get; init; }
    public string? MasterData { get; init; }
    public string? History { get; init; }
}

internal sealed record DatabaseTarget(
    string BaseDatabaseName,
    string ExpectedDatabaseName,
    string ConnectionString);

internal sealed record DatabaseSchema(
    DatabaseTarget Target,
    string ScriptPath,
    string Script);

internal sealed record EffectiveSettings(
    IReadOnlyList<DatabaseTarget> Targets,
    ApplicationOfflineSettings ApplicationOffline,
    int CommandTimeoutSeconds)
{
    public DatabaseTarget AstralRecord => Targets.Single(target => target.BaseDatabaseName == "AstralRecord");
}

internal sealed record ApplicationOfflineSettings(
    IReadOnlyList<string> Roots,
    int WaitSeconds);

internal sealed class ApplicationOfflineGuard : IAsyncDisposable
{
    private readonly ResetLockHandle resetLock;

    public ApplicationOfflineGuard(
        IReadOnlyList<string> paths,
        string expectedContent,
        string operationId,
        bool hasAdoptedMarkers,
        ResetLockHandle resetLock)
    {
        Paths = paths;
        ExpectedContent = expectedContent;
        OperationId = operationId;
        HasAdoptedMarkers = hasAdoptedMarkers;
        this.resetLock = resetLock;
    }

    public IReadOnlyList<string> Paths { get; }
    public string ExpectedContent { get; }
    public string OperationId { get; }
    public bool HasAdoptedMarkers { get; }

    public ValueTask DisposeAsync()
        => resetLock.DisposeAsync();
}

internal sealed class ResetLockHandle : IAsyncDisposable
{
    private readonly SqlConnection connection;
    private readonly string resource;
    private readonly int commandTimeoutSeconds;
    private int disposed;

    public ResetLockHandle(
        SqlConnection connection,
        string resource,
        int commandTimeoutSeconds)
    {
        this.connection = connection;
        this.resource = resource;
        this.commandTimeoutSeconds = commandTimeoutSeconds;
    }

    public async ValueTask DisposeAsync()
    {
        if (Interlocked.Exchange(ref disposed, 1) != 0)
            return;

        try
        {
            if (connection.State == ConnectionState.Open)
            {
                const string sql = """
                    DECLARE @result int;
                    EXEC @result = [sys].[sp_releaseapplock]
                        @Resource = @resource,
                        @LockOwner = N'Session',
                        @DbPrincipal = N'public';
                    SELECT @result;
                    """;
                await using var command = connection.CreateCommand();
                command.CommandText = sql;
                command.CommandTimeout = commandTimeoutSeconds;
                command.Parameters.AddWithValue("@resource", resource);
                var result = Convert.ToInt32(await command.ExecuteScalarAsync(CancellationToken.None));
                if (result < 0)
                {
                    throw new InvalidOperationException(
                        $"SQL Server returned {result} while releasing the database reset lock.");
                }
            }
        }
        catch (Exception exception)
        {
            try
            {
                SqlConnection.ClearPool(connection);
            }
            catch
            {
                // Closing the current connection remains the final lock-release fallback.
            }

            Console.Error.WriteLine(
                $"Failed to release the database reset lock cleanly; the SQL session will be discarded: {exception.Message}");
        }
        finally
        {
            try
            {
                await connection.DisposeAsync();
            }
            catch (Exception exception)
            {
                Console.Error.WriteLine(
                    $"Failed to dispose the database reset lock connection: {exception.Message}");
            }
        }
    }
}

internal sealed record ReleaseNoteBackup(
    string DatabaseName,
    long ReleaseNoteCount,
    long OutboxCount);

internal sealed record ReleaseTableState(
    bool DatabaseExists,
    bool ReleaseNoteExists,
    bool OutboxExists);

internal sealed record ColumnDefinition(
    string Name,
    string TypeName,
    short MaxLength,
    byte Precision,
    byte Scale,
    bool IsNullable,
    bool IsIdentity,
    bool IsComputed,
    bool HasDefault)
{
    public bool HasSameStorageType(ColumnDefinition other)
        => string.Equals(TypeName, other.TypeName, StringComparison.OrdinalIgnoreCase)
           && MaxLength == other.MaxLength
           && Precision == other.Precision
           && Scale == other.Scale;

    public bool HasDateTime2Type(ColumnDefinition other)
        => string.Equals(TypeName, "datetime2", StringComparison.OrdinalIgnoreCase)
           && string.Equals(other.TypeName, "datetime2", StringComparison.OrdinalIgnoreCase);

    public string StorageDescription => $"{TypeName}(maxLength={MaxLength}, precision={Precision}, scale={Scale})";
}

internal static class MaintenanceFiles
{
    public static string CreateContent(string operationId)
        => $"""
            <!doctype html>
            <html><head><title>AstralRecord maintenance</title></head>
            <body><!-- AstralRecord DB reset maintenance:{operationId} -->AstralRecord database maintenance is in progress.</body></html>
            """;
}
