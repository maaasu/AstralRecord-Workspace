using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.Data.SqlClient;
using Microsoft.EntityFrameworkCore;
using Xunit;

namespace AstralRecordApi.Tests.Repositories;

[Trait("Category", "SqlServerIntegration")]
public class InventorySqlServerIntegrationTests
{
    private const string DatabasePrefix = "AstralRecordInventoryIntegration_";
    private const string LocalSqlServerInstance = @"localhost\SQLEXPRESS";
    private const string SqlServerOptInEnvironmentVariable = "ASTRALRECORD_RUN_SQLSERVER_INTEGRATION";

    [Fact]
    public async Task ReplaceEntriesConcurrently_SerializesSameInventoryWithoutDeadlock()
    {
        if (!SqlServerIntegrationEnabled())
            return;

        var databaseName = DatabasePrefix + Guid.NewGuid().ToString("N");
        var connectionString = BuildConnectionString(databaseName);
        try
        {
            await CreateDatabaseAsync(databaseName);
            await CreateInventorySchemaAsync(connectionString);

            var accountId = Guid.NewGuid();
            var inventoryId = Guid.NewGuid();
            var firstEntryId = Guid.NewGuid();
            var secondEntryId = Guid.NewGuid();
            var updatedBy = Guid.NewGuid();
            var now = DateTime.UtcNow;
            now = new DateTime(
                now.Ticks - now.Ticks % TimeSpan.TicksPerMillisecond,
                DateTimeKind.Utc);
            await SeedInventoryAsync(
                connectionString,
                accountId,
                inventoryId,
                firstEntryId,
                secondEntryId,
                updatedBy,
                now);

            var firstRequest = CreateRequest(firstEntryId, secondEntryId, updatedBy, now, 2, 1);
            var secondRequest = CreateRequest(firstEntryId, secondEntryId, updatedBy, now, 3, 4);
            using var start = new Barrier(2);
            var firstReplace = Task.Run(async () =>
            {
                await using var dbContext = CreateDbContext(connectionString);
                start.SignalAndWait();
                return await new InventoryRepository(dbContext).ReplaceEntriesAsync(inventoryId, firstRequest);
            });
            var secondReplace = Task.Run(async () =>
            {
                await using var dbContext = CreateDbContext(connectionString);
                start.SignalAndWait();
                return await new InventoryRepository(dbContext).ReplaceEntriesAsync(inventoryId, secondRequest);
            });

            var combined = Task.WhenAll(firstReplace, secondReplace);
            var completed = await Task.WhenAny(combined, Task.Delay(TimeSpan.FromSeconds(30)));
            Assert.Same(combined, completed);
            var results = await combined;

            Assert.Equal(1, results.Count(result => result is not null));
            Assert.Equal(1, results.Count(result => result is null));

            await using var verifyContext = CreateDbContext(connectionString);
            var finalEntries = await verifyContext.InventoryEntries
                .AsNoTracking()
                .Where(entry => entry.InventoryId == inventoryId && !entry.IsDeleted)
                .ToArrayAsync();
            Assert.Equal(2, finalEntries.Length);
            var finalSlots = finalEntries
                .Select(entry => entry.SlotIndex)
                .Order()
                .ToArray();
            Assert.True(
                finalSlots.SequenceEqual(new int?[] { 1, 2 })
                || finalSlots.SequenceEqual(new int?[] { 3, 4 }));
        }
        finally
        {
            await DropDatabaseAsync(databaseName);
        }
    }

