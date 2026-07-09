using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using AstralRecordApi.Options;
using AstralRecordApi.Repositories;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;
using Xunit;

namespace AstralRecordApi.Tests.Repositories;

public class WebAuthRepositoryTests
{
    [Fact]
    public async Task ConsumeChallengeAsync_AcceptsCodeWithoutHyphen()
    {
        await using var connection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync();

        var options = new DbContextOptionsBuilder<AstralRecordDbContext>()
            .UseSqlite(connection)
            .Options;

        var userId = Guid.NewGuid();
        var accountId = Guid.NewGuid();
        var now = DateTime.UtcNow;

        await using (var setupContext = new AstralRecordDbContext(options))
        {
            await CreateSchemaAsync(setupContext);
            setupContext.Users.Add(new UserEntity
            {
                Uuid = userId,
                Mcid = "Tester",
                JoinDate = now,
                LastJoinDate = now,
                GlobalIp = "127.0.0.1",
                AccountId = accountId,
                Permission = 10,
                CreatedAt = now,
                UpdatedAt = now,
                CreatedBy = userId,
                UpdatedBy = userId,
                IsDeleted = false,
            });
            setupContext.Accounts.Add(new AccountEntity
            {
                Uuid = accountId,
                UserId = userId,
                AccountName = "main",
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
        var repository = new WebAuthRepository(
            dbContext,
            Microsoft.Extensions.Options.Options.Create(new WebAuthOptions { ChallengeMinutes = 5, LoginUrl = "https://example.com/Login" }));

        var created = await repository.CreateChallengeAsync(new WebLoginChallengeCreateRequest
        {
            UserUuid = userId,
            Mcid = "Tester",
            ServerId = "test-server",
            RequestedAt = now,
        });

        Assert.NotNull(created);

        var consumed = await repository.ConsumeChallengeAsync(new WebLoginChallengeConsumeRequest
        {
            LoginCode = created.LoginCode.Replace("-", string.Empty),
        });

        Assert.NotNull(consumed);
        Assert.Equal(userId, consumed.UserUuid);
        Assert.Equal(accountId, consumed.CurrentAccountId);
        Assert.Equal([accountId], consumed.AccountIds);
    }

    private static async Task CreateSchemaAsync(AstralRecordDbContext dbContext)
    {
        await dbContext.Database.ExecuteSqlRawAsync(@"
            CREATE TABLE user (
                uuid TEXT NOT NULL PRIMARY KEY,
                mcid TEXT NOT NULL,
                join_date TEXT NOT NULL,
                last_join_date TEXT NOT NULL,
                global_ip TEXT NOT NULL,
                account_id TEXT NULL,
                ban_indefinite INTEGER NOT NULL,
                ban_date TEXT NULL,
                kick_ip INTEGER NOT NULL,
                permission INTEGER NOT NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                created_by TEXT NOT NULL,
                updated_by TEXT NOT NULL,
                is_deleted INTEGER NOT NULL
            );

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

            CREATE TABLE web_login_challenge (
                challenge_id TEXT NOT NULL PRIMARY KEY,
                user_id TEXT NOT NULL,
                login_code_hash TEXT NOT NULL,
                issued_at TEXT NOT NULL,
                expires_at TEXT NOT NULL,
                consumed_at TEXT NULL,
                revoked_at TEXT NULL,
                failed_attempts INTEGER NOT NULL,
                issued_by_server TEXT NOT NULL,
                created_at TEXT NOT NULL
            );

            CREATE UNIQUE INDEX UX_web_login_challenge_login_code_hash
                ON web_login_challenge (login_code_hash);
        ");
    }
}
