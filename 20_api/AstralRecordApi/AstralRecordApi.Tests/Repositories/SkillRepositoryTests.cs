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
                Path.Combine(ResolveWorkspaceRoot(), "40_filebase", "30.features.skill", "v1.adventurer_smash.yml"),
                "skill",
                null);
        }

        await using var dbContext = new MasterDataDbContext(options);
        var repository = new SkillRepository(dbContext);

        var summaries = repository.GetAllSummaries();

        var summary = Assert.Single(summaries);
        Assert.Equal("adventurer_smash", summary.Id);
        Assert.Equal("IRON_AXE", summary.Icon);
        Assert.Equal(["active", "melee", "adventurer"], summary.Tags);
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
                Path.Combine(ResolveWorkspaceRoot(), "40_filebase", "30.features.skill", "v1.adventurer_meditation.yml"),
                "skill",
                null);
        }

        await using var dbContext = new MasterDataDbContext(options);
        var repository = new SkillRepository(dbContext);

        var skill = repository.GetById("adventurer_meditation");

        Assert.NotNull(skill);
        Assert.Equal("adventurer_meditation", skill.Id);
        Assert.Equal("AMETHYST_SHARD", skill.Icon);
        Assert.NotNull(skill.Passive);
        Assert.True(skill.Passive!.BindRequired);
        Assert.True(skill.Params.ContainsKey("regenMultiplier"));
    }

    /// <summary>
    /// 設計入力: 00_docs/20_API設計書/feature/11-skill/3-エンドポイント仕様
    /// 検証契約: skill masterから決定的IDの非スタックgemを仮想生成し、未指定取引設定を禁止側へ倒す。
    /// </summary>
    [Fact]
    public async Task ItemRepository_GeneratesDeterministicUnstackableSkillGemFromSkillMaster()
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
                Path.Combine(ResolveWorkspaceRoot(), "40_filebase", "30.features.skill", "v1.adventurer_smash.yml"),
                "skill",
                null);
        }
        await using var dbContext = new MasterDataDbContext(options);
        var repository = new ItemRepository(dbContext);

        var summary = Assert.Single(repository.GetAllSummaries());
        var gem = repository.GetById("00_skill_gem_adventurer_smash");

        Assert.Equal("00_skill_gem_adventurer_smash", summary.Id);
        Assert.Equal("skill_gem", summary.Category);
        Assert.NotNull(gem);
        Assert.Equal("skill_gem", gem.Category);
        Assert.Equal("&bスマッシュジェム", gem.Name);
        Assert.Equal("IRON_AXE", gem.Icon);
        Assert.Equal("COMMON", gem.Rarity);
        Assert.Equal(0, gem.MaxStack);
        Assert.True(gem.UnTradeable);
        Assert.True(gem.UnSellable);
        Assert.Equal("adventurer_smash", gem.SkillGem!.SkillId);
        Assert.Contains(gem.Lore, line => line.Contains("左クリック", StringComparison.Ordinal));
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
