using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using System.Text.RegularExpressions;
using Xunit;

namespace AstralRecordApi.Tests.Repositories;

public sealed partial class PlayerStateSnapshotRepositoryTests
{
    // AR-CODE-010: execute the actual CHECK predicate/filter from the authoritative initial DDL and table definition.
    [Theory]
    [InlineData("init.sql")]
    [InlineData("inventory_entry.md")]
    public async Task InventoryDdl_AcceptsAuthoritativeEquipmentItemIdsAndRejectsIncompletePairs(string file)
    {
        var ddl = await File.ReadAllTextAsync(Path.Combine(AppContext.BaseDirectory, "Ddl", file));
        var predicate = Regex.Match(ddl, @"CONSTRAINT \[CK_inventory_entry_payload\] CHECK \((.*?)\r?\n    \)", RegexOptions.Singleline);
        Assert.True(predicate.Success);
        var filter = Regex.Match(ddl, @"CREATE UNIQUE NONCLUSTERED INDEX \[UX_inventory_entry_inventory_item\].*?WHERE (.*?);", RegexOptions.Singleline);
        Assert.True(filter.Success);
        await using var fixture = await SnapshotFixture.CreateAsync();
        // SQLite runs the same boolean predicate, while the production migration remains SQL Server SQL.
        await using var ddlCommand = fixture.DbContext.Database.GetDbConnection().CreateCommand();
        ddlCommand.CommandText = $"""
            CREATE TABLE payload_contract (item_id TEXT, instance_type TEXT, instance_id TEXT,
                CHECK ({predicate.Groups[1].Value}));
            DROP INDEX test_inventory_stack;
            CREATE UNIQUE INDEX test_inventory_stack ON inventory_entry (inventory_id, item_id)
                WHERE {filter.Groups[1].Value};
            """;
        await ddlCommand.ExecuteNonQueryAsync();
        await fixture.DbContext.Database.ExecuteSqlRawAsync("""
            INSERT INTO payload_contract VALUES ('iron_sword', 'EQUIPMENT', 'owned-id');
            INSERT INTO payload_contract VALUES (NULL, 'EQUIPMENT', 'legacy-id');
            INSERT INTO payload_contract VALUES ('gold', NULL, NULL);
            """);
        await Assert.ThrowsAsync<SqliteException>(() => fixture.DbContext.Database.ExecuteSqlRawAsync(
            "INSERT INTO payload_contract VALUES ('iron_sword', 'EQUIPMENT', NULL);"));
        await Assert.ThrowsAsync<SqliteException>(() => fixture.DbContext.Database.ExecuteSqlRawAsync(
            "INSERT INTO payload_contract VALUES (NULL, NULL, NULL);"));

        var first = CreateEquipment(Guid.NewGuid(), fixture);
        var second = CreateEquipment(Guid.NewGuid(), fixture);
        fixture.DbContext.EquipmentInstances.AddRange(first, second);
        await fixture.DbContext.SaveChangesAsync();
        var result = await new PlayerStateSnapshotRepository(fixture.DbContext).SaveAsync(new PlayerStateSnapshotSaveRequest
        {
            SnapshotId = Guid.NewGuid(), AccountId = fixture.AccountId, UpdatedBy = fixture.AccountId,
            Inventories = [new PlayerStateInventorySnapshot
            {
                InventoryId = fixture.SecondInventoryId,
                Entries = new[] { first, second }.Select(e => new PlayerStateInventoryEntrySnapshot
                { InventoryEntryId = Guid.NewGuid(), InstanceId = e.EquipmentInstanceId, InstanceType = "EQUIPMENT", ItemCategory = "EQUIPMENT", Quantity = 1 }).ToArray(),
            }],
        });
        Assert.True(result.Succeeded, result.Detail);
        var entries = await fixture.DbContext.InventoryEntries.AsNoTracking().Where(e => e.InventoryId == fixture.SecondInventoryId).ToListAsync();
        Assert.Equal(2, entries.Count);
        foreach (var entry in entries)
        {
            Assert.Equal("iron_sword", entry.ItemId);
            await fixture.DbContext.Database.ExecuteSqlInterpolatedAsync($"INSERT INTO payload_contract VALUES ({entry.ItemId}, {entry.InstanceType}, {entry.InstanceId!.Value.ToString()});");
        }
    }
}
