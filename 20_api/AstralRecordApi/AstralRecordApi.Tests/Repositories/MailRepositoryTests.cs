using System.Data.Common;
using System.Text;
using System.Text.Json.Nodes;
using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Repositories;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Diagnostics;
using Microsoft.Extensions.Caching.Memory;
using Xunit;

namespace AstralRecordApi.Tests.Repositories;

public sealed class MailRepositoryTests
{
    [Fact]
    public async Task GetAvailableByAccountId_PreservesCustomHeadTextureAfterStateMerge()
    {
        await using var fixture = await MailRepositoryFixture.CreateAsync();
        var master = fixture.Mail("head-mail", DateTime.UtcNow.AddMinutes(-1));
        var payload = JsonNode.Parse(master.PayloadJson)!.AsObject();
        var texture = Convert.ToBase64String(Encoding.UTF8.GetBytes(
            """{"textures":{"SKIN":{"url":"https://textures.minecraft.net/texture/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}}}"""));
        payload["icon"] = "PLAYER_HEAD";
        payload["iconTexture"] = texture;
        master.PayloadJson = payload.ToJsonString();
        await fixture.SeedAsync(
            accountCreatedAt: DateTime.UtcNow.AddMinutes(-5),
            masters: [master],
            states: [fixture.State("head-mail", isRead: true)]);

        using var cache = new MemoryCache(new MemoryCacheOptions());
        var mail = Assert.Single(await fixture.CreateRepository(cache).GetAvailableByAccountIdAsync(fixture.AccountId, "all"));

        Assert.Equal(texture, mail.IconTexture);
        Assert.True(mail.IsRead);
    }

    /// <summary>
    /// 検証契約: 同一プロセス内で同時に未読件数を取得した場合、空の共有マスタキャッシュは一度だけ読込んで以後の要求が同じ結果を返す。
    /// </summary>
    [Fact]
    public async Task CountUnreadAsync_ConcurrentCacheMissLoadsMailMastersOnce()
    {
        var fixture = await MailRepositoryFixture.CreateAsync();
        await using var _ = fixture;
        await fixture.SeedAsync(
            accountCreatedAt: DateTime.UtcNow.AddMinutes(-5),
            masters: [fixture.Mail("visible-unread", DateTime.UtcNow.AddMinutes(-1))]);

        var masterSelects = new MasterMailSelectCounter();
        using var cache = new MemoryCache(new MemoryCacheOptions());
        var repositories = Enumerable.Range(0, 8)
            .Select(_ => fixture.CreateRepository(cache, masterSelects))
            .ToArray();

        var counts = await Task.WhenAll(repositories.Select(repository => repository.CountUnreadByAccountIdAsync(fixture.AccountId)));

        Assert.All(counts, count => Assert.Equal(1, count));
        Assert.Equal(1, masterSelects.Count);
    }

    /// <summary>
    /// 検証契約: 未読件数は公開期限内で未削除かつ未読のメールだけを数え、既読状態と初回ログイン条件を反映する。
    /// </summary>
    [Fact]
    public async Task CountUnreadAsync_ExcludesReadDeletedExpiredAndIneligibleFirstLoginMail()
    {
        var fixture = await MailRepositoryFixture.CreateAsync();
        await using var _ = fixture;
        var now = DateTime.UtcNow;
        await fixture.SeedAsync(
            accountCreatedAt: now.AddMinutes(-30),
            masters:
            [
                fixture.Mail("visible-unread", now.AddMinutes(-1)),
                fixture.Mail("read", now.AddMinutes(-1)),
                fixture.Mail("deleted", now.AddMinutes(-1)),
                fixture.Mail("expired", now.AddMinutes(-2), now.AddMinutes(-1)),
                fixture.Mail("first-login-ineligible", now.AddMinutes(-5), firstLoginOnly: true),
            ],
            states:
            [
                fixture.State("read", isRead: true),
                fixture.State("deleted", isDeleted: true),
            ]);

        using var cache = new MemoryCache(new MemoryCacheOptions());
        var repository = fixture.CreateRepository(cache);

        var count = await repository.CountUnreadByAccountIdAsync(fixture.AccountId);

        Assert.Equal(1, count);
    }