    [Fact]
    public async Task ReplaceEntriesConcurrently_CrossMovesSameAccountWithoutDeadlock()
    {
        if (!SqlServerIntegrationEnabled())
            return;

        var databaseName = DatabasePrefix + Guid.NewGuid().ToString("N");
        var connectionString = BuildConnectionString(databaseName);
        try
        {
            await CreateDatabaseAsync(databaseName);
            await CreateInventorySchemaAsync(connectionString);

            var accountId = Guid.NewGuid();
            var firstInventoryId = Guid.NewGuid();
            var secondInventoryId = Guid.NewGuid();
            var firstEntryId = Guid.NewGuid();
            var secondEntryId = Guid.NewGuid();
            var updatedBy = Guid.NewGuid();
            var now = DateTime.UtcNow;
            now = new DateTime(
                now.Ticks - now.Ticks % TimeSpan.TicksPerMillisecond,
                DateTimeKind.Utc);
            await SeedTwoInventoriesAsync(
                connectionString,
                accountId,
                firstInventoryId,
                secondInventoryId,
                firstEntryId,
                secondEntryId,
                updatedBy,
                now);

            var firstRequest = CreateMoveRequest(secondEntryId, updatedBy, now, 3);
            var secondRequest = CreateMoveRequest(firstEntryId, updatedBy, now, 4);
            using var start = new Barrier(2);
            var firstReplace = Task.Run(async () =>
            {
                await using var dbContext = CreateDbContext(connectionString);
                start.SignalAndWait();
                return await new InventoryRepository(dbContext)
                    .ReplaceEntriesAsync(firstInventoryId, firstRequest);
            });
            var secondReplace = Task.Run(async () =>
            {
                await using var dbContext = CreateDbContext(connectionString);
                start.SignalAndWait();
                return await new InventoryRepository(dbContext)
                    .ReplaceEntriesAsync(secondInventoryId, secondRequest);
            });

            var combined = Task.WhenAll(firstReplace, secondReplace);
            var completed = await Task.WhenAny(combined, Task.Delay(TimeSpan.FromSeconds(30)));
            Assert.Same(combined, completed);
            var results = await combined;

            Assert.Equal(1, results.Count(result => result is not null));
            Assert.Equal(1, results.Count(result => result is null));
        }
        finally
        {
            await DropDatabaseAsync(databaseName);
        }
    }

    private static AstralRecordDbContext CreateDbContext(string connectionString) => new(
        new DbContextOptionsBuilder<AstralRecordDbContext>()
            .UseSqlServer(connectionString, sqlServerOptions => sqlServerOptions.EnableRetryOnFailure())
            .Options);

    private static InventoryEntryReplaceRequest CreateRequest(
        Guid firstEntryId,
        Guid secondEntryId,
        Guid updatedBy,
        DateTime expectedUpdatedAt,
        int firstSlot,
        int secondSlot) => new()
        {
            UpdatedBy = updatedBy,
            Entries =
            [
                new InventoryEntryReplaceItemRequest
                {
                    InventoryEntryId = firstEntryId,
                    ExpectedUpdatedAt = expectedUpdatedAt,
                    SlotIndex = firstSlot,
                    ItemCategory = "MATERIAL",
                    ItemId = "deadlock_test_first",
                    Quantity = 1,
                },
                new InventoryEntryReplaceItemRequest
                {
                    InventoryEntryId = secondEntryId,
                    ExpectedUpdatedAt = expectedUpdatedAt,
                    SlotIndex = secondSlot,
                    ItemCategory = "MATERIAL",
                    ItemId = "deadlock_test_second",
                    Quantity = 1,
                },
            ],
        };

    private static InventoryEntryReplaceRequest CreateMoveRequest(
        Guid movedEntryId,
        Guid updatedBy,
        DateTime expectedUpdatedAt,
        int slotIndex) => new()
        {
            UpdatedBy = updatedBy,
            Entries =
            [
                new InventoryEntryReplaceItemRequest
                {
                    InventoryEntryId = movedEntryId,
                    ExpectedUpdatedAt = expectedUpdatedAt,
                    SlotIndex = slotIndex,
                    ItemCategory = "MATERIAL",
                    ItemId = "deadlock_test_moved",
                    Quantity = 1,
                },
            ],
        };

