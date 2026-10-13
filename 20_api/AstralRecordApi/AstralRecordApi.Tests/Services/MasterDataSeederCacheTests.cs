using AstralRecordApi.Data;
using AstralRecordApi.Models;
using AstralRecordApi.Options;
using AstralRecordApi.Repositories;
using AstralRecordApi.Services;
using AstralRecordApi.Tests.TestSupport;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.Options;
using Xunit;

namespace AstralRecordApi.Tests.Services;

public class MasterDataSeederCacheTests
{
    [Theory]
    [InlineData("success")]
    [InlineData("invalid-config")]
    [InlineData("rollback")]
    public async Task Seed_InvalidatesOnlyAfterCommittedMasterChanges(string scenario)
    {
        var root = Path.Combine(Path.GetTempPath(), "master-cache-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(root);
        try
        {
            var config = scenario switch
            {
                "success" => "schemaVersion: 1\ndatabase: []\n",
                "rollback" => "schemaVersion: 1\ndatabase:\n  - name: skill\n    path: skills\n",
                _ => "database: [\n",
            };
            await File.WriteAllTextAsync(Path.Combine(root, "config.yml"), config);
            if (scenario == "rollback")
            {
                Directory.CreateDirectory(Path.Combine(root, "skills"));
                await File.WriteAllTextAsync(Path.Combine(root, "skills", "v1.invalid.yml"),
                    "schemaVersion: 1\nid: invalid\ntype: SKILL\nname: Invalid\n");
            }
            await using var connection = new SqliteConnection("Data Source=:memory:");
            await connection.OpenAsync();
            var options = new DbContextOptionsBuilder<MasterDataDbContext>().UseSqlite(connection).Options;
            await using var db = new MasterDataDbContext(options);
            await db.Database.EnsureCreatedAsync();
            await MasterDataTestSeed.SeedInlinePayloadAsync(db,
                """{"schemaVersion":1,"id":"item_a","name":"星","icon":"PAPER","rarity":"COMMON"}""", "item", "material");
            var cache = new MasterDataCache();
            var repository = new ItemRepository(db, cache);
            var before = await repository.GetDetailsAsync(0, 100);
            Assert.Single(before.Items);
            var seeder = new MasterDataSeeder(db, Microsoft.Extensions.Options.Options.Create(new FileDatabaseOptions { RootPath = root }),
                Microsoft.Extensions.Options.Options.Create(new MasterDataOptions()), NullLogger<MasterDataSeeder>.Instance, cache);
            var result = await seeder.RunAsync(MasterDataSeedTrigger.Manual, MasterDataSeedMode.Diff);
            var after = await repository.GetDetailsAsync(0, 100);
            if (scenario == "success")
            {
                Assert.Equal("SUCCEEDED", result.Status);
                Assert.NotEqual(before.Generation, after.Generation);
                Assert.Empty(after.Items);
            }
            else
            {
                Assert.Equal("FAILED", result.Status);
                Assert.Equal(before.Generation, after.Generation);
                Assert.Single(after.Items);
                Assert.False(await db.Entries.AsNoTracking().Where(entry => entry.MasterId == "item_a").Select(entry => entry.IsDeleted).SingleAsync());
            }
        }
        finally { Directory.Delete(root, recursive: true); }
    }
}
