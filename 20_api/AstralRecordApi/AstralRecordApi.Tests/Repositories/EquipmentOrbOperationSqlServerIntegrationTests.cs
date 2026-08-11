using System.Collections.Concurrent;
using System.Text.RegularExpressions;
using System.Text.Json;
using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.Data.SqlClient;
using Microsoft.EntityFrameworkCore;
using Xunit;

namespace AstralRecordApi.Tests.Repositories;

/// <summary>
/// Production SQL Server の UPDLOCK/HOLDLOCK 分岐を固定する排他・冪等性テスト。
/// localhost\SQLEXPRESS 上のランダムな一時DB以外は作成・削除しない。
/// </summary>
[Trait("Category", "SqlServerIntegration")]
public class EquipmentOrbOperationSqlServerIntegrationTests
{
    private const string DatabasePrefix = "AstralRecordOrbIntegration_";
    private const string LocalSqlServerInstance = @"localhost\SQLEXPRESS";
    private const string SqlServerOptInEnvironmentVariable = "ASTRALRECORD_RUN_SQLSERVER_INTEGRATION";

    [Theory]
    [InlineData("FILL_ONE_EMPTY", 2, 0, 2, 2)]
    [InlineData("FILL_ALL_EMPTY", 2, 0, 1, 2)]
    [InlineData("OVERWRITE_RANDOM", 1, 1, 2, 1)]
    public async Task ConcurrentEnchantOperations_SerializePaymentAndUniqueEffects(
        string operation,
        int maxSlots,
        int initialEnchantCount,
        int expectedAppliedCount,
        int expectedFinalEnchantCount)
    {
        if (!SqlServerIntegrationEnabled()) return;
        await using var harness = await SqlServerHarness.CreateAsync(
            maxEnchantSlots: maxSlots,
            initialEnchantCount: initialEnchantCount);
        harness.SetEnchantMaster(CreateEnchantMaster());
        var firstOrb = await harness.AddOrbAsync("fill_orb_a", new ItemOrbEffectResponse
        {
            Type = "ENCHANT",
            EnchantMasterId = "enchant:enchant001",
            EnchantOperation = operation,
        });
        var secondOrb = await harness.AddOrbAsync("fill_orb_b", new ItemOrbEffectResponse
        {
            Type = "ENCHANT",
            EnchantMasterId = "enchant:enchant001",
            EnchantOperation = operation,
        });
        using var start = new Barrier(2);

        var first = Task.Run(async () =>
        {
            start.SignalAndWait();
            return await harness.ExecuteAsync(Guid.NewGuid(), "fill_orb_a", firstOrb);
        });
        var second = Task.Run(async () =>
        {
            start.SignalAndWait();
            return await harness.ExecuteAsync(Guid.NewGuid(), "fill_orb_b", secondOrb);
        });

        var results = await Task.WhenAll(first, second);

        Assert.Equal(expectedAppliedCount, results.Count(result => result.Result == "APPLIED"));
        Assert.Equal(expectedAppliedCount, results.Count(result => result.PaymentConsumed));
        Assert.Equal(expectedFinalEnchantCount, await harness.CountEnchantsAsync());
        Assert.Equal(expectedFinalEnchantCount, await harness.CountDistinctEnchantEffectsAsync());
        var deletedOrbs = await Task.WhenAll(
            harness.IsEntryDeletedAsync(firstOrb),
            harness.IsEntryDeletedAsync(secondOrb));
        Assert.Equal(expectedAppliedCount, deletedOrbs.Count(deleted => deleted));
        Assert.Equal(2, await harness.CountLedgersAsync());
    }

    [Fact]
    public async Task ConcurrentSameOperationId_ReplaysOnePaymentAndOneMutation()
    {
        if (!SqlServerIntegrationEnabled()) return;
        await using var harness = await SqlServerHarness.CreateAsync(durabilityValue: 40);
        var orb = await harness.AddOrbAsync("repair_replay_orb", new ItemOrbEffectResponse
        {
            Type = "REPAIR",
            RepairFull = true,
        });
        var operationId = Guid.NewGuid();
        using var start = new Barrier(2);

        var first = Task.Run(async () =>
        {
            start.SignalAndWait();
            return await harness.ExecuteAsync(operationId, "repair_replay_orb", orb);
        });
        var second = Task.Run(async () =>
        {
            start.SignalAndWait();
            return await harness.ExecuteAsync(operationId, "repair_replay_orb", orb);
        });

        var results = await Task.WhenAll(first, second);

        Assert.All(results, result =>
        {
            Assert.Equal("APPLIED", result.Result);
            Assert.True(result.PaymentConsumed);
            Assert.Equal(100, result.Equipment!.DurabilityValue);
        });
        Assert.True(await harness.IsEntryDeletedAsync(orb));
        Assert.Equal(1, await harness.CountLedgersAsync());
        Assert.Equal(100, (await harness.GetEquipmentAsync()).DurabilityValue);
    }

