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
    /// <summary>
    /// 設計入力: 00_docs/20_API設計書/feature/24-web-auth/3-エンドポイント仕様/24_3.03-消費系.md
    /// 検証契約: ハイフンなしの正規化コードは一度だけ消費でき、成功時にWebSiteDBへ既定権限のプレイヤーを記録する。
    /// </summary>
    [Fact]
    public async Task ConsumeChallengeAsync_AcceptsNormalizedCodeOnceAndCreatesWebUser()
    {
        await using var gameConnection = new SqliteConnection("Data Source=:memory:");
        await using var webSiteConnection = new SqliteConnection("Data Source=:memory:");
        await gameConnection.OpenAsync();
        await webSiteConnection.OpenAsync();
        var gameOptions = CreateGameOptions(gameConnection);
        var webSiteOptions = CreateWebSiteOptions(webSiteConnection);
        var userId = Guid.NewGuid();
        var accountId = Guid.NewGuid();
        var now = DateTime.UtcNow;

        await SeedGameUserAsync(gameOptions, userId, "Tester", accountId, now);
        await using var webSiteSetup = new WebSiteDbContext(webSiteOptions);
        await webSiteSetup.Database.EnsureCreatedAsync();

        await using var gameContext = new AstralRecordDbContext(gameOptions);
        await using var webSiteContext = new WebSiteDbContext(webSiteOptions);
        var repository = CreateRepository(gameContext, webSiteContext);
        var created = await repository.CreateChallengeAsync(CreateChallengeRequest(userId, "Tester", now));

        Assert.NotNull(created);
        var consumed = await repository.ConsumeChallengeAsync(new WebLoginChallengeConsumeRequest
        {
            LoginCode = created.LoginCode.Replace("-", string.Empty),
        });
        var consumedAgain = await repository.ConsumeChallengeAsync(new WebLoginChallengeConsumeRequest
        {
            LoginCode = created.LoginCode,
        });

        Assert.NotNull(consumed);
        Assert.Equal(userId, consumed.UserUuid);
        Assert.Equal(accountId, consumed.CurrentAccountId);
        Assert.Equal([accountId], consumed.AccountIds);
        Assert.False(consumed.WebAdmin);
        Assert.Null(consumedAgain);

        var webUser = await webSiteContext.WebUsers.SingleAsync();
        Assert.Equal(userId, webUser.UserUuid);
        Assert.Equal("Tester", webUser.Mcid);
        Assert.False(webUser.WebAdmin);
        Assert.Equal(webUser.FirstLoginAt, webUser.LastLoginAt);
    }

    /// <summary>
    /// 設計入力: 00_docs/20_API設計書/feature/24-web-auth/1-モデル定義/24_1.00-モデル定義.md
    /// 検証契約: WebSiteDBへの再ログイン記録はMCIDと最終ログイン日時を更新しても、既存のWeb管理フラグを変更しない。
    /// </summary>
    [Fact]
    public async Task ConsumeChallengeAsync_PreservesExistingWebAdminFlag()
    {
        await using var gameConnection = new SqliteConnection("Data Source=:memory:");
        await using var webSiteConnection = new SqliteConnection("Data Source=:memory:");
        await gameConnection.OpenAsync();
        await webSiteConnection.OpenAsync();
        var gameOptions = CreateGameOptions(gameConnection);
        var webSiteOptions = CreateWebSiteOptions(webSiteConnection);
        var userId = Guid.NewGuid();
        var accountId = Guid.NewGuid();
        var now = DateTime.UtcNow;
        await SeedGameUserAsync(gameOptions, userId, "BeforeRename", accountId, now);
        await using (var webSiteSetup = new WebSiteDbContext(webSiteOptions))
        {
            await webSiteSetup.Database.EnsureCreatedAsync();
            webSiteSetup.WebUsers.Add(new WebUserEntity
            {
                UserUuid = userId,
                Mcid = "BeforeRename",
                WebAdmin = true,
                FirstLoginAt = now.AddDays(-1),
                LastLoginAt = now.AddDays(-1),
            });
            await webSiteSetup.SaveChangesAsync();
        }

        await using var gameContext = new AstralRecordDbContext(gameOptions);
        var gameUser = await gameContext.Users.SingleAsync();
        gameUser.Mcid = "AfterRename";
        await gameContext.SaveChangesAsync();
        await using var webSiteContext = new WebSiteDbContext(webSiteOptions);
        var repository = CreateRepository(gameContext, webSiteContext);
        var created = await repository.CreateChallengeAsync(CreateChallengeRequest(userId, "AfterRename", now));

        Assert.NotNull(created);
        var consumed = await repository.ConsumeChallengeAsync(new WebLoginChallengeConsumeRequest { LoginCode = created.LoginCode });

        Assert.NotNull(consumed);
        Assert.True(consumed.WebAdmin);
        var webUser = await webSiteContext.WebUsers.SingleAsync();
        Assert.True(webUser.WebAdmin);
        Assert.Equal("AfterRename", webUser.Mcid);
        Assert.True(webUser.LastLoginAt > webUser.FirstLoginAt);
    }

    /// <summary>
    /// 設計入力: 00_docs/20_API設計書/feature/24-web-auth/3-エンドポイント仕様/24_3.02-登録系.md
    /// 検証契約: コンソール用MCID解決は完全一致で一意な登録だけを受け入れ、重複するMCIDを拒否する。
    /// </summary>
    [Fact]
    public async Task ResolveUserByMcidAsync_RejectsAmbiguousMcid()
    {
        await using var gameConnection = new SqliteConnection("Data Source=:memory:");
        await using var webSiteConnection = new SqliteConnection("Data Source=:memory:");
        await gameConnection.OpenAsync();
        await webSiteConnection.OpenAsync();
        var gameOptions = CreateGameOptions(gameConnection);
        var webSiteOptions = CreateWebSiteOptions(webSiteConnection);
        var now = DateTime.UtcNow;
        await SeedGameUserAsync(gameOptions, Guid.NewGuid(), "Duplicate", Guid.NewGuid(), now);
        await SeedGameUserAsync(gameOptions, Guid.NewGuid(), "Duplicate", Guid.NewGuid(), now);
        await using var webSiteSetup = new WebSiteDbContext(webSiteOptions);
        await webSiteSetup.Database.EnsureCreatedAsync();

        await using var gameContext = new AstralRecordDbContext(gameOptions);
        await using var webSiteContext = new WebSiteDbContext(webSiteOptions);
        var repository = CreateRepository(gameContext, webSiteContext);

        var resolved = await repository.ResolveUserByMcidAsync(" Duplicate ");

        Assert.Equal(WebLoginChallengeUserResolveStatus.Ambiguous, resolved.Status);
        Assert.Null(resolved.Response);
    }

    [Fact]
    public async Task ConsumeChallengeAsync_WhenWebDatabaseFails_RollsBackAndAllowsRetry()
    {
        await using var gameConnection = new SqliteConnection("Data Source=:memory:");
        await using var webConnection = new SqliteConnection("Data Source=:memory:");
        await gameConnection.OpenAsync();
        await webConnection.OpenAsync();
        var gameOptions = CreateGameOptions(gameConnection);
        var userId = Guid.NewGuid();
        var now = DateTime.UtcNow;
        await SeedGameUserAsync(gameOptions, userId, "RetryTester", Guid.NewGuid(), now);
        await using var gameContext = new AstralRecordDbContext(gameOptions);
        await using var webContext = new WebSiteDbContext(CreateWebSiteOptions(webConnection));
        var repository = CreateRepository(gameContext, webContext);
        var issued = await repository.CreateChallengeAsync(CreateChallengeRequest(userId, "RetryTester", now));
        Assert.NotNull(issued);
        var request = new WebLoginChallengeConsumeRequest { LoginCode = issued.LoginCode };

        // Web DBのテーブルが利用不能な状態を再現する。
        await Assert.ThrowsAsync<SqliteException>(() => repository.ConsumeChallengeAsync(request));
        Assert.Null((await gameContext.WebLoginChallenges.AsNoTracking().SingleAsync()).ConsumedAt);

        await webContext.Database.EnsureCreatedAsync();
        var retried = await repository.ConsumeChallengeAsync(request);
        Assert.NotNull(retried);
        Assert.Equal(userId, retried.UserUuid);
        Assert.Single(await webContext.WebUsers.ToListAsync());
        Assert.Null(await repository.ConsumeChallengeAsync(request));
    }

    private static WebAuthRepository CreateRepository(
        AstralRecordDbContext gameContext,
        WebSiteDbContext webSiteContext) =>
        new(gameContext, webSiteContext, Microsoft.Extensions.Options.Options.Create(new WebAuthOptions
        {
            ChallengeMinutes = 5,
            LoginUrl = "https://example.com/Login",
        }));

    private static WebLoginChallengeCreateRequest CreateChallengeRequest(Guid userId, string mcid, DateTime now) => new()
    {
        UserUuid = userId,
        Mcid = mcid,
        ServerId = "test-server",
        RequestedAt = now,
    };

    private static DbContextOptions<AstralRecordDbContext> CreateGameOptions(SqliteConnection connection) =>
        new DbContextOptionsBuilder<AstralRecordDbContext>().UseSqlite(connection).Options;

    private static DbContextOptions<WebSiteDbContext> CreateWebSiteOptions(SqliteConnection connection) =>
        new DbContextOptionsBuilder<WebSiteDbContext>().UseSqlite(connection).Options;

    private static async Task SeedGameUserAsync(
        DbContextOptions<AstralRecordDbContext> options,
        Guid userId,
        string mcid,
        Guid accountId,
        DateTime now)
    {
        await using var setupContext = new AstralRecordDbContext(options);
        await CreateSchemaAsync(setupContext);
        setupContext.Users.Add(new UserEntity
        {
            Uuid = userId,
            Mcid = mcid,
            JoinDate = now,
            LastJoinDate = now,
            GlobalIp = "127.0.0.1",
            AccountId = accountId,
            Permission = 99,
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

    private static async Task CreateSchemaAsync(AstralRecordDbContext dbContext)
    {
        await dbContext.Database.ExecuteSqlRawAsync(@"
            CREATE TABLE IF NOT EXISTS user (
                uuid TEXT NOT NULL PRIMARY KEY, mcid TEXT NOT NULL, join_date TEXT NOT NULL,
                last_join_date TEXT NOT NULL, global_ip TEXT NOT NULL, account_id TEXT NULL,
                ban_indefinite INTEGER NOT NULL, ban_date TEXT NULL, kick_ip INTEGER NOT NULL,
                permission INTEGER NOT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL,
                created_by TEXT NOT NULL, updated_by TEXT NOT NULL, is_deleted INTEGER NOT NULL
            );
            CREATE TABLE IF NOT EXISTS account (
                uuid TEXT NOT NULL PRIMARY KEY, user_id TEXT NOT NULL, account_name TEXT NOT NULL,
                slot_index INTEGER NOT NULL, is_active INTEGER NOT NULL, mode INTEGER NOT NULL,
                menu_shortcuts_json TEXT NOT NULL, level INTEGER NOT NULL, total_experience INTEGER NOT NULL,
                highest_level INTEGER NOT NULL DEFAULT 1, rebirth_original_level INTEGER NULL,
                rebirth_experience_remainder INTEGER NOT NULL DEFAULT 0, class_id TEXT NOT NULL DEFAULT '',
                class_level INTEGER NOT NULL DEFAULT 0, class_experience INTEGER NOT NULL DEFAULT 0,
                progress_version INTEGER NOT NULL DEFAULT 0, created_at TEXT NOT NULL, updated_at TEXT NOT NULL,
                created_by TEXT NOT NULL, updated_by TEXT NOT NULL, is_deleted INTEGER NOT NULL
            );
            CREATE TABLE IF NOT EXISTS web_login_challenge (
                challenge_id TEXT NOT NULL PRIMARY KEY, user_id TEXT NOT NULL, login_code_hash TEXT NOT NULL,
                issued_at TEXT NOT NULL, expires_at TEXT NOT NULL, consumed_at TEXT NULL, revoked_at TEXT NULL,
                failed_attempts INTEGER NOT NULL, issued_by_server TEXT NOT NULL, created_at TEXT NOT NULL
            );
            CREATE UNIQUE INDEX IF NOT EXISTS UX_web_login_challenge_login_code_hash
                ON web_login_challenge (login_code_hash);
        ");
    }
}
