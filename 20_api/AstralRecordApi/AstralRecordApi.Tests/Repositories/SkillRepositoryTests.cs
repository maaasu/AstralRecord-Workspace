using AstralRecordApi.Data;
using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using AstralRecordApi.Tests.TestSupport;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using System.Runtime.CompilerServices;
using System.Text.Json;
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
                Path.Combine(ResolveWorkspaceRoot(), "40_filebase", "30.features.skill", "v1.fire_boost.yml"),
                "skill",
                null);
        }

        await using var dbContext = new MasterDataDbContext(options);
        var repository = new SkillRepository(dbContext);

        var summaries = repository.GetAllSummaries();

        var summary = Assert.Single(summaries);
        Assert.Equal("fire_boost", summary.Id);
        Assert.Equal("BLAZE_POWDER", summary.Icon);
        Assert.Equal(["active", "fire"], summary.Tags);
    }

    [Fact]
    public async Task GetById_ReturnsPassiveSettingsAndNestedParams()
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
                Path.Combine(ResolveWorkspaceRoot(), "40_filebase", "30.features.skill", "v1.iron_will.yml"),
                "skill",
                null);
        }

        await using var dbContext = new MasterDataDbContext(options);
        var repository = new SkillRepository(dbContext);

        var skill = repository.GetById("iron_will");

        Assert.NotNull(skill);
        Assert.Equal("iron_will", skill.Id);
        Assert.Equal("IRON_INGOT", skill.Icon);
        Assert.NotNull(skill.Passive);
        Assert.True(skill.Passive!.BindRequired);
        Assert.True(skill.Params.ContainsKey("defenseFlat"));
    }

    [Fact]
    public void DeserializeSkillPayload_ReturnsFirstClassResourceFields()
    {
        var payloadType = typeof(SkillRepository)
            .Assembly
            .GetType("AstralRecordApi.Repositories.MasterDataPayloadJson", throwOnError: true)!;
        var options = (JsonSerializerOptions)payloadType
            .GetField("Options", System.Reflection.BindingFlags.Public | System.Reflection.BindingFlags.Static)!
            .GetValue(null)!;
        var json = """
            {
              "schemaVersion": 1,
              "id": "blade_wave",
              "type": "SKILL",
              "implementationId": "blade_wave",
              "name": "Blade Wave",
              "resourceType": "ENERGY",
              "resourceCost": 20
            }
            """;

        var skill = JsonSerializer.Deserialize<SkillResponse>(json, options);

        Assert.NotNull(skill);
        Assert.Equal("ENERGY", skill!.ResourceType);
        Assert.Equal(20.0D, skill.ResourceCost);
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
