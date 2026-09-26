using System.Text.Json;
using AstralRecordApi.Controllers;
using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using AstralRecordApi.Authentication;
using AstralRecordApi.Options;
using Microsoft.AspNetCore.DataProtection;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Configuration;
using Xunit;

namespace AstralRecordApi.Tests.Repositories;

public sealed class DonationRepositoryTests
{
    [Fact]
    public async Task PendingLimit_CancelAndRejectReleaseSlots_ReviewBlocksCancellation()
    {
        await using var f = await Fixture.Create();
        var a = await f.Repo.CreateAsync(f.User, Request());
        var b = await f.Repo.CreateAsync(f.User, Request());
        await Assert.ThrowsAsync<DonationConflictException>(() => f.Repo.CreateAsync(f.User, Request()));
        await f.Repo.TransitionAsync(a.Id, f.User, "cancel");
        await f.Repo.CreateAsync(f.User, Request());
        await f.Repo.TransitionAsync(b.Id, f.Admin, "review");
        await Assert.ThrowsAsync<DonationConflictException>(() => f.Repo.TransitionAsync(b.Id, f.User, "cancel"));
        await Assert.ThrowsAsync<DonationConflictException>(() => f.Repo.TransitionAsync(b.Id, Guid.NewGuid(), "approve"));
        await f.Repo.TransitionAsync(b.Id, f.Admin, "reject", reason: "受領していないため否認");
        Assert.Equal(0, (await f.Repo.ListAsync(f.User, false, 1, 20)).TotalApprovedAmount);
        Assert.Empty(await f.Management.Set<DonationGrantEntity>().ToListAsync());
        await f.Repo.CreateAsync(f.User, Request());
    }

    [Fact]
    public async Task Approval_IsIdempotent_UsesOverride_AndPreservesDeclaredAmount()
    {
        await using var f = await Fixture.Create();
        await f.AddAccount();
        var input = Request(1000);
        var a = await f.Repo.CreateAsync(f.User, input);
        Assert.Equal(a.Id, (await f.Repo.CreateAsync(f.User, input)).Id);
        await f.Repo.TransitionAsync(a.Id, f.Admin, "review");
        var approved = await f.Repo.TransitionAsync(a.Id, f.Admin, "approve", 300);
        await f.Repo.TransitionAsync(a.Id, f.Admin, "approve", 300);
        Assert.Equal(1000, approved.DeclaredAmount);
        Assert.Equal(300, approved.ApprovedAmount);
        Assert.Contains("500円以上", approved.Reason);
        Assert.Equal(300, (await f.Repo.ListAsync(f.User, false, 1, 20)).TotalApprovedAmount);
        await f.Repo.ReconcileAsync(default);
        Assert.Single(await f.Management.Set<DonationGrantEntity>().ToListAsync());
        await Assert.ThrowsAsync<DonationConflictException>(() => f.Repo.TransitionAsync(a.Id, f.Admin, "approve", 301));
        await Assert.ThrowsAsync<DonationConflictException>(() => f.Repo.TransitionAsync(a.Id, f.Admin, "reject", reason: "不可"));
    }

