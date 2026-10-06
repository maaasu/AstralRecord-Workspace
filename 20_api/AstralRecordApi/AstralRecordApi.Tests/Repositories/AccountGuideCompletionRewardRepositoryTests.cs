using System.Text.Json;
using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Configuration;
using Xunit;

namespace AstralRecordApi.Tests.Repositories;

public sealed class AccountGuideCompletionRewardRepositoryTests
{
    [Fact]
    public async Task UnconfiguredRewardDoesNotConsumeClaim()
    {
        await using var fixture = await Fixture.CreateAsync();
        await fixture.CompleteAsync("guide-a", "step-a");
        await using var context = fixture.NewContext();
        var repository = NewRepository(context, [Guide("guide-a", "step-a")], configured: false);

        var status = await repository.GetAsync(fixture.AccountId);
        Assert.True(status.AllCompleted);
        Assert.False(status.Configured);
        Assert.False(status.CanClaim);
        await Assert.ThrowsAsync<AccountGuideCompletionRewardUnavailableException>(
            () => repository.ClaimAsync(fixture.AccountId, fixture.UserId));
        Assert.Empty(await context.AccountGuideCompletionRewards.ToListAsync());
        Assert.Empty(await context.PlayerMailDeliveries.ToListAsync());
    }

    [Theory]
    [InlineData("unknown-item", "MATERIAL", "1")]
    [InlineData("99a00001", "ORB", "3000")]
    [InlineData("99a99999", "CURRENCY", "3000")]
    [InlineData("reward-item", "ORB", "1")]
    [InlineData("reward-item", "MATERIAL", "0")]
    [InlineData("reward-pet", "PET", "1")]
    [InlineData("reward-egg", "PET_EGG", "1")]
    public async Task InvalidRewardConfigurationDoesNotConsumeClaim(
        string itemId, string category, string amount)
    {
        await using var fixture = await Fixture.CreateAsync();
        await fixture.CompleteAsync("guide-a", "step-a");
        await using var context = fixture.NewContext();
        var repository = NewRepository(context, [Guide("guide-a", "step-a")],
            itemId: itemId, category: category, amount: amount);

        Assert.False((await repository.GetAsync(fixture.AccountId)).Configured);
        await Assert.ThrowsAsync<AccountGuideCompletionRewardUnavailableException>(
            () => repository.ClaimAsync(fixture.AccountId, fixture.UserId));
        Assert.Empty(await context.AccountGuideCompletionRewards.ToListAsync());
        Assert.Empty(await context.PlayerMailDeliveries.ToListAsync());
    }

    [Fact]
    public async Task ClaimRequiresEveryCurrentGuideStepAndNonEmptyMaster()
    {
        await using var fixture = await Fixture.CreateAsync();
        await fixture.CompleteAsync("guide-a", "step-a");
        await using var context = fixture.NewContext();
        var repository = NewRepository(context, [Guide("guide-a", "step-a"), Guide("guide-b", "step-b")]);

        Assert.False((await repository.GetAsync(fixture.AccountId)).AllCompleted);
        await Assert.ThrowsAsync<AccountGuideCompletionRewardUnavailableException>(
            () => repository.ClaimAsync(fixture.AccountId, fixture.UserId));
        Assert.False((await NewRepository(context, [])
            .GetAsync(fixture.AccountId)).AllCompleted);
        Assert.False((await NewRepository(context, [Guide("empty")])
            .GetAsync(fixture.AccountId)).AllCompleted);

        await fixture.CompleteAsync("guide-b", "step-b");
        Assert.True((await repository.GetAsync(fixture.AccountId)).CanClaim);
    }

