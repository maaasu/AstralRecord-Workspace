using System.Data.Common;
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
    /// <summary>
    /// 設計入力: 00_docs/20_API設計書/feature/18-mail/3-エンドポイント仕様/18_3.00-索引.md
    /// 章・見出し: # 18_3.00 メール API > ## 未読件数レスポンス
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
    /// 設計入力: 00_docs/20_API設計書/feature/18-mail/3-エンドポイント仕様/18_3.00-索引.md
    /// 章・見出し: # 18_3.00 メール API
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

        private AstralRecordDbContext CreatePlayerContext()
            => new(new DbContextOptionsBuilder<AstralRecordDbContext>()
                .UseSqlite(new SqliteConnection(playerConnectionString))
                .Options);

        private MasterDataDbContext CreateMasterContext(DbCommandInterceptor? interceptor = null)
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
