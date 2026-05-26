using AstralRecordApi.Data;
using AstralRecordApi.Repositories;
using AstralRecordApi.Tests.TestSupport;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Xunit;

namespace AstralRecordApi.Tests.Repositories;

public class SkillRepositoryTests
{
    [Fact]
    public async Task GetAllSummaries_ReturnsIconAndTagsDefinedInSkillPayload()
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
                @"E:\AstralRecord-Workspace\40_filebase\30.features.skill\v1.fire_boost.yml",
                "skill",
                null);
        }

        await using var dbContext = new MasterDataDbContext(options);
        var repository = new SkillRepository(dbContext);

        var summaries = repository.GetAllSummaries();

        var summary = Assert.Single(summaries);
        Assert.Equal("fire_boost", summary.Id);
        Assert.Equal("BLAZE_POWDER", summary.Icon);
        Assert.Equal(["passive", "fire"], summary.Tags);
    }

    [Fact]
    public async Task GetById_ReturnsParamsWithoutFlatteningNestedPayload()
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
                @"E:\AstralRecord-Workspace\40_filebase\30.features.skill\v1.iron_will.yml",
                "skill",
                null);
        }

        await using var dbContext = new MasterDataDbContext(options);
        var repository = new SkillRepository(dbContext);

        var skill = repository.GetById("iron_will");

        Assert.NotNull(skill);
        Assert.Equal("iron_will", skill.Id);
        Assert.Equal("IRON_INGOT", skill.Icon);
        Assert.True(skill.Params.ContainsKey("damageReduction"));
    }
}
