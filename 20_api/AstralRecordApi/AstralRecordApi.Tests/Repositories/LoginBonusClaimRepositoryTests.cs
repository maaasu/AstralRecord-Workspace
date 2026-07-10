using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Xunit;

namespace AstralRecordApi.Tests.Repositories;

public class LoginBonusClaimRepositoryTests
{
    [Fact]
    public async Task ClaimAsync_CreatesOnlyOneActiveClaimPerAccountDate()
    {
        await using var connection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync();

        var options = new DbContextOptionsBuilder<AstralRecordDbContext>()
            .UseSqlite(connection)
            .Options;

        var accountId = Guid.NewGuid();
        var userId = Guid.NewGuid();
        var now = DateTime.UtcNow;

        await using (var setupContext = new AstralRecordDbContext(options))
        {
            await CreateSchemaAsync(setupContext);
            setupContext.Accounts.Add(new AccountEntity
            {
                Uuid = accountId,
                UserId = userId,
                AccountName = "tester",
                SlotIndex = 0,
                IsActive = true,
                Mode = 0,
                MenuShortcutsJson = "{}",
                Level = 1,
                TotalExperience = 0,
                CreatedAt = now,
                UpdatedAt = now,
                CreatedBy = userId,
                UpdatedBy = userId,
                IsDeleted = false,
            });
            await setupContext.SaveChangesAsync();
        }

        await using var dbContext = new AstralRecordDbContext(options);
        var repository = new LoginBonusClaimRepository(dbContext);
        var request = new LoginBonusClaimRequest
        {
            ClaimDate = new DateOnly(2026, 7, 6),
            UpdatedBy = accountId,
        };

        var created = await repository.ClaimAsync(accountId, request);
        var duplicate = await repository.ClaimAsync(accountId, request);

        Assert.True(created.WasCreated);
        Assert.False(duplicate.WasCreated);
        Assert.Equal(created.LoginBonusClaimId, duplicate.LoginBonusClaimId);

        Assert.True(await repository.CancelAsync(accountId, request.ClaimDate, accountId));
        Assert.False(await repository.CancelAsync(accountId, request.ClaimDate, accountId));

        var retried = await repository.ClaimAsync(accountId, request);
        Assert.True(retried.WasCreated);
        Assert.NotEqual(created.LoginBonusClaimId, retried.LoginBonusClaimId);
    }

    [Fact]
    public async Task GetByAccountIdAsync_FiltersClaimsByDateRange()
    {
        await using var connection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync();

        var options = new DbContextOptionsBuilder<AstralRecordDbContext>()
            .UseSqlite(connection)
            .Options;

        var accountId = Guid.NewGuid();
        var userId = Guid.NewGuid();
        var now = DateTime.UtcNow;

        await using (var setupContext = new AstralRecordDbContext(options))
        {
            await CreateSchemaAsync(setupContext);
            setupContext.Accounts.Add(new AccountEntity
            {
                Uuid = accountId,
                UserId = userId,
                AccountName = "tester",
                SlotIndex = 0,
                IsActive = true,
                Mode = 0,
                MenuShortcutsJson = "{}",
                Level = 1,
                TotalExperience = 0,
                CreatedAt = now,
                UpdatedAt = now,
                CreatedBy = userId,
                UpdatedBy = userId,
                IsDeleted = false,
            });
            setupContext.LoginBonusClaims.AddRange(
                NewClaim(accountId, new DateOnly(2026, 6, 30), now),
                NewClaim(accountId, new DateOnly(2026, 7, 1), now),
                NewClaim(accountId, new DateOnly(2026, 7, 31), now),
                NewClaim(accountId, new DateOnly(2026, 8, 1), now));
            await setupContext.SaveChangesAsync();
        }

        await using var dbContext = new AstralRecordDbContext(options);
        var repository = new LoginBonusClaimRepository(dbContext);

        var claims = await repository.GetByAccountIdAsync(
            accountId,
            new DateOnly(2026, 7, 1),
            new DateOnly(2026, 7, 31));

        Assert.Equal([new DateOnly(2026, 7, 1), new DateOnly(2026, 7, 31)], claims.Select(claim => claim.ClaimDate));
    }

    private static LoginBonusClaimEntity NewClaim(Guid accountId, DateOnly claimDate, DateTime now) => new()
    {
        LoginBonusClaimId = Guid.NewGuid(),
        AccountId = accountId,
        ClaimDate = claimDate,
        ClaimedAt = now,
        CreatedAt = now,
        UpdatedAt = now,
        CreatedBy = accountId,
        UpdatedBy = accountId,
        IsDeleted = false,
    };

    private static async Task CreateSchemaAsync(AstralRecordDbContext dbContext)
    {
        await dbContext.Database.ExecuteSqlRawAsync(@"
            CREATE TABLE account (
                uuid TEXT NOT NULL PRIMARY KEY,
                user_id TEXT NOT NULL,
                account_name TEXT NOT NULL,
                slot_index INTEGER NOT NULL,
                is_active INTEGER NOT NULL,
                mode INTEGER NOT NULL,
                menu_shortcuts_json TEXT NOT NULL,
                level INTEGER NOT NULL,
                total_experience INTEGER NOT NULL,
                class_id TEXT NOT NULL,
                class_level INTEGER NOT NULL,
                class_experience INTEGER NOT NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                created_by TEXT NOT NULL,
                updated_by TEXT NOT NULL,
                is_deleted INTEGER NOT NULL
            );

            CREATE TABLE login_bonus_claim (
                login_bonus_claim_id TEXT NOT NULL PRIMARY KEY,
                account_id TEXT NOT NULL,
                claim_date TEXT NOT NULL,
                claimed_at TEXT NOT NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                created_by TEXT NOT NULL,
                updated_by TEXT NOT NULL,
                is_deleted INTEGER NOT NULL
            );

            CREATE UNIQUE INDEX UX_login_bonus_claim_account_date
                ON login_bonus_claim (account_id, claim_date)
                WHERE is_deleted = 0;
        ");
    }
}