    /// <summary>マスターと指定アカウントの状態に応じた受取状態を返す。</summary>
    [Theory]
    [InlineData("対象者の未読", "AVAILABLE")]
    [InlineData("別アカウントのみ既読", "AVAILABLE")]
    [InlineData("既読かつ削除済みで公開終了", "RECEIVED")]
    [InlineData("未読かつ削除済み", "UNAVAILABLE")]
    [InlineData("公開終了", "UNAVAILABLE")]
    [InlineData("配信基準日より前に作成", "UNAVAILABLE")]
    [InlineData("マスターなし", "UNKNOWN")]
    [InlineData("未来公開", "UNKNOWN")]
    [InlineData("アカウントなし", "UNKNOWN")]
    [InlineData("アカウント削除済み", "UNKNOWN")]
    [InlineData("既読時受取の設定なし", "UNKNOWN")]
    [InlineData("指定報酬なし", "UNKNOWN")]
    [InlineData("不正なマスター JSON", "UNKNOWN")]
    public async Task GetWelcomeTutorialStatusAsync_ReturnsStatusForRequestedAccount(
        string scenario, string expected)
    {
        const string mailId = "welcome_mail";
        var now = DateTime.UtcNow;
        var publishFrom = scenario == "未来公開" ? now.AddDays(1) : now.AddDays(-2);
        DateTime? publishTo = scenario is "公開終了" or "既読かつ削除済みで公開終了"
            ? now.AddDays(-1)
            : null;
        var accountId = Guid.NewGuid();

        await using var fixture = await MailRepositoryFixture.CreateAsync();
        await using var player = fixture.CreatePlayerContext();
        await using var master = fixture.CreateMasterContext();
        using var cache = new MemoryCache(new MemoryCacheOptions());

        var accountCreatedAt = scenario == "配信基準日より前に作成"
            ? publishFrom.AddDays(-1)
            : now.AddHours(-1);
        if (scenario != "アカウントなし")
        {
            player.Accounts.Add(new AccountEntity
            {
                Uuid = accountId,
                UserId = Guid.NewGuid(),
                AccountName = "welcome-tutorial-test",
                CreatedAt = accountCreatedAt,
                UpdatedAt = now,
                CreatedBy = accountId,
                UpdatedBy = accountId,
                IsDeleted = scenario == "アカウント削除済み",
            });
        }

        if (scenario is "既読かつ削除済みで公開終了" or "未読かつ削除済み" or "別アカウントのみ既読")
        {
            var stateAccountId = scenario == "別アカウントのみ既読" ? Guid.NewGuid() : accountId;
            var isRead = scenario != "未読かつ削除済み";
            var isDeleted = scenario != "別アカウントのみ既読";
            player.PlayerMailStates.Add(new PlayerMailStateEntity
            {
                PlayerMailStateId = Guid.NewGuid(),
                AccountId = stateAccountId,
                MailId = mailId,
                IsRead = isRead,
                ReadAt = isRead ? now.AddHours(-2) : null,
                IsDeleted = isDeleted,
                DeletedAt = isDeleted ? now.AddHours(-1) : null,
                Version = 1,
                CreatedAt = now.AddHours(-3),
                UpdatedAt = now,
                CreatedBy = stateAccountId,
                UpdatedBy = stateAccountId,
            });
        }

        if (scenario != "マスターなし")
        {
            var payload = System.Text.Json.JsonSerializer.Serialize(new
            {
                schemaVersion = 1,
                id = mailId,
                icon = "PAPER",
                title = "初回チュートリアル",
                body = "報酬を受け取れます。",
                publishFrom,
                publishTo,
                firstLoginOnly = true,
                receiveOnRead = scenario != "既読時受取の設定なし",
                rewards = new[]
                {
                    new
                    {
                        itemId = scenario == "指定報酬なし" ? "50a00002" : "50a00001",
                        category = "ITEM",
                        amount = 1,
                    },
                },
            });
            master.Entries.Add(new MasterDataEntryEntity
            {
                EntryId = Guid.NewGuid(),
                SourceId = Guid.NewGuid(),
                MasterType = "mail",
                MasterId = mailId,
                SchemaVersion = 1,
                SourceFilePath = $"test/{mailId}.json",
                SourceFileHash = new string('0', 64),
                PayloadJson = scenario == "不正なマスター JSON" ? "{" : payload,
                EffectiveFrom = publishFrom,
                CreatedAt = now,
                UpdatedAt = now,
                CreatedBy = accountId,
                UpdatedBy = accountId,
            });
        }

        await player.SaveChangesAsync();
        await master.SaveChangesAsync();
        var stateCount = await player.PlayerMailStates.AsNoTracking().CountAsync();
        player.ChangeTracker.Clear();
        master.ChangeTracker.Clear();

        var repository = new MailRepository(player, master, cache);
        var status = await repository.GetWelcomeTutorialStatusAsync(accountId);

        Assert.Equal(expected, status);
        Assert.Empty(player.ChangeTracker.Entries());
        Assert.Empty(master.ChangeTracker.Entries());
        Assert.Equal(stateCount, await player.PlayerMailStates.AsNoTracking().CountAsync());
    }