    [Fact]
    public async Task ClaimPersistsMailAndReceiptTogetherAndRetryDoesNotDuplicate()
    {
        await using var fixture = await Fixture.CreateAsync();
        await fixture.CompleteAsync("guide-a", "step-a");
        await using (var firstContext = fixture.NewContext())
        {
            var first = await NewRepository(firstContext, [Guide("guide-a", "step-a")])
                .ClaimAsync(fixture.AccountId, fixture.UserId);
            Assert.True(first.Claimed);
            Assert.False(first.CanClaim);
            Assert.Equal($"guide-completion-{fixture.AccountId:N}", first.MailId);
        }

        await using (var retryContext = fixture.NewContext())
        {
            var retry = await NewRepository(retryContext, [Guide("guide-a", "step-a")])
                .ClaimAsync(fixture.AccountId, fixture.UserId);
            Assert.True(retry.Claimed);
        }

        await using var verification = fixture.NewContext();
        var receipt = Assert.Single(await verification.AccountGuideCompletionRewards.ToListAsync());
        var mail = Assert.Single(await verification.PlayerMailDeliveries.ToListAsync());
        Assert.Equal(mail.MailId, receipt.MailId);
        Assert.Contains("reward-item", mail.PayloadJson);
        Assert.Single(await verification.AccountGuideStepProgresses.ToListAsync());

        // Newly added steps change current completion, but do not reset the one-time claim.
        var afterAddition = await NewRepository(verification,
            [Guide("guide-a", "step-a"), Guide("guide-new", "step-new")])
            .GetAsync(fixture.AccountId);
        Assert.False(afterAddition.AllCompleted);
        Assert.True(afterAddition.Claimed);
        Assert.False(afterAddition.CanClaim);
    }

    [Fact]
    public async Task MailWriteFailureRollsBackReceiptAndCanBeRetried()
    {
        await using var fixture = await Fixture.CreateAsync();
        await fixture.CompleteAsync("guide-a", "step-a");
        var mailId = $"guide-completion-{fixture.AccountId:N}";
        await using (var setup = fixture.NewContext())
        {
            setup.PlayerMailDeliveries.Add(new PlayerMailDeliveryEntity
            {
                PlayerMailDeliveryId = Guid.NewGuid(), AccountId = fixture.AccountId,
                MailId = mailId, PayloadJson = "{}", Version = 1,
                CreatedAt = DateTime.UtcNow, UpdatedAt = DateTime.UtcNow,
                CreatedBy = fixture.UserId, UpdatedBy = fixture.UserId,
            });
            await setup.SaveChangesAsync();
        }
        await using (var context = fixture.NewContext())
        {
            var repository = NewRepository(context, [Guide("guide-a", "step-a")]);
            await Assert.ThrowsAsync<DbUpdateException>(
                () => repository.ClaimAsync(fixture.AccountId, fixture.UserId));
        }
        await using (var verify = fixture.NewContext())
        {
            Assert.Empty(await verify.AccountGuideCompletionRewards.ToListAsync());
            await verify.PlayerMailDeliveries.ExecuteDeleteAsync();
        }
        await using (var retry = fixture.NewContext())
        {
            Assert.True((await NewRepository(retry, [Guide("guide-a", "step-a")])
                .ClaimAsync(fixture.AccountId, fixture.UserId)).Claimed);
        }
    }