    [Fact]
    public async Task Delivery_ReplaysSafely_AndNewAccountsGetCumulativeAmountAfterGameReset()
    {
        await using var f = await Fixture.Create();
        var account = await f.AddAccount();
        var second = await f.AddAccount();
        var a = await f.Repo.CreateAsync(f.User, Request(1000));
        await f.Repo.TransitionAsync(a.Id, f.Admin, "review");
        await f.Repo.TransitionAsync(a.Id, f.Admin, "approve");
        await f.Repo.ReconcileAsync(default);
        await f.Repo.ReconcileAsync(default);
        Assert.Equal(2, await f.Game.PlayerMailDeliveries.CountAsync());
        var deliveredNotice = Assert.Single(await f.Repo.NotificationsAsync(f.User), x => x.Kind == "MailDelivered");
        Assert.Equal("寄付へのお礼のメールが届きました。メール画面をご確認ください。", deliveredNotice.Message);
        var firstGrant = await f.Management.Set<DonationGrantEntity>().FirstAsync();
        firstGrant.DeliveredAtUtc = null; // ゲームDB commit後、ManagementDB ACK前に停止した場合。
        await f.Management.SaveChangesAsync();
        await f.Repo.ReconcileAsync(default);
        Assert.Equal(2, await f.Game.PlayerMailDeliveries.CountAsync());
        Assert.Single(await f.Repo.NotificationsAsync(f.User), x => x.Kind == "MailDelivered");
        var payload = await f.Game.PlayerMailDeliveries.Select(x => x.PayloadJson).FirstAsync();
        var mail = JsonSerializer.Deserialize<MailResponse>(payload, new JsonSerializerOptions(JsonSerializerDefaults.Web))!;
        Assert.Null(mail.PublishTo);
        Assert.True(mail.ReceiveOnRead);
        Assert.Equal(1000, Assert.Single(mail.Rewards).Amount);

        await f.Game.PlayerMailDeliveries.ExecuteDeleteAsync();
        await f.Game.Accounts.ExecuteDeleteAsync(); // ManagementDBは残したままゲーム初期化。
        var newAccount = await f.AddAccount();
        await f.Repo.ReconcileAsync(default);
        Assert.Equal(newAccount, (await f.Game.PlayerMailDeliveries.SingleAsync()).AccountId);
        Assert.Equal(1000, (await f.Repo.ListAsync(f.User, false, 1, 20)).TotalApprovedAmount);
        Assert.Single(await f.Repo.NotificationsAsync(f.User), x => x.Kind == "MailDelivered");
        await f.AddAccount(account); // 同じUUIDの復元には追加配布しない。
        await f.Repo.ReconcileAsync(default);
        Assert.Single(await f.Game.PlayerMailDeliveries.ToListAsync());
        Assert.NotEqual(account, second);
    }

    [Fact]
    public async Task MailDelivered_UsesOneUserMessageWhenAccountMailAmountsDiffer()
    {
        await using var f = await Fixture.Create();
        await f.AddAccount();
        var first = await f.Repo.CreateAsync(f.User, Request());
        await f.Repo.TransitionAsync(first.Id, f.Admin, "review");
        await f.Repo.TransitionAsync(first.Id, f.Admin, "approve");
        await f.Repo.ReconcileAsync(default);

        var second = await f.Repo.CreateAsync(f.User, Request());
        await f.Repo.TransitionAsync(second.Id, f.Admin, "review");
        await f.Repo.TransitionAsync(second.Id, f.Admin, "approve");
        await f.AddAccount(); // 新規アカウントには累計、既存アカウントには差分のメールを作る。
        await f.Repo.ReconcileAsync(default);

        Assert.Equal(3, await f.Game.PlayerMailDeliveries.CountAsync());
        var amounts = await f.Management.Set<DonationGrantEntity>().Where(x => x.ThroughAmount == 1000)
            .OrderBy(x => x.Amount).Select(x => x.Amount).ToArrayAsync();
        Assert.Equal(new[] { 500, 1000 }, amounts);
        var notices = (await f.Repo.NotificationsAsync(f.User)).Where(x => x.Kind == "MailDelivered").ToArray();
        Assert.Equal(2, notices.Length);
        Assert.All(notices, x => Assert.Equal("寄付へのお礼のメールが届きました。メール画面をご確認ください。", x.Message));
    }

    [Fact]
    public async Task OrphanedPendingGrantsCannotStarveNewAccountDelivery()
    {
        await using var f = await Fixture.Create();
        var a = await f.Repo.CreateAsync(f.User, Request());
        await f.Repo.TransitionAsync(a.Id, f.Admin, "review");
        await f.Repo.TransitionAsync(a.Id, f.Admin, "approve");
        f.Management.Set<DonationGrantEntity>().AddRange(Enumerable.Range(0, 201).Select(_ => new DonationGrantEntity
        {
            Id = Guid.NewGuid(), UserUuid = f.User, AccountUuid = Guid.NewGuid(), ThroughAmount = 500,
            Amount = 500, Message = "過去アカウント", CreatedAtUtc = DateTime.UtcNow.AddDays(-1),
        }));
        await f.Management.SaveChangesAsync();
        var account = await f.AddAccount();
        await f.Repo.ReconcileAsync(default);
        Assert.Equal(account, (await f.Game.PlayerMailDeliveries.SingleAsync()).AccountId);
        Assert.Single(await f.Repo.NotificationsAsync(f.User), x => x.Kind == "Approved");
        Assert.Single(await f.Repo.NotificationsAsync(f.User), x => x.Kind == "MailDelivered");
    }

