using AstralRecordApi.Data;
using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using AstralRecordApi.Tests.TestSupport;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
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
            await MasterDataTestSeed.SeedInlinePayloadAsync(
                setupContext,
                MasterDataTestFixtures.AdventurerSmash,
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
            await MasterDataTestSeed.SeedInlinePayloadAsync(
                setupContext,
                MasterDataTestFixtures.AdventurerMeditation,
                "skill",
                null);
        }

        await using var dbContext = new MasterDataDbContext(options);
        var repository = new SkillRepository(dbContext);

        var skill = repository.GetById("adventurer_meditation");

        Assert.NotNull(skill);
        Assert.Equal("adventurer_meditation", skill.Id);
        Assert.Equal("CAMPFIRE", skill.Icon);
        Assert.NotNull(skill.Passive);
        Assert.True(skill.Passive!.BindRequired);
        Assert.Equal(60L, ((JsonElement)skill.Params["chargeTicks"]!).GetInt64());
        Assert.Equal(2.0D, ((JsonElement)skill.Params["initialRegenMultiplier"]!).GetDouble());
        Assert.Equal(0.5D, ((JsonElement)skill.Params["regenMultiplierIncrement"]!).GetDouble());
        Assert.Equal(140L, ((JsonElement)skill.Params["activeDurationTicks"]!).GetInt64());
        Assert.Equal("buff:adventurer_meditation", ((JsonElement)skill.Params["buffId"]!).GetString());
        Assert.Equal(10L, ((JsonElement)skill.Params["chargeParticleIntervalTicks"]!).GetInt64());
        Assert.Equal(5L, ((JsonElement)skill.Params["activeParticleIntervalTicks"]!).GetInt64());
        Assert.Equal(40L, ((JsonElement)skill.Params["activeSoundIntervalTicks"]!).GetInt64());
    }

    /// <summary>
    /// 設計入力: 00_docs/50_Filebase設計書/feature/30-skill.md、00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
    /// 検証契約: 固定したクラッシュアロー payload を seed 経路へ投入し、シールドブレイク倍率がLv.1の3.0倍から各レベル0.5ずつ増加してLv.5の5.0倍になる。
    /// </summary>
    [Fact]
    public async Task CrashArrowMaster_ResolvesShieldBreakMultiplierAcrossLevels()
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
                MasterDataTestFixtures.HunterCrashArrow,
                "skill",
                null);
        }

        await using var dbContext = new MasterDataDbContext(options);
        var payload = await dbContext.Entries
            .Where(entry => !entry.IsDeleted && entry.MasterId == "hunter_crash_arrow")
            .Select(entry => entry.PayloadJson)
            .SingleAsync();

        using var document = JsonDocument.Parse(payload);
        var root = document.RootElement;
        var multiplier = root.GetProperty("params")
            .GetProperty("shieldBreakMultiplier")
            .GetDouble();
        var resolvedValues = new List<double> { multiplier };

        foreach (var level in root.GetProperty("levels").EnumerateArray())
        {
            Assert.Equal(resolvedValues.Count + 1, level.GetProperty("level").GetInt32());
            multiplier += level.GetProperty("paramDeltas")
                .GetProperty("shieldBreakMultiplier")
                .GetDouble();
            resolvedValues.Add(multiplier);
        }

        Assert.Equal([3.0D, 3.5D, 4.0D, 4.5D, 5.0D], resolvedValues);
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
            await MasterDataTestSeed.SeedInlinePayloadAsync(
                setupContext,
                MasterDataTestFixtures.AdventurerSmash,
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
        Assert.Contains(gem.Lore, line => line.Contains("購入すると即時反映", StringComparison.Ordinal));
        Assert.Contains(gem.Lore, line => line.Contains("習得済みならレベルアップ", StringComparison.Ordinal));
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

}