    [Fact]
    public async Task DurabilityUpdateConcurrentWithEnhance_DoesNotRollBackOrbColumns()
    {
        if (!SqlServerIntegrationEnabled()) return;
        await using var harness = await SqlServerHarness.CreateAsync(durabilityValue: 80);
        var orb = await harness.AddOrbAsync("enhance_concurrent_orb", new ItemOrbEffectResponse
        {
            Type = "ENHANCE",
            TargetSlots = ["WEAPON"],
        });
        using var start = new Barrier(2);

        var enhance = Task.Run(async () =>
        {
            start.SignalAndWait();
            return await harness.ExecuteAsync(Guid.NewGuid(), "enhance_concurrent_orb", orb);
        });
        var durability = Task.Run(async () =>
        {
            start.SignalAndWait();
            return await harness.UpdateDurabilityAsync(40);
        });

        var enhanceResult = await enhance;
        var durabilityResult = await durability;
        var equipment = await harness.GetEquipmentAsync();

        Assert.Equal("APPLIED", enhanceResult.Result);
        Assert.True(enhanceResult.PaymentConsumed);
        Assert.NotNull(durabilityResult);
        Assert.Equal(1, equipment.EnhanceLevel);
        Assert.Equal(110, equipment.DurabilityMax);
        Assert.Contains(equipment.DurabilityValue, new int?[] { 40, 50 });
        Assert.True(await harness.IsEntryDeletedAsync(orb));
        Assert.Equal(1, await harness.CountLedgersAsync());
    }

    [Fact]
    public async Task OrbMutationConcurrentWithMarketListing_SerializesWithoutDeadlock()
    {
        if (!SqlServerIntegrationEnabled()) return;
        await using var harness = await SqlServerHarness.CreateAsync(durabilityValue: 40);
        var orb = await harness.AddOrbAsync("market_race_repair_orb", new ItemOrbEffectResponse
        {
            Type = "REPAIR",
            RepairFull = true,
        });
        using var start = new Barrier(2);

        var orbTask = Task.Run(async () =>
        {
            start.SignalAndWait();
            return await harness.ExecuteAsync(Guid.NewGuid(), "market_race_repair_orb", orb);
        });
        var listingTask = Task.Run(async () =>
        {
            start.SignalAndWait();
            return await harness.CreateMarketListingWithSharedLockOrderAsync();
        });

        await Task.WhenAll(orbTask, listingTask).WaitAsync(TimeSpan.FromSeconds(20));
        var orbResult = await orbTask;
        var listingResult = await listingTask;

        Assert.True(listingResult);
        Assert.Contains(orbResult.Result, new[] { "APPLIED", "NOT_ELIGIBLE" });
        Assert.Equal(orbResult.Result == "APPLIED", orbResult.PaymentConsumed);
        Assert.Equal(orbResult.PaymentConsumed, await harness.IsEntryDeletedAsync(orb));
        Assert.Equal(1, await harness.CountActiveEquipmentListingsAsync());
    }

    [Fact]
    public async Task EnchantEffectMigration_FailureRollsBackEverySchemaChange()
    {
        if (!SqlServerIntegrationEnabled()) return;
        await using var harness = await SqlServerHarness.CreateAsync();
        await harness.PrepareFailingLegacyEnchantSchemaAsync();

        await Assert.ThrowsAsync<SqlException>(() => harness.ApplyEnchantEffectMigrationAsync());

        var state = await harness.ReadLegacyEnchantSchemaStateAsync();
        Assert.True(state.HasPoolIndex);
        Assert.False(state.HasEnchantMasterId);
        Assert.False(state.HasEffectId);
        Assert.True(state.HasLegacyUniqueConstraint);
        Assert.Equal(1, state.RowCount);
    }

    [Fact]
    public async Task LegacyEnhancementMaterialMarketRows_MigrateCategorySignatureAndSnapshotTogether()
    {
        if (!SqlServerIntegrationEnabled()) return;
        await using var harness = await SqlServerHarness.CreateAsync();
        await harness.SeedLegacyMarketRowsAsync();

        await harness.ApplyOrbMigrationAsync();

        var rows = await harness.ReadMarketMigrationRowsAsync();
        Assert.All(rows, row =>
        {
            Assert.Equal("orb", row.Category);
            Assert.StartsWith("orb|", row.Signature, StringComparison.Ordinal);
        });
        Assert.Equal("orb", rows[0].SnapshotCategory);
        Assert.StartsWith("orb|", rows[0].SnapshotSignature, StringComparison.Ordinal);
        Assert.Null(rows[0].CamelCaseSnapshotCategory);
        Assert.Null(rows[0].CamelCaseSnapshotSignature);
        Assert.Equal("orb", rows[1].SnapshotCategory);
        Assert.StartsWith("orb|", rows[1].SnapshotSignature, StringComparison.Ordinal);
        Assert.Null(rows[1].CamelCaseSnapshotCategory);
        Assert.Null(rows[1].CamelCaseSnapshotSignature);
    }

    private static ItemEquipmentResponse CreateEquipment(int maxEnchantSlots = 2) => new()
    {
        Slot = "WEAPON",
        Durability = new ItemEquipmentDurabilityResponse { Max = 100 },
        Enchant = new ItemEquipmentEnchantResponse { MaxSlots = maxEnchantSlots },
        Enhance = new ItemEquipmentEnhanceResponse
        {
            MaxLevel = 1,
            Levels =
            [
                new ItemEquipmentEnhanceLevelResponse
                {
                    Level = 1,
                    DurabilityBonus = 10,
                    SuccessRate = 1.0F,
                    FailAction = "NONE",
                },
            ],
        },
    };