    [Fact]
    public async Task ApprovalPersistsWhenGameSchemaIsUnavailable()
    {
        await using var f = await Fixture.Create();
        var a = await f.Repo.CreateAsync(f.User, Request());
        await f.Repo.TransitionAsync(a.Id, f.Admin, "review");
        await f.Game.Database.ExecuteSqlRawAsync("DROP TABLE account");
        await f.Repo.TransitionAsync(a.Id, f.Admin, "approve");
        Assert.Equal(500, (await f.Repo.ListAsync(f.User, false, 1, 20)).TotalApprovedAmount);
        Assert.Single(await f.Repo.NotificationsAsync(f.User), x => x.Kind == "Approved");
    }

    [Fact]
    public async Task NewDonationOnlyAddsDelta_SecretsNotInList_NotificationsNeedOwnerAck()
    {
        await using var f = await Fixture.Create();
        await f.AddAccount();
        for (var i = 0; i < 2; i++)
        {
            var a = await f.Repo.CreateAsync(f.User, Request(500));
            await f.Repo.TransitionAsync(a.Id, f.Admin, "review");
            await f.Repo.TransitionAsync(a.Id, f.Admin, "approve");
            await f.Repo.ReconcileAsync(default);
        }
        Assert.Equal(1000, await f.Management.Set<DonationGrantEntity>().SumAsync(x => x.Amount));
        Assert.Equal(2, (await f.Repo.NotificationsAsync(f.User)).Count(x => x.Kind == "MailDelivered"));
        Assert.All((await f.Repo.ListAsync(f.User, false, 1, 20)).Requests, x => Assert.Empty(x.Entries));
        var request = await f.Management.Set<DonationRequestEntity>().FirstAsync();
        Assert.DoesNotContain("amazon", request.ProtectedEntries);
        Assert.Null(await f.Repo.GetAsync(request.Id, f.Admin, false));
        Assert.NotNull(await f.Repo.GetAsync(request.Id, f.Admin, true));
        var notification = (await f.Repo.NotificationsAsync(f.User))[0];
        await f.Repo.AcknowledgeAsync(f.Admin, notification.Id);
        Assert.Contains(await f.Repo.NotificationsAsync(f.User), x => x.Id == notification.Id);
        await f.Repo.AcknowledgeAsync(f.User, notification.Id);
        Assert.DoesNotContain(await f.Repo.NotificationsAsync(f.User), x => x.Id == notification.Id);
    }

    [Fact]
    public async Task DuplicateCodesAndInvalidOverridesFail_EmptyOverrideUsesDeclared()
    {
        await using var f = await Fixture.Create();
        var input = Request();
        var a = await f.Repo.CreateAsync(f.User, input);
        await Assert.ThrowsAsync<DonationConflictException>(() => f.Repo.CreateAsync(f.User, input with { OperationId = Guid.NewGuid() }));
        await f.Repo.TransitionAsync(a.Id, f.Admin, "review");
        await Assert.ThrowsAsync<ArgumentException>(() => f.Repo.TransitionAsync(a.Id, f.Admin, "approve", 0));
        Assert.Equal(500, (await f.Repo.TransitionAsync(a.Id, f.Admin, "approve")).ApprovedAmount);
        f.Discord.Member = false;
        await Assert.ThrowsAsync<UnauthorizedAccessException>(() => f.Repo.CreateAsync(f.User, Request()));
    }

    [Fact]
    public async Task WebEndpointsRequireDedicatedKey_AndFreshManagementAdminFlag()
    {
        await using var f = await Fixture.Create();
        var auth = new WebAuthRepository(f.Game, f.Management, Microsoft.Extensions.Options.Options.Create(new WebAuthOptions()),
            new WebCodeProofProtector(f.Protector));
        var config = new ConfigurationBuilder().AddInMemoryCollection(new Dictionary<string, string?> { ["Donations:WebKey"] = "dedicated-key" }).Build();
        var controller = new DonationController(f.Repo, f.Discord, auth, config)
        { ControllerContext = new() { HttpContext = new DefaultHttpContext() } };
        Assert.Equal(403, Assert.IsType<StatusCodeResult>(await controller.List(f.User)).StatusCode);
        controller.Request.Headers["X-Donation-Web-Key"] = "dedicated-key";
        Assert.IsType<OkObjectResult>(await controller.List(f.User));
        Assert.Equal(403, Assert.IsType<StatusCodeResult>(await controller.AdminList(f.User)).StatusCode);
        var player = await f.Management.Players.SingleAsync();
        player.WebAdmin = true;
        await f.Management.SaveChangesAsync();
        Assert.IsType<OkObjectResult>(await controller.AdminList(f.User));
        player.WebAdmin = false;
        await f.Management.SaveChangesAsync();
        Assert.Equal(403, Assert.IsType<StatusCodeResult>(await controller.AdminList(f.User)).StatusCode);
    }