    private sealed class MailRepositoryFixture : IAsyncDisposable
    {
        private readonly SqliteConnection playerAnchor;
        private readonly SqliteConnection masterAnchor;
        private readonly string playerConnectionString;
        private readonly string masterConnectionString;
        private readonly List<IAsyncDisposable> ownedContexts = [];

        private MailRepositoryFixture(
            SqliteConnection playerAnchor,
            SqliteConnection masterAnchor,
            string playerConnectionString,
            string masterConnectionString)
        {
            this.playerAnchor = playerAnchor;
            this.masterAnchor = masterAnchor;
            this.playerConnectionString = playerConnectionString;
            this.masterConnectionString = masterConnectionString;
        }

        public Guid AccountId { get; } = Guid.NewGuid();

        public static async Task<MailRepositoryFixture> CreateAsync()
        {
            var suffix = Guid.NewGuid().ToString("N");
            var playerConnectionString = $"Data Source=file:mail-player-{suffix}?mode=memory&cache=shared";
            var masterConnectionString = $"Data Source=file:mail-master-{suffix}?mode=memory&cache=shared";
            var playerAnchor = new SqliteConnection(playerConnectionString);
            var masterAnchor = new SqliteConnection(masterConnectionString);
            await playerAnchor.OpenAsync();
            await masterAnchor.OpenAsync();
            await using (var player = new AstralRecordDbContext(new DbContextOptionsBuilder<AstralRecordDbContext>()
                .UseSqlite(playerAnchor).Options))
                await player.Database.EnsureCreatedAsync();
            await using (var master = new MasterDataDbContext(new DbContextOptionsBuilder<MasterDataDbContext>()
                .UseSqlite(masterAnchor).Options))
                await master.Database.EnsureCreatedAsync();
            return new MailRepositoryFixture(playerAnchor, masterAnchor, playerConnectionString, masterConnectionString);
        }

        public async Task SeedAsync(
            DateTime accountCreatedAt,
            IReadOnlyList<MasterDataEntryEntity> masters,
            IReadOnlyList<PlayerMailStateEntity>? states = null)
        {
            await using (var player = CreatePlayerContext())
            {
                player.Accounts.Add(new AccountEntity
                {
                    Uuid = AccountId,
                    UserId = Guid.NewGuid(),
                    AccountName = "mail-test",
                    CreatedAt = accountCreatedAt,
                    UpdatedAt = accountCreatedAt,
                    CreatedBy = AccountId,
                    UpdatedBy = AccountId,
                });
                if (states is not null)
                    player.PlayerMailStates.AddRange(states);
                await player.SaveChangesAsync();
            }
            await using var master = CreateMasterContext();
            master.Entries.AddRange(masters);
            await master.SaveChangesAsync();
        }

