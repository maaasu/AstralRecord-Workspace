using AstralRecordApi.Data;
using AstralRecordApi.Repositories;
using AstralRecordApi.Tests.TestSupport;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using System.Runtime.CompilerServices;
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
            await MasterDataTestSeed.SeedEntryAsync(
                setupContext,
                Path.Combine(ResolveWorkspaceRoot(), "40_filebase", "20.features.class", "v1.mage.yml"),
                "class",
                null);
        }

        await using var dbContext = new MasterDataDbContext(options);
        var repository = new ClassRepository(dbContext);

        var summary = Assert.Single(repository.GetAllSummaries());
        var detail = repository.GetById("mage");

        Assert.Equal("&b魔術師", summary.ShortName);
        Assert.NotNull(detail);
        Assert.Equal("&b魔術師", detail!.ShortName);
    }

    private static string ResolveWorkspaceRoot([CallerFilePath] string currentFile = "")
    {
        var current = new FileInfo(currentFile).Directory;
        while (current is not null)
        {
            if (Directory.Exists(Path.Combine(current.FullName, "40_filebase"))
                && Directory.Exists(Path.Combine(current.FullName, "20_api")))
            {
                return current.FullName;
            }

            current = current.Parent;
        }

        throw new InvalidOperationException("workspace root could not be resolved from the test source path.");
    }
}
