using AstralRecordApi.Data;
using AstralRecordApi.Repositories;
using AstralRecordApi.Tests.TestSupport;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Xunit;

namespace AstralRecordApi.Tests.Repositories;

public class ClassRepositoryTests
{
    [Fact]
    public async Task GetAllAndGetById_ReturnConfiguredShortName()
    {
        await using var connection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync();

        var options = new DbContextOptionsBuilder<MasterDataDbContext>()
            .UseSqlite(connection)
            .Options;

        await using (var setupContext = new MasterDataDbContext(options))
        {
            await MasterDataTestSeed.CreateSchemaAsync(setupContext);
            await MasterDataTestSeed.SeedInlinePayloadAsync(
                setupContext,
                MasterDataTestFixtures.MageClass,
                "class",
                null);
        }

        await using var dbContext = new MasterDataDbContext(options);
        var repository = new ClassRepository(dbContext);

        var summary = Assert.Single(repository.GetAllSummaries());
        var detail = repository.GetById("mage");

        Assert.Equal("&bMAG", summary.ShortName);
        Assert.NotNull(detail);
        Assert.Equal("&bMAG", detail!.ShortName);
    }

}
