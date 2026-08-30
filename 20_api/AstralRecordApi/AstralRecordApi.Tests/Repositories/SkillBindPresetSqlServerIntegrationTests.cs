using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.Data.SqlClient;
using Microsoft.EntityFrameworkCore;
using Xunit;

namespace AstralRecordApi.Tests.Repositories;

/// <summary>
/// Production SQL Server のプリセット選択排他を固定する統合テスト。
/// localhost\SQLEXPRESS 上のランダムな一時DB以外は作成・削除しない。
/// </summary>
[Trait("Category", "SqlServerIntegration")]
public class SkillBindPresetSqlServerIntegrationTests
{
    private const string DatabasePrefix = "AstralRecordSkillPresetIntegration_";
    private const string LocalSqlServerInstance = @"localhost\SQLEXPRESS";
    private const string SqlServerOptInEnvironmentVariable = "ASTRALRECORD_RUN_SQLSERVER_INTEGRATION";

    [Fact]
    public async Task ConcurrentSelections_SerializePerAccountAndKeepOneSelectedPreset()
    {
        if (!SqlServerIntegrationEnabled()) return;
        await using var harness = await SqlServerHarness.CreateAsync();
        using var start = new Barrier(2);

        var first = Task.Run(async () =>
        {
            start.SignalAndWait();
            return await harness.SelectAsync(2);
        });
        var second = Task.Run(async () =>
        {
            start.SignalAndWait();
            return await harness.SelectAsync(3);
        });

        var results = await Task.WhenAll(first, second).WaitAsync(TimeSpan.FromSeconds(20));
        var presets = await harness.GetPresetsAsync();

        Assert.All(results, Assert.True);
        Assert.Equal(3, presets.Count);
        Assert.Single(presets, preset => preset.IsSelected);
        Assert.Contains(presets.Single(preset => preset.IsSelected).PresetIndex, new[] { 2, 3 });
    }

    private sealed class SqlServerHarness : IAsyncDisposable
    {
        private readonly string databaseName;
        private readonly string connectionString;
        private readonly Guid accountId;
        private readonly Guid userId;

        private SqlServerHarness(
            string databaseName,
            string connectionString,
            Guid accountId,
            Guid userId)
        {
            this.databaseName = databaseName;
            this.connectionString = connectionString;
            this.accountId = accountId;
            this.userId = userId;
        }

        public static async Task<SqlServerHarness> CreateAsync()
        {
            var databaseName = DatabasePrefix + Guid.NewGuid().ToString("N");
            var connectionString = BuildConnectionString(databaseName);
            var accountId = Guid.NewGuid();
            var userId = Guid.NewGuid();
            await CreateDatabaseAsync(databaseName);

            try
            {
                await CreateSchemaAsync(connectionString);
                await using var dbContext = CreateDbContext(connectionString);
                var now = DateTime.UtcNow;
                dbContext.Accounts.Add(new AccountEntity
                {
                    Uuid = accountId,
                    UserId = userId,
                    AccountName = "skill-preset-integration",
                    SlotIndex = 1,
                    IsActive = true,
                    Mode = 0,
                    Level = 1,
                    ClassId = "adventurer",
                    ClassLevel = 1,
                    CreatedAt = now,
                    UpdatedAt = now,
                    CreatedBy = userId,
                    UpdatedBy = userId,
                });
                dbContext.SkillBindPresets.AddRange(
                    CreatePreset(accountId, userId, 1, isSelected: true, now),
                    CreatePreset(accountId, userId, 2, isSelected: false, now),
                    CreatePreset(accountId, userId, 3, isSelected: false, now));
                await dbContext.SaveChangesAsync();
                return new SqlServerHarness(databaseName, connectionString, accountId, userId);
            }
            catch
            {
                await DropDatabaseAsync(databaseName);
                throw;
            }
        }

        public async Task<bool> SelectAsync(int presetIndex)
        {
            await using var dbContext = CreateDbContext(connectionString);
            return await new SkillBindPresetRepository(dbContext).SelectAsync(
                accountId,
                presetIndex,
                new SkillBindPresetSelectionRequest
                {
                    PresetIndex = presetIndex,
                    UpdatedBy = userId,
                });
        }

        public async Task<List<SkillBindPresetEntity>> GetPresetsAsync()
        {
            await using var dbContext = CreateDbContext(connectionString);
            return await dbContext.SkillBindPresets
                .AsNoTracking()
                .Where(preset => preset.AccountId == accountId && !preset.IsDeleted)
                .OrderBy(preset => preset.PresetIndex)
                .ToListAsync();
        }

        public async ValueTask DisposeAsync() => await DropDatabaseAsync(databaseName);

        private static SkillBindPresetEntity CreatePreset(
            Guid accountId,
            Guid userId,
            int presetIndex,
            bool isSelected,
            DateTime now) => new()
        {
            SkillBindPresetId = Guid.NewGuid(),
            AccountId = accountId,
            PresetIndex = presetIndex,
            ActiveSkillSlotsJson = "[null,null,null,null,null,null,null,null]",
            LeftClickSkillId = "NORMAL_ATTACK",
            PassiveSkillSlotsJson = "[null,null,null,null]",
            IsUnlocked = true,
            IsSelected = isSelected,
            Version = 1,
            CreatedAt = now,
            UpdatedAt = now,
            CreatedBy = userId,
            UpdatedBy = userId,
        };
    }

