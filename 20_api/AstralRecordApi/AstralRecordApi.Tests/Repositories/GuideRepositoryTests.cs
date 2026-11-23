using System.Text.Json;
using AstralRecordApi.Data;
using AstralRecordApi.Repositories;
using AstralRecordApi.Tests.TestSupport;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Xunit;

namespace AstralRecordApi.Tests.Repositories;

public class GuideRepositoryTests
{
    [Theory]
    [InlineData(true)]
    [InlineData(false)]
    public async Task OnlyPublishedChapterParticipatesInListingAndRewardChecks(bool currentChapter)
    {
        await using var connection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync();
        var options = new DbContextOptionsBuilder<MasterDataDbContext>().UseSqlite(connection).Options;
        await using var db = new MasterDataDbContext(options);
        await MasterDataTestSeed.CreateSchemaAsync(db);
        string[] steps = currentChapter
            ? ["open_menu", "receive_welcome_mail", "open_initial_bundle", "buy_starter_weapon",
               "equip_starter_weapon", "defeat_grassboar"]
            : ["login", "open_guide", "defeat_grassboar"];
        await MasterDataTestSeed.SeedInlinePayloadAsync(db, Payload("beginner_onboarding", steps), "guide", null);
        await MasterDataTestSeed.SeedInlinePayloadAsync(db, Payload("first_weapon_upgrade", ["buy"]), "guide", null);

        var repository = new GuideRepository(db);
        Assert.Null(repository.GetById("first_weapon_upgrade"));
        if (currentChapter)
        {
            Assert.Equal("beginner_onboarding", Assert.Single(repository.GetAll()).Id);
            Assert.Equal(6, repository.GetById("beginner_onboarding")!.Steps.Count);
        }
        else
        {
            Assert.Empty(repository.GetAll());
            Assert.Null(repository.GetById("beginner_onboarding"));
        }
    }

    private static string Payload(string id, string[] steps) => JsonSerializer.Serialize(new
    {
        id, schemaVersion = 3, category = "beginner", title = id,
        steps = steps.Select(step => new { id = step, text = step, condition = new { type = "TEST" } })
    });
}
