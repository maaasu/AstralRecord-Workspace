using AstralRecordApi.Data;
using AstralRecordApi.Repositories;
using AstralRecordApi.Tests.TestSupport;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using System.Text.Json;
using Xunit;

namespace AstralRecordApi.Tests.Repositories;

public class MobDefenseEligibleResponseTests
{
    /// <summary>
    /// 設計入力: 00_docs/20_API設計書/feature/16-mob/1-モデル定義/16_1.00-モデル定義.md
    /// JSON の項目順序に関係なく ENEMY だけが参加可能となり、詳細と一覧は同じ値を返す。
    /// </summary>
    [Theory]
    [InlineData("ENEMY", true, true, true)]
    [InlineData("ENEMY", true, false, true)]
    [InlineData("enemy", true, true, true)]
    [InlineData("ENEMY", false, false, false)]
    [InlineData("ENEMY", null, false, false)]
    [InlineData("NPC", true, true, false)]
    [InlineData("BOSS", true, false, false)]
    public async Task DefenseEligible_UsesEnemyCategoryForDetailAndSummary(
        string category,
        bool? rawFlag,
        bool flagBeforeCategory,
        bool expected)
    {
        await using var connection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync();
        var options = new DbContextOptionsBuilder<MasterDataDbContext>()
            .UseSqlite(connection)
            .Options;

        var masterType = category.ToUpperInvariant() switch
        {
            "NPC" => "mob.npc",
            "BOSS" => "mob.boss",
            _ => "mob.enemy",
        };
        await using (var setupContext = new MasterDataDbContext(options))
        {
            await MasterDataTestSeed.CreateSchemaAsync(setupContext);
            await MasterDataTestSeed.SeedInlinePayloadAsync(
                setupContext,
                BuildPayload(category, rawFlag, flagBeforeCategory),
                masterType,
                category);
        }

        await using var dbContext = new MasterDataDbContext(options);
        var repository = new MobRepository(dbContext);
        var detail = repository.GetById("defense_test_mob");
        var summary = Assert.Single(repository.GetAllSummaries());

        Assert.NotNull(detail);
        Assert.Equal(expected, detail!.DefenseEligible);
        Assert.Equal(detail.DefenseEligible, summary.DefenseEligible);

        var responseOptions = new JsonSerializerOptions(JsonSerializerDefaults.Web);
        using var detailJson = JsonDocument.Parse(JsonSerializer.Serialize(detail, responseOptions));
        using var summaryJson = JsonDocument.Parse(JsonSerializer.Serialize(summary, responseOptions));
        Assert.Equal(expected, detailJson.RootElement.GetProperty("defenseEligible").GetBoolean());
        Assert.Equal(expected, summaryJson.RootElement.GetProperty("defenseEligible").GetBoolean());
    }

    private static string BuildPayload(string category, bool? rawFlag, bool flagBeforeCategory)
    {
        var categoryField = $"\"category\":{JsonSerializer.Serialize(category)}";
        var flagField = rawFlag is null ? null : $"\"defenseEligible\":{(rawFlag.Value ? "true" : "false")}";
        var fields = flagBeforeCategory
            ? new[] { flagField, categoryField }
            : new[] { categoryField, flagField };
        var orderedFields = string.Join(",", fields.Where(field => field is not null));
        return $$"""
            {"schemaVersion":1,"id":"defense_test_mob","type":"MOB",{{orderedFields}},"name":"defense test mob","level":1,"entityType":"ZOMBIE"}
            """;
    }
}