    [Fact]
    public async Task EnabledDefaultRewardDeliversExactFourRowsOnceAcrossReloadsAndGuideAdditions()
    {
        await using var fixture = await Fixture.CreateAsync();
        var guides = new[] { Guide("guide-a", "step-a"), Guide("guide-b", "step-b") };
        await fixture.CompleteAsync("guide-a", "step-a");
        var configuration = new ConfigurationBuilder()
            .SetBasePath(AppContext.BaseDirectory).AddJsonFile("appsettings.json").Build();
        var expected = new[]
        {
            ("99a00001", "currency", 3000),
            ("40a00011", "orb", 10),
            ("30a00032", "consumable", 10),
            ("30a00035", "consumable", 10),
        };
        var settings = configuration.GetSection("GuideCompletionReward")
            .Get<AccountGuideCompletionRewardOptions>()!;
        Assert.Equal(expected, settings.Rewards.Select(reward => (reward.ItemId, reward.Category, reward.Amount)));
        Assert.DoesNotContain(new FixedItemRepository().GetAllSummaries(), item => item.Id == "99a00001");

        await using (var context = fixture.NewContext())
        {
            var enabled = new AccountGuideCompletionRewardRepository(
                context, new FixedGuideRepository(guides), new FixedItemRepository(), configuration);
            Assert.True((await enabled.GetAsync(fixture.AccountId)).Configured);
            Assert.False((await enabled.GetAsync(fixture.AccountId)).CanClaim);
            await Assert.ThrowsAsync<AccountGuideCompletionRewardUnavailableException>(
                () => enabled.ClaimAsync(fixture.AccountId, fixture.UserId));
        }
        await fixture.CompleteAsync("guide-b", "step-b");
        await using (var context = fixture.NewContext())
        {
            var disabled = NewRepository(context, guides, configured: false);
            Assert.False((await disabled.GetAsync(fixture.AccountId)).CanClaim);
            await Assert.ThrowsAsync<AccountGuideCompletionRewardUnavailableException>(
                () => disabled.ClaimAsync(fixture.AccountId, fixture.UserId));
            Assert.Empty(await context.AccountGuideCompletionRewards.ToListAsync());
            Assert.Empty(await context.PlayerMailDeliveries.ToListAsync());
            var enabled = new AccountGuideCompletionRewardRepository(
                context, new FixedGuideRepository(guides), new FixedItemRepository(), configuration);
            Assert.True((await enabled.GetAsync(fixture.AccountId)).CanClaim);
            Assert.True((await enabled.ClaimAsync(fixture.AccountId, fixture.UserId)).Claimed);
        }

        // A fresh context models a reload/relogin; repeated delivery and later guides do not reset the receipt.
        await using (var reloaded = fixture.NewContext())
        {
            var repository = new AccountGuideCompletionRewardRepository(
                reloaded, new FixedGuideRepository(guides), new FixedItemRepository(), configuration);
            Assert.False((await repository.GetAsync(fixture.AccountId)).CanClaim);
            Assert.True((await repository.ClaimAsync(fixture.AccountId, fixture.UserId)).Claimed);
            var delivery = Assert.Single(await reloaded.PlayerMailDeliveries.ToListAsync());
            var mail = JsonSerializer.Deserialize<MailResponse>(delivery.PayloadJson, MasterDataPayloadJson.Options)!;
            Assert.Equal(expected, mail.Rewards.Select(reward => (reward.ItemId, reward.Category, reward.Amount)));
            Assert.All(mail.Rewards, reward => Assert.Null(reward.InstanceId));
            var currency = mail.Rewards.Single(reward => reward.Category == "currency");
            Assert.Equal(3000L, GoldCurrencyBalanceSupport.TotalGold([
                new InventoryEntryEntity { ItemId = currency.ItemId, Quantity = currency.Amount },
            ]));
            Assert.Single(await reloaded.AccountGuideCompletionRewards.ToListAsync());
            var added = new AccountGuideCompletionRewardRepository(reloaded,
                new FixedGuideRepository([.. guides, Guide("guide-new", "step-new")]),
                new FixedItemRepository(), configuration);
            Assert.False((await added.GetAsync(fixture.AccountId)).AllCompleted);
            Assert.True((await added.ClaimAsync(fixture.AccountId, fixture.UserId)).Claimed);
        }
        await fixture.CompleteAsync("guide-new", "step-new");
        await using var final = fixture.NewContext();
        var afterCompletion = new AccountGuideCompletionRewardRepository(final,
            new FixedGuideRepository([.. guides, Guide("guide-new", "step-new")]),
            new FixedItemRepository(), configuration);
        var status = await afterCompletion.GetAsync(fixture.AccountId);
        Assert.True(status.AllCompleted);
        Assert.True(status.Claimed);
        Assert.False(status.CanClaim);
        await afterCompletion.ClaimAsync(fixture.AccountId, fixture.UserId);
        Assert.Single(await final.PlayerMailDeliveries.ToListAsync());
        Assert.Single(await final.AccountGuideCompletionRewards.ToListAsync());
    }

    private static AccountGuideCompletionRewardRepository NewRepository(
        AstralRecordDbContext context, IReadOnlyList<GuideResponse> guides, bool configured = true,
        string itemId = "reward-item", string category = "MATERIAL", string amount = "1")
    {
        var settings = new Dictionary<string, string?>();
        if (configured)
        {
            settings["GuideCompletionReward:Rewards:0:ItemId"] = itemId;
            settings["GuideCompletionReward:Rewards:0:Category"] = category;
            settings["GuideCompletionReward:Rewards:0:Amount"] = amount;
        }
        var configuration = new ConfigurationBuilder().AddInMemoryCollection(settings).Build();
        return new AccountGuideCompletionRewardRepository(context, new FixedGuideRepository(guides),
            new FixedItemRepository(), configuration);
    }