    [Theory]
    [InlineData("http://pay.paypay.ne.jp/test")]
    [InlineData("https://pay.paypay.ne.jp.evil.example/test")]
    [InlineData("https://pay.paypay.ne.jp/test?other=1")]
    [InlineData("https://user@pay.paypay.ne.jp/test")]
    public void PaymentLinksCannotTargetOtherHosts(string value) => Assert.Throws<ArgumentException>(() =>
        DonationRepository.Normalize(new(Guid.NewGuid(), 500, DonationRules.TermsVersion, [new("paypay", value, 500)])));

    private static DonationCreateRequest Request(int amount = 500) => new(Guid.NewGuid(), amount, DonationRules.TermsVersion,
        [new("amazon", Guid.NewGuid().ToString("N").ToUpperInvariant(), amount)]);

    private sealed class DiscordStub : IDonationDiscordRepository
    {
        public bool Member { get; set; } = true;
        public Task<DonationDiscordLinkResponse?> GetAsync(Guid userUuid) => Task.FromResult<DonationDiscordLinkResponse?>(new("123", "Tester", Member, DateTime.UtcNow));
        public Task<DonationDiscordLinkResponse> LinkAsync(Guid userUuid, DonationDiscordLinkRequest request) => VerifyMembershipAsync(userUuid);
        public async Task<DonationDiscordLinkResponse> VerifyMembershipAsync(Guid userUuid) => Member ? (await GetAsync(userUuid))! : throw new UnauthorizedAccessException();
    }

    private sealed class Fixture : IAsyncDisposable
    {
        private readonly SqliteConnection managementConnection = new("Data Source=:memory:");
        private readonly SqliteConnection gameConnection = new("Data Source=:memory:");
        public Guid User { get; } = Guid.NewGuid();
        public Guid Admin { get; } = Guid.NewGuid();
        public ManagementDbContext Management { get; private set; } = null!;
        public AstralRecordDbContext Game { get; private set; } = null!;
        public DiscordStub Discord { get; } = new();
        public EphemeralDataProtectionProvider Protector { get; } = new();
        public DonationRepository Repo => new(Management, Game, Discord, Protector, TimeProvider.System);
        public static async Task<Fixture> Create()
        {
            var f = new Fixture();
            await f.managementConnection.OpenAsync();
            await f.gameConnection.OpenAsync();
            f.Management = new(new DbContextOptionsBuilder<ManagementDbContext>().UseSqlite(f.managementConnection).Options);
            f.Game = new(new DbContextOptionsBuilder<AstralRecordDbContext>().UseSqlite(f.gameConnection).Options);
            await f.Management.Database.EnsureCreatedAsync();
            await f.Game.Database.EnsureCreatedAsync();
            f.Management.Players.Add(new() { PlayerUuid = f.User, Mcid = "Tester", CreatedAt = DateTime.UtcNow, UpdatedAt = DateTime.UtcNow });
            await f.Management.SaveChangesAsync();
            return f;
        }
        public async Task<Guid> AddAccount(Guid? id = null)
        {
            Game.ChangeTracker.Clear();
            var uuid = id ?? Guid.NewGuid();
            Game.Accounts.Add(new() { Uuid = uuid, UserId = User, AccountName = uuid.ToString(),
                SlotIndex = await Game.Accounts.CountAsync() + 1, CreatedAt = DateTime.UtcNow, UpdatedAt = DateTime.UtcNow });
            await Game.SaveChangesAsync();
            return uuid;
        }
        public async ValueTask DisposeAsync()
        {
            await Management.DisposeAsync(); await Game.DisposeAsync();
            await managementConnection.DisposeAsync(); await gameConnection.DisposeAsync();
        }
    }
}