    private static AstralRecordDbContext CreateDbContext(string connectionString) => new(
        new DbContextOptionsBuilder<AstralRecordDbContext>()
            .UseSqlServer(connectionString, sqlServer => sqlServer.EnableRetryOnFailure())
            .Options);

    private static async Task CreateDatabaseAsync(string databaseName)
    {
        EnsureTemporaryDatabaseName(databaseName);
        await using var connection = new SqlConnection(BuildConnectionString("master"));
        await connection.OpenAsync();
        await ExecuteAsync(connection, $"CREATE DATABASE {QuoteIdentifier(databaseName)}");
    }

    private static async Task DropDatabaseAsync(string databaseName)
    {
        EnsureTemporaryDatabaseName(databaseName);
        await using var connection = new SqlConnection(BuildConnectionString("master"));
        await connection.OpenAsync();
        await ExecuteAsync(connection,
            $"ALTER DATABASE {QuoteIdentifier(databaseName)} SET SINGLE_USER WITH ROLLBACK IMMEDIATE; " +
            $"DROP DATABASE {QuoteIdentifier(databaseName)}");
    }

    private static async Task CreateSchemaAsync(string connectionString)
    {
        await using var connection = new SqlConnection(connectionString);
        await connection.OpenAsync();
        await ExecuteAsync(connection, """
            CREATE TABLE dbo.account (
                uuid uniqueidentifier NOT NULL PRIMARY KEY,
                user_id uniqueidentifier NOT NULL,
                account_name nvarchar(128) NOT NULL,
                slot_index int NOT NULL,
                is_active bit NOT NULL,
                mode tinyint NOT NULL,
                menu_shortcuts_json nvarchar(max) NOT NULL,
                level int NOT NULL,
                total_experience bigint NOT NULL,
                class_id nvarchar(100) NOT NULL,
                class_level int NOT NULL,
                class_experience bigint NOT NULL,
                created_at datetime2 NOT NULL,
                updated_at datetime2 NOT NULL,
                created_by uniqueidentifier NOT NULL,
                updated_by uniqueidentifier NOT NULL,
                is_deleted bit NOT NULL
            );
            CREATE TABLE dbo.skill_bind_preset (
                skill_bind_preset_id uniqueidentifier NOT NULL PRIMARY KEY,
                account_id uniqueidentifier NOT NULL,
                preset_index int NOT NULL,
                active_skill_slots_json nvarchar(max) NOT NULL,
                left_click_skill_id nvarchar(128) NULL,
                passive_skill_slots_json nvarchar(max) NOT NULL,
                is_unlocked bit NOT NULL,
                is_selected bit NOT NULL,
                version int NOT NULL,
                created_at datetime2 NOT NULL,
                updated_at datetime2 NOT NULL,
                created_by uniqueidentifier NOT NULL,
                updated_by uniqueidentifier NOT NULL,
                is_deleted bit NOT NULL
            );
            CREATE UNIQUE INDEX UX_skill_bind_preset_account_preset
                ON dbo.skill_bind_preset(account_id, preset_index)
                WHERE is_deleted = 0;
            CREATE UNIQUE INDEX UX_skill_bind_preset_account_selected
                ON dbo.skill_bind_preset(account_id)
                WHERE is_deleted = 0 AND is_selected = 1;
            """);
    }

    private static string BuildConnectionString(string databaseName)
    {
        EnsureLocalSqlServerInstance(LocalSqlServerInstance);
        return new SqlConnectionStringBuilder
        {
            DataSource = LocalSqlServerInstance,
            InitialCatalog = databaseName,
            IntegratedSecurity = true,
            TrustServerCertificate = true,
        }.ConnectionString;
    }

    private static bool SqlServerIntegrationEnabled() => string.Equals(
        Environment.GetEnvironmentVariable(SqlServerOptInEnvironmentVariable),
        "1",
        StringComparison.Ordinal);

    private static void EnsureTemporaryDatabaseName(string databaseName)
    {
        if (!databaseName.StartsWith(DatabasePrefix, StringComparison.Ordinal)
            || databaseName.Length != DatabasePrefix.Length + 32)
            throw new InvalidOperationException("Only this test's random temporary database may be created or removed.");
    }

    private static void EnsureLocalSqlServerInstance(string dataSource)
    {
        if (!string.Equals(dataSource, LocalSqlServerInstance, StringComparison.OrdinalIgnoreCase))
            throw new InvalidOperationException("SQL Server integration tests may only connect to localhost\\SQLEXPRESS.");
    }

    private static string QuoteIdentifier(string value) =>
        "[" + value.Replace("]", "]]", StringComparison.Ordinal) + "]";

    private static async Task ExecuteAsync(SqlConnection connection, string commandText)
    {
        await using var command = new SqlCommand(commandText, connection);
        command.CommandTimeout = 120;
        await command.ExecuteNonQueryAsync();
    }
}