    private static EnchantMasterResponse CreateEnchantMaster() => new()
    {
        Id = "enchant001",
        Targets =
        [
            new EnchantTargetResponse
            {
                EquipmentType = "WEAPON",
                Entries =
                [
                    new EnchantEntryResponse
                    {
                        EffectId = "sql_attack",
                        Status = "ATTACK",
                        Type = "SCALAR",
                        Value = "1.30",
                        Weight = 30,
                    },
                    new EnchantEntryResponse
                    {
                        EffectId = "sql_critical",
                        Status = "CRITICAL_RATE",
                        Type = "FLAT",
                        Value = "19",
                        Weight = 1,
                    },
                ],
            },
        ],
    };

    private sealed class SqlServerHarness : IAsyncDisposable
    {
        private readonly string databaseName;
        private readonly string connectionString;
        private readonly MutableItemRepository items;
        private readonly MutableEnchantRepository enchants = new();
        private int nextSlot = 1;

        private SqlServerHarness(
            string databaseName,
            string connectionString,
            MutableItemRepository items,
            Guid accountId,
            Guid equipmentInstanceId,
            Guid bagInventoryId)
        {
            this.databaseName = databaseName;
            this.connectionString = connectionString;
            this.items = items;
            AccountId = accountId;
            EquipmentInstanceId = equipmentInstanceId;
            BagInventoryId = bagInventoryId;
        }

        public Guid AccountId { get; }

        public Guid EquipmentInstanceId { get; }

        public Guid BagInventoryId { get; }

        public static async Task<SqlServerHarness> CreateAsync(
            int durabilityValue = 100,
            int maxEnchantSlots = 2,
            int initialEnchantCount = 0)
        {
            var databaseName = DatabasePrefix + Guid.NewGuid().ToString("N");
            var connectionString = BuildConnectionString(databaseName);
            await CreateDatabaseAsync(databaseName);
            try
            {
                await CreateSchemaAsync(connectionString);
                await AssertMarketListingRangeLockIndexAsync(connectionString);
                var accountId = Guid.NewGuid();
                var equipmentInstanceId = Guid.NewGuid();
                var bagInventoryId = Guid.NewGuid();
                var now = DateTime.UtcNow;
                var equipmentItem = new ItemResponse
                {
                    SchemaVersion = 1,
                    Id = "sql_test_equipment",
                    Category = "equipment",
                    Name = "SQL test equipment",
                    Icon = "IRON_SWORD",
                    Rarity = "COMMON",
                    Equipment = CreateEquipment(maxEnchantSlots),
                };
                var items = new MutableItemRepository(equipmentItem);
                await using var dbContext = CreateDbContext(connectionString);
                dbContext.Inventories.Add(new InventoryEntity
                {
                    InventoryId = bagInventoryId,
                    AccountId = accountId,
                    InventoryType = "BAG",
                    InventoryProfile = "GAME",
                    IsEnabled = true,
                    CreatedAt = now,
                    UpdatedAt = now,
                    CreatedBy = accountId,
                    UpdatedBy = accountId,
                });
                dbContext.EquipmentInstances.Add(new EquipmentInstanceEntity
                {
                    EquipmentInstanceId = equipmentInstanceId,
                    AccountId = accountId,
                    ItemId = equipmentItem.Id,
                    DurabilityMax = 100,
                    DurabilityValue = durabilityValue,
                    CreatedAt = now,
                    UpdatedAt = now,
                    CreatedBy = accountId,
                    UpdatedBy = accountId,
                });
                dbContext.InventoryEntries.Add(new InventoryEntryEntity
                {
                    InventoryEntryId = Guid.NewGuid(),
                    InventoryId = bagInventoryId,
                    SlotIndex = 0,
                    ItemCategory = "equipment",
                    ItemId = null,
                    InstanceType = "EQUIPMENT",
                    InstanceId = equipmentInstanceId,
                    Quantity = 1,
                    CreatedAt = now,
                    UpdatedAt = now,
                    CreatedBy = accountId,
                    UpdatedBy = accountId,
                });
                for (var slotIndex = 0; slotIndex < initialEnchantCount; slotIndex++)
                {
                    dbContext.EquipmentInstanceEnchants.Add(new EquipmentInstanceEnchantEntity
                    {
                        EnchantId = Guid.NewGuid(),
                        EquipmentInstanceId = equipmentInstanceId,
                        SlotIndex = slotIndex,
                        EnchantMasterId = "legacy",
                        EffectId = "sql_existing_" + slotIndex,
                        Status = "DEFENSE",
                        Type = "FLAT",
                        Value = 1,
                        CreatedAt = now,
                        UpdatedAt = now,
                        CreatedBy = accountId,
                        UpdatedBy = accountId,
                    });
                }
                await dbContext.SaveChangesAsync();
                return new SqlServerHarness(
                    databaseName,
                    connectionString,
                    items,
                    accountId,
                    equipmentInstanceId,
                    bagInventoryId);
            }
            catch
            {
                await DropDatabaseAsync(databaseName);
                throw;
            }
        }