        public MailRepository CreateRepository(IMemoryCache cache, DbCommandInterceptor? interceptor = null)
        {
            var player = CreatePlayerContext();
            var master = CreateMasterContext(interceptor);
            ownedContexts.Add(player);
            ownedContexts.Add(master);
            return new MailRepository(player, master, cache);
        }

        public MasterDataEntryEntity Mail(
            string id,
            DateTime publishFrom,
            DateTime? publishTo = null,
            bool firstLoginOnly = false) => new()
        {
            EntryId = Guid.NewGuid(),
            SourceId = Guid.NewGuid(),
            MasterType = "mail",
            MasterId = id,
            SchemaVersion = 1,
            SourceFilePath = $"test/{id}.json",
            SourceFileHash = new string('0', 64),
            PayloadJson = $$"""
                {
                  "schemaVersion": 1,
                  "id": "{{id}}",
                  "icon": "PAPER",
                  "title": "{{id}}",
                  "body": "test",
                  "publishFrom": "{{publishFrom:O}}",
                  "publishTo": {{(publishTo is null ? "null" : $"\"{publishTo.Value:O}\"")}},
                  "firstLoginOnly": {{firstLoginOnly.ToString().ToLowerInvariant()}},
                  "receiveOnRead": false,
                  "rewards": []
                }
                """,
            EffectiveFrom = publishFrom,
            CreatedAt = publishFrom,
            UpdatedAt = publishFrom,
            CreatedBy = AccountId,
            UpdatedBy = AccountId,
        };

        public PlayerMailStateEntity State(string mailId, bool isRead = false, bool isDeleted = false) => new()
        {
            PlayerMailStateId = Guid.NewGuid(),
            AccountId = AccountId,
            MailId = mailId,
            IsRead = isRead,
            IsDeleted = isDeleted,
            Version = 1,
            CreatedAt = DateTime.UtcNow,
            UpdatedAt = DateTime.UtcNow,
            CreatedBy = AccountId,
            UpdatedBy = AccountId,
        };

        public async ValueTask DisposeAsync()
        {
            foreach (var context in ownedContexts)
                await context.DisposeAsync();
            await playerAnchor.DisposeAsync();
            await masterAnchor.DisposeAsync();
        }

        public AstralRecordDbContext CreatePlayerContext()
            => new(new DbContextOptionsBuilder<AstralRecordDbContext>()
                .UseSqlite(new SqliteConnection(playerConnectionString))
                .Options);

        public MasterDataDbContext CreateMasterContext(DbCommandInterceptor? interceptor = null)
        {
            var options = new DbContextOptionsBuilder<MasterDataDbContext>()
                .UseSqlite(new SqliteConnection(masterConnectionString));
            if (interceptor is not null)
                options.AddInterceptors(interceptor);
            return new MasterDataDbContext(options.Options);
        }
    }

    private sealed class MasterMailSelectCounter : DbCommandInterceptor
    {
        private int count;

        public int Count => Volatile.Read(ref count);

        public override ValueTask<InterceptionResult<DbDataReader>> ReaderExecutingAsync(
            DbCommand command,
            CommandEventData eventData,
            InterceptionResult<DbDataReader> result,
            CancellationToken cancellationToken = default)
        {
            if (command.CommandText.Contains("master_data_entry", StringComparison.OrdinalIgnoreCase)
                && command.CommandText.StartsWith("SELECT", StringComparison.OrdinalIgnoreCase))
                Interlocked.Increment(ref count);
            return base.ReaderExecutingAsync(command, eventData, result, cancellationToken);
        }
    }
}
