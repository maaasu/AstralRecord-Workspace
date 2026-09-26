using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.AspNetCore.DataProtection;
using Microsoft.Data.SqlClient;
using Microsoft.EntityFrameworkCore;
using Xunit;

namespace AstralRecordApi.Tests.Repositories;

/// <summary>本番と同じSQL Serverのユーザー行ロックで同時申請・承認を検証します。</summary>
public sealed class DonationSqlServerTests
{
    [Fact]
    [Trait("Category", "SqlServerIntegration")]
    public async Task ConcurrentSubmissionsAndApprovalsPreserveLimitsAndTotals()
    {
        if (Environment.GetEnvironmentVariable("ASTRALRECORD_RUN_SQLSERVER_INTEGRATION") != "1") return;
        var name = "AR_Donation_Test_" + Guid.NewGuid().ToString("N");
        var builder = new SqlConnectionStringBuilder("Server=localhost\\SQLEXPRESS;Integrated Security=True;TrustServerCertificate=True") { InitialCatalog = name };
        var options = new DbContextOptionsBuilder<ManagementDbContext>().UseSqlServer(builder.ConnectionString,
            sql => sql.EnableRetryOnFailure(5, TimeSpan.FromMilliseconds(100), [1205])).Options;
        var user = Guid.NewGuid(); var admin = Guid.NewGuid();
        var protection = new EphemeralDataProtectionProvider();
        await using var setup = new ManagementDbContext(options);
        try
        {
            await setup.Database.EnsureCreatedAsync();
            setup.Players.Add(new() { PlayerUuid = user, Mcid = "ConcurrentTest", CreatedAt = DateTime.UtcNow, UpdatedAt = DateTime.UtcNow });
            await setup.SaveChangesAsync();
            async Task<DonationResponse?> Submit()
            {
                await using var db = new ManagementDbContext(options);
                await using var game = new AstralRecordDbContext(new DbContextOptionsBuilder<AstralRecordDbContext>()
                    .UseSqlServer("Server=localhost\\SQLEXPRESS;Database=AR_Donation_Unavailable;Integrated Security=True;TrustServerCertificate=True").Options);
                var repo = new DonationRepository(db, game, new DiscordStub(), protection, TimeProvider.System);
                try { return await repo.CreateAsync(user, new(Guid.NewGuid(), 500, DonationRules.TermsVersion, [new("amazon", Guid.NewGuid().ToString("N"), 500)])); }
                catch (DonationConflictException) { return null; }
            }
            var submissions = await Task.WhenAll(Submit(), Submit(), Submit());
            Assert.Equal(2, submissions.Count(x => x is not null));
            var id = submissions.First(x => x is not null)!.Id;
            async Task Act(string action)
            {
                await using var db = new ManagementDbContext(options);
                await using var game = new AstralRecordDbContext(new DbContextOptionsBuilder<AstralRecordDbContext>()
                    .UseSqlServer("Server=localhost\\SQLEXPRESS;Database=AR_Donation_Unavailable;Integrated Security=True;TrustServerCertificate=True").Options);
                await new DonationRepository(db, game, new DiscordStub(), protection, TimeProvider.System).TransitionAsync(id, admin, action);
            }
            await Act("review");
            await Task.WhenAll(Act("approve"), Act("approve"));
            Assert.Equal(500, (await setup.Set<DonationLedgerEntity>().AsNoTracking().SingleAsync()).TotalApprovedAmount);
            Assert.Single(await setup.Set<DonationNotificationEntity>().Where(x => x.Kind == "Approved").ToListAsync());
        }
        finally
        {
            // このテスト内で生成した一時DBだけを削除し、実DBには触れない。
            if (name.StartsWith("AR_Donation_Test_", StringComparison.Ordinal) && name.Length == 49)
                await setup.Database.EnsureDeletedAsync();
        }
    }

    private sealed class DiscordStub : IDonationDiscordRepository
    {
        public Task<DonationDiscordLinkResponse?> GetAsync(Guid userUuid) => Task.FromResult<DonationDiscordLinkResponse?>(new("12345", "Test", true, DateTime.UtcNow));
        public Task<DonationDiscordLinkResponse> LinkAsync(Guid userUuid, DonationDiscordLinkRequest request) => VerifyMembershipAsync(userUuid);
        public async Task<DonationDiscordLinkResponse> VerifyMembershipAsync(Guid userUuid) => (await GetAsync(userUuid))!;
    }
}