        public void SetEnchantMaster(EnchantMasterResponse master) => enchants.Set(master);

        public async Task<Guid> AddOrbAsync(string itemId, ItemOrbEffectResponse effect)
        {
            items.Set(new ItemResponse
            {
                SchemaVersion = 1,
                Id = itemId,
                Category = "orb",
                Name = itemId,
                Icon = "AMETHYST_SHARD",
                Rarity = "COMMON",
                Orb = new ItemOrbResponse { Effect = effect },
            });
            var entryId = Guid.NewGuid();
            var now = DateTime.UtcNow;
            await using var dbContext = CreateDbContext(connectionString);
            dbContext.InventoryEntries.Add(new InventoryEntryEntity
            {
                InventoryEntryId = entryId,
                InventoryId = BagInventoryId,
                SlotIndex = nextSlot++,
                ItemCategory = "orb",
                ItemId = itemId,
                Quantity = 1,
                CreatedAt = now,
                UpdatedAt = now,
                CreatedBy = AccountId,
                UpdatedBy = AccountId,
            });
            await dbContext.SaveChangesAsync();
            return entryId;
        }

        public async Task<EquipmentOrbOperationResponse> ExecuteAsync(
            Guid operationId,
            string orbItemId,
            Guid orbEntryId)
        {
            await using var dbContext = CreateDbContext(connectionString);
            return await new EquipmentOrbOperationRepository(dbContext, items, enchants).ExecuteAsync(new()
            {
                OperationId = operationId,
                AccountId = AccountId,
                EquipmentInstanceId = EquipmentInstanceId,
                OrbInventoryEntryId = orbEntryId,
                OrbItemId = orbItemId,
            });
        }

        public async Task<EquipmentInstanceEntity?> UpdateDurabilityAsync(int durabilityValue)
        {
            await using var dbContext = CreateDbContext(connectionString);
            return await new EquipmentRepository(dbContext).UpdateDurabilityAsync(
                EquipmentInstanceId,
                durabilityValue,
                AccountId);
        }

        public async Task<EquipmentInstanceEntity> GetEquipmentAsync()
        {
            await using var dbContext = CreateDbContext(connectionString);
            return await dbContext.EquipmentInstances.AsNoTracking()
                .SingleAsync(instance => instance.EquipmentInstanceId == EquipmentInstanceId);
        }

        public async Task<bool> IsEntryDeletedAsync(Guid entryId)
        {
            await using var dbContext = CreateDbContext(connectionString);
            return await dbContext.InventoryEntries.AsNoTracking()
                .Where(entry => entry.InventoryEntryId == entryId)
                .Select(entry => entry.IsDeleted)
                .SingleAsync();
        }

        public async Task<int> CountEnchantsAsync()
        {
            await using var dbContext = CreateDbContext(connectionString);
            return await dbContext.EquipmentInstanceEnchants.AsNoTracking()
                .CountAsync(enchant => enchant.EquipmentInstanceId == EquipmentInstanceId);
        }

        public async Task<int> CountDistinctEnchantEffectsAsync()
        {
            await using var dbContext = CreateDbContext(connectionString);
            return await dbContext.EquipmentInstanceEnchants.AsNoTracking()
                .Where(enchant => enchant.EquipmentInstanceId == EquipmentInstanceId)
                .Select(enchant => enchant.EffectId)
                .Distinct()
                .CountAsync();
        }

        public async Task<int> CountLedgersAsync()
        {
            await using var dbContext = CreateDbContext(connectionString);
            return await dbContext.EquipmentOrbOperations.AsNoTracking().CountAsync();
        }

        public async Task<bool> CreateMarketListingWithSharedLockOrderAsync()
        {
            await using var dbContext = CreateDbContext(connectionString);
            await using var transaction = await dbContext.Database.BeginTransactionAsync(
                System.Data.IsolationLevel.Serializable);
            if (await MarketListingRangeLock.HasActiveOrSuspendedAsync(
                    dbContext,
                    "EQUIPMENT",
                    EquipmentInstanceId))
                return false;

            var equipment = await dbContext.EquipmentInstances.FromSqlInterpolated($"""
                    SELECT * FROM [dbo].[equipment_instance] WITH (UPDLOCK, HOLDLOCK)
                    WHERE [equipment_instance_id] = {EquipmentInstanceId}
                    """)
                .SingleAsync();
            if (equipment.AccountId != AccountId)
                return false;
            var present = await dbContext.InventoryEntries.FromSqlInterpolated($"""
                    SELECT entry.*
                    FROM [dbo].[inventory_entry] AS entry WITH (UPDLOCK, HOLDLOCK)
                    INNER JOIN [dbo].[inventory] AS inventory WITH (HOLDLOCK)
                        ON inventory.[inventory_id] = entry.[inventory_id]
                    WHERE inventory.[account_id] = {AccountId}
                      AND inventory.[inventory_profile] = 'GAME'
                      AND inventory.[inventory_type] IN ('BAG', 'HOTBAR')
                      AND inventory.[is_enabled] = 1
                      AND inventory.[is_deleted] = 0
                      AND entry.[instance_type] = 'EQUIPMENT'
                      AND entry.[instance_id] = {EquipmentInstanceId}
                      AND entry.[is_deleted] = 0
                    """)
                .AnyAsync();
            if (!present)
                return false;

            await dbContext.Database.ExecuteSqlInterpolatedAsync($"""
                INSERT INTO [dbo].[market_listing]
                    ([listing_id], [item_category], [instance_type], [instance_id], [status],
                     [is_deleted], [version], [updated_at])
                VALUES
                    ({Guid.NewGuid()}, N'equipment', N'EQUIPMENT', {EquipmentInstanceId}, N'ACTIVE',
                     0, 1, SYSUTCDATETIME())
                """);
            await transaction.CommitAsync();
            return true;
        }

