using System.Data.Common;
using AstralRecordApi.Data;
using AstralRecordApi.Repositories;
using AstralRecordApi.Services;
using AstralRecordApi.Tests.TestSupport;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Diagnostics;
using Xunit;

namespace AstralRecordApi.Tests.Repositories;

public class MasterDataCacheRepositoryTests
{
    [Fact]
    public async Task SharedCache_AcrossScopedRepositoriesAvoidsDbCommandsAndRefreshesOnInvalidation()
    {
        await using var connection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync();
        var counter = new Commands();
        var options = new DbContextOptionsBuilder<MasterDataDbContext>().UseSqlite(connection).AddInterceptors(counter).Options;
        await using var db = new MasterDataDbContext(options);
        await MasterDataTestSeed.CreateSchemaAsync(db);
        await MasterDataTestSeed.SeedInlinePayloadAsync(db, MasterDataTestFixtures.AdventurerSmash, "skill", null);
        counter.Reads = 0;
        var cache = new MasterDataCache();
        var first = new SkillRepository(db, cache);
        var detail = await first.GetByIdAsync("adventurer_smash");
        Assert.NotNull(detail);
        Assert.Equal(1, counter.Reads);
        Assert.Equal(0, counter.SyncReads);
        await using var otherDb = new MasterDataDbContext(options);
        var other = new SkillRepository(otherDb, cache);
        Assert.Same(detail, await other.GetByIdAsync("ADVENTURER_SMASH"));
        Assert.Single(await other.GetAllSummariesAsync());
        var page = await other.GetDetailsAsync(0, 100);
        Assert.Same(detail, Assert.Single(page.Items));
        Assert.Null(await other.GetByIdAsync("unknown"));
        Assert.Equal(1, counter.Reads);
        Assert.Equal(0, counter.SyncReads);
        await db.Entries.ExecuteUpdateAsync(setters => setters.SetProperty(entry => entry.IsDeleted, true));
        Assert.Same(detail, await other.GetByIdAsync("adventurer_smash"));
        cache.Invalidate();
        Assert.Null(await other.GetByIdAsync("adventurer_smash"));
        Assert.Empty(await other.GetAllSummariesAsync());
        Assert.NotEqual(page.Generation, (await other.GetDetailsAsync(0, 100)).Generation);
        Assert.Equal(2, counter.Reads);
    }

    [Fact]
    public async Task ItemPages_ExcludeDeletedRecordsButDeletedSigilRemainsResolvable()
    {
        await using var connection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync();
        var options = new DbContextOptionsBuilder<MasterDataDbContext>().UseSqlite(connection).Options;
        await using var db = new MasterDataDbContext(options);
        await MasterDataTestSeed.CreateSchemaAsync(db);
        string Payload(string id) => $$"""{"schemaVersion":1,"id":"{{id}}","name":"星","icon":"PAPER","rarity":"COMMON"}""";
        await MasterDataTestSeed.SeedInlinePayloadAsync(db, Payload("deleted_sigil"), "item", "sigil");
        await MasterDataTestSeed.SeedInlinePayloadAsync(db, Payload("deleted_material"), "item", "material");
        await db.Entries.ExecuteUpdateAsync(setters => setters.SetProperty(entry => entry.IsDeleted, true));
        await MasterDataTestSeed.SeedInlinePayloadAsync(db, Payload("active_material"), "item", "material");
        await MasterDataTestSeed.SeedInlinePayloadAsync(db, Payload("unsupported"), "item", "unsupported");
        var repository = new ItemRepository(db, new MasterDataCache());
        var page = await repository.GetDetailsAsync(0, 100);
        var active = Assert.Single(page.Items);
        Assert.Equal("material", active.Category);
        Assert.Equal("active_material", active.Id);
        Assert.Equal("sigil", (await repository.GetByIdAsync("DELETED_SIGIL"))!.Category);
        Assert.Null(await repository.GetByIdAsync("deleted_material"));
        Assert.Null(await repository.GetByIdAsync("unsupported"));
        Assert.Equal(active.Id, Assert.Single(await repository.GetAllSummariesAsync()).Id);
        Assert.Empty((await repository.GetDetailsAsync(1, 100)).Items);
    }

    private sealed class Commands : DbCommandInterceptor
    {
        public int Reads;
        public int SyncReads;
        public override InterceptionResult<DbDataReader> ReaderExecuting(DbCommand command, CommandEventData eventData, InterceptionResult<DbDataReader> result)
        { Reads++; SyncReads++; return result; }
        public override ValueTask<InterceptionResult<DbDataReader>> ReaderExecutingAsync(DbCommand command, CommandEventData eventData,
            InterceptionResult<DbDataReader> result, CancellationToken cancellationToken = default)
        { Reads++; return ValueTask.FromResult(result); }
    }
}