    private static GuideResponse Guide(string id, params string[] steps) => new()
    {
        SchemaVersion = 1,
        Id = id,
        Category = "beginner",
        Title = id,
        Steps = steps.Select(step => new GuideStepResponse
        {
            Id = step,
            Text = step,
            Condition = new GuideConditionResponse { Type = "manual" },
        }).ToArray(),
    };

    private sealed class FixedGuideRepository(IReadOnlyList<GuideResponse> guides) : IGuideRepository
    {
        public IReadOnlyList<GuideResponse> GetAll() => guides;
        public GuideResponse? GetById(string guideId) => guides.FirstOrDefault(guide => guide.Id == guideId);
    }

    private sealed class FixedItemRepository : IItemRepository
    {
        public IReadOnlyList<ItemSummaryResponse> GetAllSummaries() =>
            [
                new ItemSummaryResponse { Id = "reward-item", Category = "material", Name = "test reward" },
                new ItemSummaryResponse { Id = "40a00011", Category = "orb", Name = "Sindri fixture" },
                new ItemSummaryResponse { Id = "30a00032", Category = "consumable", Name = "HP fixture" },
                new ItemSummaryResponse { Id = "30a00035", Category = "consumable", Name = "MP fixture" },
                new ItemSummaryResponse { Id = "reward-pet", Category = "pet", Name = "test pet" },
                new ItemSummaryResponse { Id = "reward-egg", Category = "pet_egg", Name = "test egg" },
            ];

        public ItemResponse? GetById(string itemId) => null;
    }

    private sealed class Fixture : IAsyncDisposable
    {
        private readonly SqliteConnection connection;
        private readonly DbContextOptions<AstralRecordDbContext> options;
        public Guid AccountId { get; } = Guid.NewGuid();
        public Guid UserId { get; } = Guid.NewGuid();

        private Fixture(SqliteConnection connection)
        {
            this.connection = connection;
            options = new DbContextOptionsBuilder<AstralRecordDbContext>().UseSqlite(connection).Options;
        }

        public static async Task<Fixture> CreateAsync()
        {
            var connection = new SqliteConnection("Data Source=:memory:");
            await connection.OpenAsync();
            var fixture = new Fixture(connection);
            await using var context = fixture.NewContext();
            await context.Database.ExecuteSqlRawAsync("""
                CREATE TABLE account (uuid TEXT NOT NULL PRIMARY KEY, is_deleted INTEGER NOT NULL);
                CREATE TABLE account_guide_step_progress (
                    account_guide_step_progress_id TEXT NOT NULL PRIMARY KEY,
                    account_id TEXT NOT NULL, guide_id TEXT NOT NULL, step_id TEXT NOT NULL,
                    completed_at TEXT NOT NULL, created_at TEXT NOT NULL, created_by TEXT NOT NULL);
                CREATE TABLE account_guide_completion_reward (
                    account_id TEXT NOT NULL PRIMARY KEY, mail_id TEXT NULL UNIQUE,
                    claimed_at TEXT NOT NULL, claimed_by TEXT NOT NULL,
                    inherited_from_account_id TEXT NULL);
                CREATE TABLE player_mail_delivery (
                    player_mail_delivery_id TEXT NOT NULL PRIMARY KEY, account_id TEXT NOT NULL,
                    mail_id TEXT NOT NULL, payload_json TEXT NOT NULL, version INTEGER NOT NULL,
                    created_at TEXT NOT NULL, updated_at TEXT NOT NULL, created_by TEXT NOT NULL,
                    updated_by TEXT NOT NULL, is_deleted INTEGER NOT NULL,
                    UNIQUE (account_id, mail_id));
                """);
            await context.Database.ExecuteSqlInterpolatedAsync(
                $"INSERT INTO account (uuid, is_deleted) VALUES ({fixture.AccountId}, 0)");
            return fixture;
        }

        public AstralRecordDbContext NewContext() => new(options);

        public async Task CompleteAsync(string guideId, string stepId)
        {
            await using var context = NewContext();
            var now = DateTime.UtcNow;
            context.AccountGuideStepProgresses.Add(new AccountGuideStepProgressEntity
            {
                AccountGuideStepProgressId = Guid.NewGuid(), AccountId = AccountId,
                GuideId = guideId, StepId = stepId, CompletedAt = now,
                CreatedAt = now, CreatedBy = UserId,
            });
            await context.SaveChangesAsync();
        }

        public async ValueTask DisposeAsync() => await connection.DisposeAsync();
    }
}