        public async Task<int> CountActiveEquipmentListingsAsync()
        {
            await using var connection = new SqlConnection(connectionString);
            await connection.OpenAsync();
            await using var command = new SqlCommand("""
                SELECT COUNT(*) FROM dbo.market_listing
                WHERE instance_type = N'EQUIPMENT'
                  AND instance_id = @instance_id
                  AND status = N'ACTIVE'
                  AND is_deleted = 0;
                """, connection);
            command.Parameters.AddWithValue("@instance_id", EquipmentInstanceId);
            return Convert.ToInt32(await command.ExecuteScalarAsync());
        }

        public async Task PrepareFailingLegacyEnchantSchemaAsync()
        {
            await using var connection = new SqlConnection(connectionString);
            await connection.OpenAsync();
            await EquipmentOrbOperationSqlServerIntegrationTests.ExecuteAsync(connection, """
                DROP TABLE [dbo].[equipment_instance_enchant];
                CREATE TABLE [dbo].[equipment_instance_enchant] (
                    [enchant_id] UNIQUEIDENTIFIER NOT NULL PRIMARY KEY,
                    [equipment_instance_id] UNIQUEIDENTIFIER NOT NULL,
                    [pool_index] INT NOT NULL,
                    CONSTRAINT [UQ_equipment_instance_enchant_pool_index]
                        UNIQUE ([equipment_instance_id], [pool_index])
                );
                INSERT INTO [dbo].[equipment_instance_enchant]
                    ([enchant_id], [equipment_instance_id], [pool_index])
                VALUES (NEWID(), NEWID(), 0);
                EXEC(N'CREATE TRIGGER [dbo].[TR_fail_orb_enchant_migration]
                    ON [dbo].[equipment_instance_enchant]
                    AFTER UPDATE AS
                    THROW 51001, N''forced migration failure'', 1;');
                """);
        }

        public async Task ApplyEnchantEffectMigrationAsync()
        {
            var migrationPath = FindWorkspaceFile(
                "00_docs",
                "40_Database設計書",
                "table-definitions",
                "AstralRecord",
                "migrations",
                "20260810_orb_enchant_effect_id.sql");
            var script = await File.ReadAllTextAsync(migrationPath);
            await using var connection = new SqlConnection(connectionString);
            await connection.OpenAsync();
            foreach (var batch in Regex.Split(
                         script,
                         @"^\s*GO\s*$",
                         RegexOptions.Multiline | RegexOptions.IgnoreCase))
            {
                if (!string.IsNullOrWhiteSpace(batch))
                    await EquipmentOrbOperationSqlServerIntegrationTests.ExecuteAsync(connection, batch);
            }
        }

        public async Task<LegacyEnchantSchemaState> ReadLegacyEnchantSchemaStateAsync()
        {
            await using var connection = new SqlConnection(connectionString);
            await connection.OpenAsync();
            await using var command = new SqlCommand("""
                SELECT
                    CASE WHEN COL_LENGTH(N'dbo.equipment_instance_enchant', N'pool_index') IS NULL THEN 0 ELSE 1 END,
                    CASE WHEN COL_LENGTH(N'dbo.equipment_instance_enchant', N'enchant_master_id') IS NULL THEN 0 ELSE 1 END,
                    CASE WHEN COL_LENGTH(N'dbo.equipment_instance_enchant', N'effect_id') IS NULL THEN 0 ELSE 1 END,
                    CASE WHEN EXISTS (
                        SELECT 1 FROM sys.key_constraints
                        WHERE [name] = N'UQ_equipment_instance_enchant_pool_index'
                          AND [parent_object_id] = OBJECT_ID(N'dbo.equipment_instance_enchant')
                    ) THEN 1 ELSE 0 END,
                    (SELECT COUNT(*) FROM dbo.equipment_instance_enchant);
                """, connection);
            await using var reader = await command.ExecuteReaderAsync();
            Assert.True(await reader.ReadAsync());
            return new LegacyEnchantSchemaState(
                reader.GetInt32(0) == 1,
                reader.GetInt32(1) == 1,
                reader.GetInt32(2) == 1,
                reader.GetInt32(3) == 1,
                reader.GetInt32(4));
        }