    private static async Task SeedInventoryAsync(
        string connectionString,
        Guid accountId,
        Guid inventoryId,
        Guid firstEntryId,
        Guid secondEntryId,
        Guid updatedBy,
        DateTime now)
    {
        await using var dbContext = CreateDbContext(connectionString);
        dbContext.Accounts.Add(CreateAccount(accountId, updatedBy, now));
        dbContext.Inventories.Add(new InventoryEntity
        {
            InventoryId = inventoryId,
            AccountId = accountId,
            InventoryType = "BAG",
            InventoryProfile = "GAME",
            SlotCapacity = 9,
            IsEnabled = true,
            CreatedAt = now,
            UpdatedAt = now,
            CreatedBy = updatedBy,
            UpdatedBy = updatedBy,
            IsDeleted = false,
        });
        dbContext.InventoryEntries.AddRange(
            CreateEntry(firstEntryId, inventoryId, 1, "deadlock_test_first", updatedBy, now),
            CreateEntry(secondEntryId, inventoryId, 2, "deadlock_test_second", updatedBy, now));
        await dbContext.SaveChangesAsync();
    }

    private static async Task SeedTwoInventoriesAsync(
        string connectionString,
        Guid accountId,
        Guid firstInventoryId,
        Guid secondInventoryId,
        Guid firstEntryId,
        Guid secondEntryId,
        Guid updatedBy,
        DateTime now)
    {
        await using var dbContext = CreateDbContext(connectionString);
        dbContext.Accounts.Add(CreateAccount(accountId, updatedBy, now));
        dbContext.Inventories.AddRange(
            new InventoryEntity
            {
                InventoryId = firstInventoryId,
                AccountId = accountId,
                InventoryType = "BAG",
                InventoryProfile = "GAME",
                SlotCapacity = 9,
                IsEnabled = true,
                CreatedAt = now,
                UpdatedAt = now,
                CreatedBy = updatedBy,
                UpdatedBy = updatedBy,
                IsDeleted = false,
            },
            new InventoryEntity
            {
                InventoryId = secondInventoryId,
                AccountId = accountId,
                InventoryType = "HOTBAR",
                InventoryProfile = "GAME",
                SlotCapacity = 9,
                IsEnabled = true,
                CreatedAt = now,
                UpdatedAt = now,
                CreatedBy = updatedBy,
                UpdatedBy = updatedBy,
                IsDeleted = false,
            });
        dbContext.InventoryEntries.AddRange(
            CreateEntry(firstEntryId, firstInventoryId, 1, "deadlock_test_first", updatedBy, now),
            CreateEntry(secondEntryId, secondInventoryId, 2, "deadlock_test_second", updatedBy, now));
        await dbContext.SaveChangesAsync();
    }

    private static InventoryEntryEntity CreateEntry(
        Guid entryId,
        Guid inventoryId,
        int slotIndex,
        string itemId,
        Guid updatedBy,
        DateTime now) => new()
        {
            InventoryEntryId = entryId,
            InventoryId = inventoryId,
            SlotIndex = slotIndex,
            ItemCategory = "MATERIAL",
            ItemId = itemId,
            Quantity = 1,
            CreatedAt = now,
            UpdatedAt = now,
            CreatedBy = updatedBy,
            UpdatedBy = updatedBy,
            IsDeleted = false,
        };

