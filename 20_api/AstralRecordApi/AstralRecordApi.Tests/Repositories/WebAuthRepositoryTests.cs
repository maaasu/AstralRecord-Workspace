using AstralRecordApi.Authentication;
using Microsoft.AspNetCore.DataProtection;
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
    /// 検証契約: ハイフンなしの正規化コードは一度だけ消費でき、成功時にManagementDBへ既定権限のプレイヤーを記録する。
    /// </summary>
    [Fact]
    public async Task ConsumeChallengeAsync_AcceptsNormalizedCodeOnceAndCreatesWebUser()
    {
        await using var gameConnection = new SqliteConnection("Data Source=:memory:");
        await using var managementConnection = new SqliteConnection("Data Source=:memory:");
        await gameConnection.OpenAsync();
        await managementConnection.OpenAsync();
        var gameOptions = CreateGameOptions(gameConnection);
        var managementOptions = CreateManagementOptions(managementConnection);
        var userId = Guid.NewGuid();
        var accountId = Guid.NewGuid();
        var now = DateTime.UtcNow;

        await SeedGameUserAsync(gameOptions, userId, "Tester", accountId, now);
        await using var managementSetup = new ManagementDbContext(managementOptions);
        await managementSetup.Database.EnsureCreatedAsync();

        await using var gameContext = new AstralRecordDbContext(gameOptions);
        await using var managementContext = new ManagementDbContext(managementOptions);
        var repository = CreateRepository(gameContext, managementContext);
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

        var webUser = await managementContext.Players.SingleAsync();
        Assert.Equal(userId, webUser.PlayerUuid);
        Assert.Equal("Tester", webUser.Mcid);
        Assert.False(webUser.WebAdmin);
        Assert.Equal(webUser.FirstWebLoginAt, webUser.LastWebLoginAt);
    }

    /// <summary>
    /// 設計入力: 00_docs/20_API設計書/feature/24-web-auth/1-モデル定義/24_1.00-モデル定義.md
    /// 検証契約: ManagementDBへの再ログイン記録はMCIDと最終ログイン日時を更新しても、既存のWeb管理フラグを変更しない。
    /// </summary>
    [Fact]
    public async Task ConsumeChallengeAsync_PreservesExistingWebAdminFlag()
    {
        await using var gameConnection = new SqliteConnection("Data Source=:memory:");
        await using var managementConnection = new SqliteConnection("Data Source=:memory:");
        await gameConnection.OpenAsync();
        await managementConnection.OpenAsync();
        var gameOptions = CreateGameOptions(gameConnection);
        var managementOptions = CreateManagementOptions(managementConnection);
        var userId = Guid.NewGuid();
        var accountId = Guid.NewGuid();
        var now = DateTime.UtcNow;
        await SeedGameUserAsync(gameOptions, userId, "BeforeRename", accountId, now);
        await using (var managementSetup = new ManagementDbContext(managementOptions))
        {
            await managementSetup.Database.EnsureCreatedAsync();
            managementSetup.Players.Add(new ManagementPlayerEntity
            {
                PlayerUuid = userId,
                Mcid = "BeforeRename",
                WebAdmin = true,
                CreatedAt = now.AddDays(-1),
                UpdatedAt = now.AddDays(-1),
                FirstWebLoginAt = now.AddDays(-1),
                LastWebLoginAt = now.AddDays(-1),
            });
            await managementSetup.SaveChangesAsync();
        }

        await using var gameContext = new AstralRecordDbContext(gameOptions);
        var gameUser = await gameContext.Users.SingleAsync();
        gameUser.Mcid = "AfterRename";
        await gameContext.SaveChangesAsync();
        await using var managementContext = new ManagementDbContext(managementOptions);
        var repository = CreateRepository(gameContext, managementContext);
        var created = await repository.CreateChallengeAsync(CreateChallengeRequest(userId, "AfterRename", now));

        Assert.NotNull(created);
        var consumed = await repository.ConsumeChallengeAsync(new WebLoginChallengeConsumeRequest { LoginCode = created.LoginCode });

        Assert.NotNull(consumed);
        Assert.True(consumed.WebAdmin);
        var webUser = await managementContext.Players.SingleAsync();
        Assert.True(webUser.WebAdmin);
        Assert.Equal("AfterRename", webUser.Mcid);
        Assert.True(webUser.LastWebLoginAt > webUser.FirstWebLoginAt);
    }

    /// <summary>
    /// 設計入力: 00_docs/20_API設計書/feature/24-web-auth/3-エンドポイント仕様/24_3.02-登録系.md
    /// 検証契約: コンソール用MCID解決は完全一致で一意な登録だけを受け入れ、重複するMCIDを拒否する。
    /// </summary>
    [Fact]
    public async Task ResolveUserByMcidAsync_RejectsAmbiguousMcid()
    {
        await using var gameConnection = new SqliteConnection("Data Source=:memory:");
        await using var managementConnection = new SqliteConnection("Data Source=:memory:");
        await gameConnection.OpenAsync();
        await managementConnection.OpenAsync();
        var gameOptions = CreateGameOptions(gameConnection);
        var managementOptions = CreateManagementOptions(managementConnection);
        var now = DateTime.UtcNow;
        await SeedGameUserAsync(gameOptions, Guid.NewGuid(), "Duplicate", Guid.NewGuid(), now);
        await SeedGameUserAsync(gameOptions, Guid.NewGuid(), "Duplicate", Guid.NewGuid(), now);
        await using var managementSetup = new ManagementDbContext(managementOptions);
        await managementSetup.Database.EnsureCreatedAsync();

        await using var gameContext = new AstralRecordDbContext(gameOptions);
        await using var managementContext = new ManagementDbContext(managementOptions);
        var repository = CreateRepository(gameContext, managementContext);

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
        await using var webContext = new ManagementDbContext(CreateManagementOptions(webConnection));
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
        Assert.Single(await webContext.Players.ToListAsync());
        Assert.Null(await repository.ConsumeChallengeAsync(request));
    }

    [Fact]
    public async Task ManagementPlayer_SurvivesGameResetAndSupportsFirstWebLoginLater()
    {
        await using var gameConnection = new SqliteConnection("Data Source=:memory:");
        await using var managementConnection = new SqliteConnection("Data Source=:memory:");
        await gameConnection.OpenAsync();
        await managementConnection.OpenAsync();
        var gameOptions = CreateGameOptions(gameConnection);
        var userId = Guid.NewGuid();
        var createdAt = DateTime.UtcNow.AddDays(-10);
        await SeedGameUserAsync(gameOptions, userId, "BeforeReset", Guid.NewGuid(), createdAt);
        await using var gameContext = new AstralRecordDbContext(gameOptions);
        await using var managementContext = new ManagementDbContext(CreateManagementOptions(managementConnection));
        await managementContext.Database.EnsureCreatedAsync();
        managementContext.Players.Add(new ManagementPlayerEntity
        {
            PlayerUuid = userId, Mcid = "BeforeReset", WebAdmin = true,
            CreatedAt = createdAt, UpdatedAt = createdAt,
        });
        await managementContext.SaveChangesAsync();
        var repository = CreateRepository(gameContext, managementContext);

        await gameContext.Accounts.ExecuteDeleteAsync();
        await gameContext.Users.ExecuteDeleteAsync();
        Assert.True(await repository.IsWebAdminAsync(userId));
        Assert.Null((await managementContext.Players.AsNoTracking().SingleAsync()).FirstWebLoginAt);

        await SeedGameUserAsync(gameOptions, userId, "AfterReset", Guid.NewGuid(), DateTime.UtcNow);
        var issued = await repository.CreateChallengeAsync(CreateChallengeRequest(userId, "AfterReset", DateTime.UtcNow));
        Assert.NotNull(issued);
        var consumed = await repository.ConsumeChallengeAsync(new WebLoginChallengeConsumeRequest { LoginCode = issued.LoginCode });
            Assert.NotNull(consumed);
        var retained = await managementContext.Players.AsNoTracking().SingleAsync();
        Assert.Equal(createdAt, retained.CreatedAt);
        Assert.Equal("AfterReset", retained.Mcid);
        Assert.True(retained.WebAdmin);
        Assert.NotNull(retained.FirstWebLoginAt);
        Assert.Equal(retained.FirstWebLoginAt, retained.LastWebLoginAt);
        Assert.True(retained.UpdatedAt > createdAt);
    }

    [Fact]
    public async Task ConsumeChallengeAsync_WrongExpectedUserDoesNotConsumeCode()
    {
        await using var gameConnection = new SqliteConnection("Data Source=:memory:");
        await using var managementConnection = new SqliteConnection("Data Source=:memory:");
        await gameConnection.OpenAsync();
        await managementConnection.OpenAsync();
        var gameOptions = CreateGameOptions(gameConnection);
        var managementOptions = CreateManagementOptions(managementConnection);
        var userId = Guid.NewGuid();
        await SeedGameUserAsync(gameOptions, userId, "SubjectCheck", Guid.NewGuid(), DateTime.UtcNow);
        await using var setup = new ManagementDbContext(managementOptions);
        await setup.Database.EnsureCreatedAsync();
        await using var game = new AstralRecordDbContext(gameOptions);
        await using var management = new ManagementDbContext(managementOptions);
        var repository = CreateRepository(game, management);
        var issued = await repository.CreateChallengeAsync(CreateChallengeRequest(userId, "SubjectCheck", DateTime.UtcNow));

        Assert.NotNull(issued);
        Assert.Null(await repository.ConsumeChallengeAsync(new WebLoginChallengeConsumeRequest { LoginCode = issued.LoginCode, ExpectedUserUuid = Guid.NewGuid() }));
        Assert.NotNull(await repository.ConsumeChallengeAsync(new WebLoginChallengeConsumeRequest { LoginCode = issued.LoginCode, ExpectedUserUuid = userId }));
    }

    [Fact]
    public async Task Credentials_UseCasRotateSessionAndRejectStaleUpdate()
    {
        var fixture = await PasswordFixture.CreateAsync();
        await using (fixture)
        {
            var initial = await fixture.Repository.GetCredentialAsync(fixture.UserId);
            Assert.NotNull(initial);
            var enabled = await fixture.Repository.UpdateCredentialAsync(fixture.UserId, new WebCredentialUpdateRequest
            {
                SessionVersion = initial.SessionVersion,
                Action = "enable",
                NewPassword = "CobaltHarbor!29",
                CodeAuthenticationProof = fixture.CodeProof,
            });
            Assert.Equal(WebCredentialUpdateStatus.Succeeded, enabled.Status);
            Assert.NotNull(enabled.Response);
            Assert.NotEqual(initial.SessionVersion, enabled.Response.SessionVersion);
            Assert.NotNull(enabled.Response.LoginId);

            var stale = await fixture.Repository.UpdateCredentialAsync(fixture.UserId, new WebCredentialUpdateRequest
            {
                SessionVersion = initial.SessionVersion,
                Action = "disable",
                CurrentPassword = "CobaltHarbor!29",
            });
            Assert.Equal(WebCredentialUpdateStatus.Stale, stale.Status);

            var login = await fixture.Repository.LoginWithPasswordAsync(new WebPasswordLoginRequest
            {
                LoginId = enabled.Response.LoginId!, Password = "CobaltHarbor!29",
            });
            Assert.Equal(WebPasswordLoginStatus.Succeeded, login.Status);
            Assert.Equal(enabled.Response.SessionVersion, login.Response!.SessionVersion);
        }
    }

    [Fact]
    public async Task PasswordLogin_UsesManagementIdentityAfterGameResetButRejectsExplicitDeletion()
    {
        var fixture = await PasswordFixture.CreateAsync();
        await using (fixture)
        {
            var initial = (await fixture.Repository.GetCredentialAsync(fixture.UserId))!;
            var enabled = (await fixture.Repository.UpdateCredentialAsync(fixture.UserId, new WebCredentialUpdateRequest
            {
                SessionVersion = initial.SessionVersion, Action = "enable", NewPassword = "CobaltHarbor!29", CodeAuthenticationProof = fixture.CodeProof,
            })).Response!;
            await fixture.Game.Users.ExecuteDeleteAsync();
            var afterReset = await fixture.Repository.LoginWithPasswordAsync(new WebPasswordLoginRequest { LoginId = enabled.LoginId!, Password = "CobaltHarbor!29" });
            Assert.Equal(WebPasswordLoginStatus.Succeeded, afterReset.Status);
            Assert.Equal(0, afterReset.Response!.Permission);
            Assert.Empty(afterReset.Response.AccountIds);

            await SeedGameUserAsync(fixture.GameOptions, fixture.UserId, "PasswordTester", Guid.NewGuid(), DateTime.UtcNow);
            var restored = await fixture.Game.Users.SingleAsync();
            restored.IsDeleted = true;
            await fixture.Game.SaveChangesAsync();
            Assert.Null(await fixture.Repository.GetCredentialAsync(fixture.UserId));
            var deleted = await fixture.Repository.LoginWithPasswordAsync(new WebPasswordLoginRequest { LoginId = enabled.LoginId!, Password = "CobaltHarbor!29" });
            Assert.Equal(WebPasswordLoginStatus.Invalid, deleted.Status);
        }
    }

    [Fact]
    public async Task PasswordLogin_PersistsFailedAttemptsAndThrottlesId()
    {
        var fixture = await PasswordFixture.CreateAsync();
        await using (fixture)
        {
            var initial = (await fixture.Repository.GetCredentialAsync(fixture.UserId))!;
            var enabled = (await fixture.Repository.UpdateCredentialAsync(fixture.UserId, new WebCredentialUpdateRequest
            {
                SessionVersion = initial.SessionVersion, Action = "enable", NewPassword = "CobaltHarbor!29", CodeAuthenticationProof = fixture.CodeProof,
            })).Response!;
            for (var attempt = 0; attempt < 10; attempt++)
            {
                var result = await fixture.Repository.LoginWithPasswordAsync(new WebPasswordLoginRequest { LoginId = enabled.LoginId!, Password = "wrong password" });
                Assert.Equal(WebPasswordLoginStatus.Invalid, result.Status);
            }
            var throttled = await fixture.Repository.LoginWithPasswordAsync(new WebPasswordLoginRequest { LoginId = enabled.LoginId!, Password = "CobaltHarbor!29" });
            Assert.Equal(WebPasswordLoginStatus.Throttled, throttled.Status);
        }
    }

    [Fact]
    public async Task Credentials_RequireRealCodeProof_ReserveIdAcrossDisable_AndRejectOldProof()
    {
        await using var fixture = await PasswordFixture.CreateAsync();
        var initial = (await fixture.Repository.GetCredentialAsync(fixture.UserId))!;
        var forged = new WebCredentialUpdateRequest
        {
            SessionVersion = initial.SessionVersion, Action = "enable", NewPassword = "CobaltHarbor!29",
            CodeAuthenticationProof = DateTimeOffset.UtcNow.ToString("O"),
        };
        Assert.Equal(WebCredentialUpdateStatus.Invalid, (await fixture.Repository.UpdateCredentialAsync(fixture.UserId, forged)).Status);
        forged.CodeAuthenticationProof = fixture.CodeProof;
        var enabled = (await fixture.Repository.UpdateCredentialAsync(fixture.UserId, forged)).Response!;
        Assert.NotNull(enabled);
        var oldProof = await fixture.Repository.UpdateCredentialAsync(fixture.UserId, new()
        {
            SessionVersion = enabled.SessionVersion, Action = "disable", CodeAuthenticationProof = fixture.CodeProof,
        });
        Assert.Equal(WebCredentialUpdateStatus.Invalid, oldProof.Status);
        var disabled = (await fixture.Repository.UpdateCredentialAsync(fixture.UserId, new()
        {
            SessionVersion = enabled.SessionVersion, Action = "disable", CodeAuthenticationProof = enabled.CodeAuthenticationProof,
        })).Response!;
        Assert.False(disabled.Enabled);
        Assert.Equal(enabled.LoginId, disabled.LoginId);
        Assert.Equal(enabled.CodeAuthenticatedAt, disabled.CodeAuthenticatedAt);
        Assert.Null((await fixture.Management.WebCredentials.AsNoTracking().SingleAsync()).PasswordHash);
        var login = await fixture.Repository.LoginWithPasswordAsync(new() { LoginId = enabled.LoginId!, Password = "CobaltHarbor!29" });
        Assert.Equal(WebPasswordLoginStatus.Invalid, login.Status);
        var restored = (await fixture.Repository.UpdateCredentialAsync(fixture.UserId, new()
        {
            SessionVersion = disabled.SessionVersion, Action = "enable", NewPassword = "SilverCanyon!84", CodeAuthenticationProof = disabled.CodeAuthenticationProof,
        })).Response!;
        Assert.True(restored.Enabled);
        Assert.Equal(enabled.LoginId, restored.LoginId);
    }

    [Fact]
    public async Task CurrentPasswordAttempts_SharePersistentLimit_ButCodeRecoveryStillWorks()
    {
        await using var fixture = await PasswordFixture.CreateAsync();
        var initial = (await fixture.Repository.GetCredentialAsync(fixture.UserId))!;
        var enabled = (await fixture.Repository.UpdateCredentialAsync(fixture.UserId, new()
        {
            SessionVersion = initial.SessionVersion, Action = "enable", NewPassword = "CobaltHarbor!29", CodeAuthenticationProof = fixture.CodeProof,
        })).Response!;
        for (var i = 0; i < 10; i++)
        {
            var wrong = await fixture.Repository.UpdateCredentialAsync(fixture.UserId, new()
            {
                SessionVersion = enabled.SessionVersion, Action = "disable", CurrentPassword = "incorrect",
            });
            Assert.Equal(WebCredentialUpdateStatus.Invalid, wrong.Status);
        }
        var blocked = await fixture.Repository.UpdateCredentialAsync(fixture.UserId, new()
        {
            SessionVersion = enabled.SessionVersion, Action = "disable", CurrentPassword = "CobaltHarbor!29",
        });
        Assert.Equal(WebCredentialUpdateStatus.Throttled, blocked.Status);
        Assert.Equal(WebPasswordLoginStatus.Throttled, (await fixture.Repository.LoginWithPasswordAsync(new()
        {
            LoginId = enabled.LoginId!, Password = "CobaltHarbor!29",
        })).Status);
        var recovery = await fixture.Repository.UpdateCredentialAsync(fixture.UserId, new()
        {
            SessionVersion = enabled.SessionVersion, Action = "change", NewPassword = "SilverCanyon!84", CodeAuthenticationProof = enabled.CodeAuthenticationProof,
        });
        Assert.Equal(WebCredentialUpdateStatus.Succeeded, recovery.Status);
        Assert.Equal(WebPasswordLoginStatus.Succeeded, (await fixture.Repository.LoginWithPasswordAsync(new()
        {
            LoginId = enabled.LoginId!, Password = "SilverCanyon!84",
        })).Status);
    }

    private sealed class PasswordFixture : IAsyncDisposable
    {
        private readonly SqliteConnection gameConnection;
        private readonly SqliteConnection managementConnection;
        public DbContextOptions<AstralRecordDbContext> GameOptions { get; private init; } = null!;
        public Guid UserId { get; private init; }
        public string CodeProof { get; private init; } = string.Empty;
        public AstralRecordDbContext Game { get; private init; } = null!;
        public ManagementDbContext Management { get; private init; } = null!;
        public WebAuthRepository Repository { get; private init; } = null!;

        private PasswordFixture(SqliteConnection gameConnection, SqliteConnection managementConnection)
        {
            this.gameConnection = gameConnection;
            this.managementConnection = managementConnection;
        }

        public static async Task<PasswordFixture> CreateAsync()
        {
            var gameConnection = new SqliteConnection("Data Source=:memory:");
            var managementConnection = new SqliteConnection("Data Source=:memory:");
            await gameConnection.OpenAsync();
            await managementConnection.OpenAsync();
            var gameOptions = CreateGameOptions(gameConnection);
            var managementOptions = CreateManagementOptions(managementConnection);
            var userId = Guid.NewGuid();
            await SeedGameUserAsync(gameOptions, userId, "PasswordTester", Guid.NewGuid(), DateTime.UtcNow);
            var managementSetup = new ManagementDbContext(managementOptions);
            await managementSetup.Database.EnsureCreatedAsync();
            var game = new AstralRecordDbContext(gameOptions);
            var management = new ManagementDbContext(managementOptions);
            var repository = CreateRepository(game, management);
            var issued = await repository.CreateChallengeAsync(CreateChallengeRequest(userId, "PasswordTester", DateTime.UtcNow));
            Assert.NotNull(issued);
            var consumed = await repository.ConsumeChallengeAsync(new WebLoginChallengeConsumeRequest { LoginCode = issued.LoginCode });
            Assert.NotNull(consumed);
            await managementSetup.DisposeAsync();
            return new PasswordFixture(gameConnection, managementConnection)
            {
                GameOptions = gameOptions, UserId = userId, Game = game, Management = management, Repository = repository, CodeProof = consumed.CodeAuthenticationProof!,
            };
        }

        public async ValueTask DisposeAsync()
        {
            await Game.DisposeAsync();
            await Management.DisposeAsync();
            await gameConnection.DisposeAsync();
            await managementConnection.DisposeAsync();
        }
    }

    private static WebAuthRepository CreateRepository(
        AstralRecordDbContext gameContext,
        ManagementDbContext managementContext) =>
        new(gameContext, managementContext, Microsoft.Extensions.Options.Options.Create(new WebAuthOptions
        {
            ChallengeMinutes = 5,
            LoginUrl = "https://example.com/Login",
        }), new WebCodeProofProtector(new EphemeralDataProtectionProvider()));

    private static WebLoginChallengeCreateRequest CreateChallengeRequest(Guid userId, string mcid, DateTime now) => new()
    {
        UserUuid = userId,
        Mcid = mcid,
        ServerId = "test-server",
        RequestedAt = now,
    };

    private static DbContextOptions<AstralRecordDbContext> CreateGameOptions(SqliteConnection connection) =>
        new DbContextOptionsBuilder<AstralRecordDbContext>().UseSqlite(connection).Options;

    private static DbContextOptions<ManagementDbContext> CreateManagementOptions(SqliteConnection connection) =>
        new DbContextOptionsBuilder<ManagementDbContext>().UseSqlite(connection).Options;

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