        public async Task SeedLegacyMarketRowsAsync()
        {
            await using var connection = new SqlConnection(connectionString);
            await connection.OpenAsync();
            var snapshot = JsonSerializer.Serialize(new MarketPriceQuoteResponse
            {
                ItemCategory = "enhancement_material",
                ItemId = "legacy_orb",
                ValuationSignature = "enhancement_material|legacy_orb|STACK",
            });
            await using var command = new SqlCommand("""
                INSERT INTO dbo.market_listing
                    (listing_id, item_category, valuation_signature, valuation_snapshot_json, version, updated_at)
                VALUES
                    (@listing_id, N'enhancement_material', N'enhancement_material|legacy_orb|STACK', @snapshot, 1, SYSUTCDATETIME());
                INSERT INTO dbo.market_transaction
                    (transaction_id, item_category, valuation_signature, valuation_snapshot_json)
                VALUES
                    (@transaction_id, N'enhancement_material', N'enhancement_material|legacy_orb|STACK', @snapshot);
                INSERT INTO dbo.market_price_snapshot
                    (market_price_snapshot_id, item_category, valuation_signature)
                VALUES
                    (@price_id, N'enhancement_material', N'enhancement_material|legacy_orb|STACK');
                """, connection);
            command.Parameters.AddWithValue("@listing_id", Guid.NewGuid());
            command.Parameters.AddWithValue("@transaction_id", Guid.NewGuid());
            command.Parameters.AddWithValue("@price_id", Guid.NewGuid());
            command.Parameters.AddWithValue("@snapshot", snapshot);
            await command.ExecuteNonQueryAsync();
        }

        public async Task ApplyOrbMigrationAsync()
        {
            var migrationPath = FindWorkspaceFile(
                "00_docs",
                "40_Database設計書",
                "table-definitions",
                "AstralRecord",
                "migrations",
                "20260811_equipment_orb_operation.sql");
            var script = await File.ReadAllTextAsync(migrationPath);
            await using var connection = new SqlConnection(connectionString);
            await connection.OpenAsync();
            foreach (var batch in Regex.Split(
                         script,
                         @"^\s*GO\s*$",
                         RegexOptions.Multiline | RegexOptions.IgnoreCase))
            {
                if (!string.IsNullOrWhiteSpace(batch))
                    await EquipmentOrbOperationSqlServerIntegrationTests.ExecuteAsync(connection, batch);
            }
        }

        public async Task<IReadOnlyList<MarketMigrationRow>> ReadMarketMigrationRowsAsync()
        {
            await using var connection = new SqlConnection(connectionString);
            await connection.OpenAsync();
            await using var command = new SqlCommand("""
                SELECT item_category, valuation_signature,
                    JSON_VALUE(valuation_snapshot_json, '$.ItemCategory'),
                    JSON_VALUE(valuation_snapshot_json, '$.ValuationSignature'),
                    JSON_VALUE(valuation_snapshot_json, '$.itemCategory'),
                    JSON_VALUE(valuation_snapshot_json, '$.valuationSignature'),
                    0 AS sort_order
                FROM dbo.market_listing
                UNION ALL
                SELECT item_category, valuation_signature,
                    JSON_VALUE(valuation_snapshot_json, '$.ItemCategory'),
                    JSON_VALUE(valuation_snapshot_json, '$.ValuationSignature'),
                    JSON_VALUE(valuation_snapshot_json, '$.itemCategory'),
                    JSON_VALUE(valuation_snapshot_json, '$.valuationSignature'),
                    1 AS sort_order
                FROM dbo.market_transaction
                UNION ALL
                SELECT item_category, valuation_signature, NULL, NULL, NULL, NULL, 2 AS sort_order
                FROM dbo.market_price_snapshot
                ORDER BY sort_order;
                """, connection);
            await using var reader = await command.ExecuteReaderAsync();
            var rows = new List<MarketMigrationRow>();
            while (await reader.ReadAsync())
            {
                rows.Add(new MarketMigrationRow(
                    reader.GetString(0),
                    reader.GetString(1),
                    reader.IsDBNull(2) ? null : reader.GetString(2),
                    reader.IsDBNull(3) ? null : reader.GetString(3),
                    reader.IsDBNull(4) ? null : reader.GetString(4),
                    reader.IsDBNull(5) ? null : reader.GetString(5)));
            }
            return rows;
        }

        public async ValueTask DisposeAsync() => await DropDatabaseAsync(databaseName);
    }

    private sealed class MutableItemRepository(params ItemResponse[] initial) : IItemRepository
    {
        private readonly ConcurrentDictionary<string, ItemResponse> values =
            new(initial.ToDictionary(item => item.Id, StringComparer.OrdinalIgnoreCase), StringComparer.OrdinalIgnoreCase);

        public IReadOnlyList<ItemSummaryResponse> GetAllSummaries() => [];

        public ItemResponse? GetById(string itemId) =>
            values.TryGetValue(itemId.Trim(), out var item) ? item : null;

        public void Set(ItemResponse item) => values[item.Id] = item;
    }

    private sealed class MutableEnchantRepository : IEnchantRepository
    {
        private EnchantMasterResponse? master;

        public EnchantMasterResponse? GetById(string enchantMasterId)
        {
            var normalized = enchantMasterId.StartsWith("enchant:", StringComparison.OrdinalIgnoreCase)
                ? enchantMasterId["enchant:".Length..]
                : enchantMasterId;
            return master is not null && string.Equals(master.Id, normalized, StringComparison.OrdinalIgnoreCase)
                ? master
                : null;
        }