    private static AccountEntity CreateAccount(Guid accountId, Guid updatedBy, DateTime now) => new()
    {
        Uuid = accountId,
        UserId = Guid.NewGuid(),
        AccountName = "inventory-sqlserver-integration",
        SlotIndex = 0,
        IsActive = true,
        Mode = 0,
        MenuShortcutsJson = "{}",
        Level = 1,
        ClassId = "adventurer",
        ClassLevel = 1,
        CreatedAt = now,
        UpdatedAt = now,
        CreatedBy = updatedBy,
        UpdatedBy = updatedBy,
        IsDeleted = false,
    };

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
        await ExecuteAsync(
            connection,
            $"ALTER DATABASE {QuoteIdentifier(databaseName)} SET SINGLE_USER WITH ROLLBACK IMMEDIATE; DROP DATABASE {QuoteIdentifier(databaseName)}");
    }

    private static async Task CreateInventorySchemaAsync(string connectionString)
    {
        await using var connection = new SqlConnection(connectionString);
        await connection.OpenAsync();
        await ExecuteAsync(connection, """
            CREATE TABLE dbo.account (
                uuid uniqueidentifier NOT NULL PRIMARY KEY,
                user_id uniqueidentifier NOT NULL,
                account_name nvarchar(max) NOT NULL,
                slot_index int NOT NULL,
                is_active bit NOT NULL,
                mode tinyint NOT NULL,
                menu_shortcuts_json nvarchar(max) NOT NULL,
                level int NOT NULL,
                total_experience bigint NOT NULL,
                class_id nvarchar(100) NOT NULL,
                class_level int NOT NULL,
                class_experience bigint NOT NULL,
                created_at datetime2(3) NOT NULL,
                updated_at datetime2(3) NOT NULL,
                created_by uniqueidentifier NOT NULL,
                updated_by uniqueidentifier NOT NULL,
                is_deleted bit NOT NULL
            );
            CREATE TABLE dbo.inventory (
                inventory_id uniqueidentifier NOT NULL PRIMARY KEY,
                account_id uniqueidentifier NOT NULL,
                inventory_type nvarchar(30) NOT NULL,
                inventory_profile nvarchar(30) NOT NULL,
                slot_capacity int NULL,
                is_enabled bit NOT NULL,
                metadata_json nvarchar(max) NULL,
                created_at datetime2(3) NOT NULL,
                updated_at datetime2(3) NOT NULL,
                created_by uniqueidentifier NOT NULL,
                updated_by uniqueidentifier NOT NULL,
                is_deleted bit NOT NULL
            );
            CREATE TABLE dbo.inventory_entry (
                inventory_entry_id uniqueidentifier NOT NULL PRIMARY KEY,
                inventory_id uniqueidentifier NOT NULL,
                slot_index int NULL,
                item_category nvarchar(30) NOT NULL,
                item_id nvarchar(100) NULL,
                instance_type nvarchar(30) NULL,
                instance_id uniqueidentifier NULL,
                quantity bigint NOT NULL,
                metadata_json nvarchar(max) NULL,
                created_at datetime2(3) NOT NULL,
                updated_at datetime2(3) NOT NULL,
                created_by uniqueidentifier NOT NULL,
                updated_by uniqueidentifier NOT NULL,
                is_deleted bit NOT NULL,
                CONSTRAINT FK_inventory_entry_inventory FOREIGN KEY (inventory_id)
                    REFERENCES dbo.inventory(inventory_id)
            );
            CREATE NONCLUSTERED INDEX IX_inventory_account_id
                ON dbo.inventory (account_id);
            CREATE NONCLUSTERED INDEX IX_inventory_entry_inventory_id
                ON dbo.inventory_entry (inventory_id);
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
        {
            throw new InvalidOperationException("Only this test's random temporary database may be created or removed.");
        }
    }

    private static void EnsureLocalSqlServerInstance(string dataSource)
    {
        if (!string.Equals(dataSource, LocalSqlServerInstance, StringComparison.OrdinalIgnoreCase))
            throw new InvalidOperationException("SQL Server integration tests may only connect to localhost\\SQLEXPRESS.");
    }

    private static string QuoteIdentifier(string value) => "[" + value.Replace("]", "]]", StringComparison.Ordinal) + "]";

    private static async Task ExecuteAsync(SqlConnection connection, string commandText)
    {
        await using var command = new SqlCommand(commandText, connection);
        await command.ExecuteNonQueryAsync();
    }
}