        public void Set(EnchantMasterResponse value) => master = value;
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
            CREATE TABLE dbo.inventory (
                inventory_id uniqueidentifier NOT NULL PRIMARY KEY,
                account_id uniqueidentifier NOT NULL,
                inventory_type nvarchar(32) NOT NULL,
                inventory_profile nvarchar(32) NOT NULL,
                slot_capacity int NULL,
                is_enabled bit NOT NULL,
                metadata_json nvarchar(max) NULL,
                created_at datetime2 NOT NULL,
                updated_at datetime2 NOT NULL,
                created_by uniqueidentifier NOT NULL,
                updated_by uniqueidentifier NOT NULL,
                is_deleted bit NOT NULL
            );
            CREATE TABLE dbo.inventory_entry (
                inventory_entry_id uniqueidentifier NOT NULL PRIMARY KEY,
                inventory_id uniqueidentifier NOT NULL,
                slot_index int NULL,
                item_category nvarchar(100) NOT NULL,
                item_id nvarchar(128) NULL,
                instance_type nvarchar(32) NULL,
                instance_id uniqueidentifier NULL,
                quantity bigint NOT NULL,
                metadata_json nvarchar(max) NULL,
                created_at datetime2 NOT NULL,
                updated_at datetime2 NOT NULL,
                created_by uniqueidentifier NOT NULL,
                updated_by uniqueidentifier NOT NULL,
                is_deleted bit NOT NULL,
                CONSTRAINT CK_orb_test_inventory_entry_payload CHECK (
                    (item_id IS NOT NULL AND instance_type IS NULL AND instance_id IS NULL)
                    OR (item_id IS NULL AND instance_type IS NOT NULL AND instance_id IS NOT NULL)
                )
            );
            CREATE INDEX IX_orb_test_inventory_entry_inventory ON dbo.inventory_entry(inventory_id);
            CREATE TABLE dbo.equipment_instance (
                equipment_instance_id uniqueidentifier NOT NULL PRIMARY KEY,
                account_id uniqueidentifier NOT NULL,
                item_id nvarchar(128) NOT NULL,
                enhance_level int NOT NULL,
                rune_max_slots int NOT NULL,
                transcendence_rank int NOT NULL,
                durability_max int NULL,
                durability_value int NULL,
                created_at datetime2 NOT NULL,
                updated_at datetime2 NOT NULL,
                created_by uniqueidentifier NOT NULL,
                updated_by uniqueidentifier NOT NULL,
                is_deleted bit NOT NULL
            );
            CREATE TABLE dbo.equipment_loadout (
                equipment_loadout_id uniqueidentifier NOT NULL PRIMARY KEY,
                account_id uniqueidentifier NOT NULL,
                loadout_profile nvarchar(32) NOT NULL,
                loadout_name nvarchar(128) NOT NULL,
                sort_order int NOT NULL,
                is_active bit NOT NULL,
                metadata_json nvarchar(max) NULL,
                created_at datetime2 NOT NULL,
                updated_at datetime2 NOT NULL,
                created_by uniqueidentifier NOT NULL,
                updated_by uniqueidentifier NOT NULL,
                is_deleted bit NOT NULL
            );
            CREATE TABLE dbo.equipment_loadout_slot (
                equipment_loadout_slot_id uniqueidentifier NOT NULL PRIMARY KEY,
                equipment_loadout_id uniqueidentifier NOT NULL,
                slot_type nvarchar(32) NOT NULL,
                slot_index int NOT NULL,
                equipment_instance_id uniqueidentifier NOT NULL,
                created_at datetime2 NOT NULL,
                updated_at datetime2 NOT NULL,
                created_by uniqueidentifier NOT NULL,
                updated_by uniqueidentifier NOT NULL,
                is_deleted bit NOT NULL
            );
            CREATE TABLE dbo.equipment_instance_stat_roll (
                stat_roll_id uniqueidentifier NOT NULL PRIMARY KEY,
                equipment_instance_id uniqueidentifier NOT NULL,
                status nvarchar(128) NOT NULL,
                random_min nvarchar(128) NOT NULL,
                random_max nvarchar(128) NOT NULL,
                sort_order int NOT NULL,
                created_at datetime2 NOT NULL,
                updated_at datetime2 NOT NULL,
                created_by uniqueidentifier NOT NULL,
                updated_by uniqueidentifier NOT NULL
            );
            CREATE TABLE dbo.equipment_instance_enchant (
                enchant_id uniqueidentifier NOT NULL PRIMARY KEY,
                equipment_instance_id uniqueidentifier NOT NULL,
                slot_index int NOT NULL,
                enchant_master_id nvarchar(128) NOT NULL,
                effect_id nvarchar(128) NOT NULL,
                status nvarchar(128) NOT NULL,
                type nvarchar(32) NOT NULL,
                value decimal(18,4) NOT NULL,
                created_at datetime2 NOT NULL,
                updated_at datetime2 NOT NULL,
                created_by uniqueidentifier NOT NULL,
                updated_by uniqueidentifier NOT NULL,
                CONSTRAINT UX_orb_test_enchant_slot UNIQUE(equipment_instance_id, slot_index),
                CONSTRAINT UX_orb_test_enchant_effect UNIQUE(equipment_instance_id, effect_id)
            );
            CREATE TABLE dbo.equipment_instance_rune (
                rune_id uniqueidentifier NOT NULL PRIMARY KEY,
                equipment_instance_id uniqueidentifier NOT NULL,
                rune_instance_id uniqueidentifier NULL,
                slot_index int NOT NULL,
                item_id nvarchar(128) NOT NULL,
                created_at datetime2 NOT NULL,
                updated_at datetime2 NOT NULL,
                created_by uniqueidentifier NOT NULL,
                updated_by uniqueidentifier NOT NULL
            );
            CREATE TABLE dbo.equipment_orb_operation (
                operation_id uniqueidentifier NOT NULL PRIMARY KEY,
                account_id uniqueidentifier NOT NULL,
                equipment_instance_id uniqueidentifier NOT NULL,
                orb_inventory_entry_id uniqueidentifier NOT NULL,
                orb_item_id nvarchar(128) NOT NULL,
                operation_type nvarchar(32) NOT NULL,
                request_hash char(64) NOT NULL,
                result_code nvarchar(32) NOT NULL,
                result_payload_json nvarchar(max) NOT NULL,
                payment_consumed bit NOT NULL,
                affected_inventory_entry_ids_json nvarchar(max) NOT NULL,
                created_at datetime2 NOT NULL,
                completed_at datetime2 NOT NULL,
                created_by uniqueidentifier NOT NULL
            );
            CREATE INDEX IX_orb_test_operation_account_created
                ON dbo.equipment_orb_operation(account_id, created_at);
            CREATE TABLE dbo.market_listing (
                listing_id uniqueidentifier NOT NULL PRIMARY KEY,
                item_category nvarchar(100) NOT NULL,
                instance_type nvarchar(32) NULL,
                instance_id uniqueidentifier NULL,
                valuation_signature nvarchar(300) NULL,
                valuation_snapshot_json nvarchar(max) NULL,
                status nvarchar(32) NOT NULL CONSTRAINT DF_orb_test_market_status DEFAULT N'ACTIVE',
                is_deleted bit NOT NULL CONSTRAINT DF_orb_test_market_deleted DEFAULT 0,
                version int NOT NULL,
                updated_at datetime2 NOT NULL
            );
            CREATE INDEX IX_market_listing_instance_active_status
                ON dbo.market_listing(instance_type, instance_id, is_deleted, status);
            CREATE TABLE dbo.market_transaction (
                transaction_id uniqueidentifier NOT NULL PRIMARY KEY,
                item_category nvarchar(100) NOT NULL,
                valuation_signature nvarchar(300) NULL,
                valuation_snapshot_json nvarchar(max) NULL
            );
            CREATE TABLE dbo.market_price_snapshot (
                market_price_snapshot_id uniqueidentifier NOT NULL PRIMARY KEY,
                item_category nvarchar(100) NOT NULL,
                valuation_signature nvarchar(300) NULL
            );
            """);
    }

    private static async Task AssertMarketListingRangeLockIndexAsync(string connectionString)
    {
        await using var connection = new SqlConnection(connectionString);
        await connection.OpenAsync();
        await using var command = new SqlCommand("""
            SELECT [column].[name]
            FROM sys.indexes AS [index]
            INNER JOIN sys.index_columns AS [index_column]
                ON [index_column].[object_id] = [index].[object_id]
               AND [index_column].[index_id] = [index].[index_id]
            INNER JOIN sys.columns AS [column]
                ON [column].[object_id] = [index_column].[object_id]
               AND [column].[column_id] = [index_column].[column_id]
            WHERE [index].[object_id] = OBJECT_ID(N'dbo.market_listing')
              AND [index].[name] = N'IX_market_listing_instance_active_status'
              AND [index_column].[key_ordinal] > 0
            ORDER BY [index_column].[key_ordinal];
            """, connection);
        await using var reader = await command.ExecuteReaderAsync();
        var columns = new List<string>();
        while (await reader.ReadAsync())
            columns.Add(reader.GetString(0));
        Assert.Equal(
            ["instance_type", "instance_id", "is_deleted", "status"],
            columns);
    }

    private static string FindWorkspaceFile(params string[] relativeParts)
    {
        DirectoryInfo? current = new(AppContext.BaseDirectory);
        while (current is not null)
        {
            var candidate = relativeParts.Aggregate(current.FullName, Path.Combine);
            if (File.Exists(candidate))
                return candidate;
            current = current.Parent;
        }
        throw new FileNotFoundException("Workspace migration file was not found.");
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

    // xUnit v2 runner does not recognize SkipException.ForSkip as a dynamic skip in this project.
    // Keep the tests compiled in the default suite and make actual localhost SQL I/O explicitly opt-in.
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

    private sealed record MarketMigrationRow(
        string Category,
        string Signature,
        string? SnapshotCategory,
        string? SnapshotSignature,
        string? CamelCaseSnapshotCategory,
        string? CamelCaseSnapshotSignature);

    private sealed record LegacyEnchantSchemaState(
        bool HasPoolIndex,
        bool HasEnchantMasterId,
        bool HasEffectId,
        bool HasLegacyUniqueConstraint,
        int RowCount);
}
