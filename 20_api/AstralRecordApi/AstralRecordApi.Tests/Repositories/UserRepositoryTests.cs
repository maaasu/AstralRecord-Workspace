using AstralRecordApi.Data;
using AstralRecordApi.Data.Entities;
using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Xunit;

namespace AstralRecordApi.Tests.Repositories;

public class UserRepositoryTests
{
    /// <summary>
    /// 設計入力: 00_docs/20_API設計書/feature/01-user/3-エンドポイント仕様/01_3.01-取得系.md
    /// 章・見出し: # 01_3.01-取得系 > ## 1. 取得系エンドポイント仕様 > ### Minecraft ID 一覧取得
    /// 検証契約: MCID一覧取得は論理削除済みを除外し、prefixを前方一致させた有効ユーザーをMCID昇順で返す。
    /// </summary>
    [Fact]
    public async Task GetMcidsAsync_ExcludesDeletedUsersAndOrdersByMcid()
    {
        await using var connection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync();
        var options = CreateOptions(connection);
        var now = DateTime.UtcNow;
        await using (var setupContext = new AstralRecordDbContext(options))
        {
            await setupContext.Database.EnsureCreatedAsync();
            setupContext.Users.AddRange(
                CreateUser("Alice", false, now),
                CreateUser("Alfred", false, now),
                CreateUser("Alonzo", true, now),
                CreateUser("Bob", false, now)
            );
            await setupContext.SaveChangesAsync();
        }

        await using var dbContext = new AstralRecordDbContext(options);
        var repository = new UserRepository(
            dbContext,
            new HistoryDbContext(new DbContextOptions<HistoryDbContext>()));

        var mcids = await repository.GetMcidsAsync("Al");

        Assert.Equal(new[] { "Alfred", "Alice" }, mcids);
    }

    /// <summary>
    /// 設計入力: 00_docs/20_API設計書/feature/01-user/3-エンドポイント仕様/01_3.01-取得系.md
    /// 章・見出し: # 01_3.01-取得系 > ## 1. 取得系エンドポイント仕様 > ### 同一グローバルIPの別ユーザー有無取得
    /// 検証契約: 同一IPの存在確認は論理削除済みと参加者本人を除外する。
    /// </summary>
    [Fact]
    public async Task HasOtherByGlobalIpAsync_ExcludesDeletedUsersAndTheParticipant()
    {
        await using var connection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync();
        var options = CreateOptions(connection);
        var now = DateTime.UtcNow;
        var alice = CreateUser("Alice", false, now);
        alice.GlobalIp = "203.0.113.10";
        var alfred = CreateUser("Alfred", false, now);
        alfred.GlobalIp = "203.0.113.10";
        var deleted = CreateUser("Alonzo", true, now);
        deleted.GlobalIp = "203.0.113.10";
        var differentIp = CreateUser("Bob", false, now);
        differentIp.GlobalIp = "203.0.113.11";
        await using (var setupContext = new AstralRecordDbContext(options))
        {
            await setupContext.Database.EnsureCreatedAsync();
            setupContext.Users.AddRange(alice, alfred, deleted, differentIp);
            await setupContext.SaveChangesAsync();
        }

        await using var dbContext = new AstralRecordDbContext(options);
        var repository = new UserRepository(
            dbContext,
            new HistoryDbContext(new DbContextOptions<HistoryDbContext>()));

        var hasOtherForAlice = await repository.HasOtherByGlobalIpAsync(" 203.0.113.10 ", alice.Uuid);
        var hasOtherForBob = await repository.HasOtherByGlobalIpAsync(" 203.0.113.11 ", differentIp.Uuid);

        Assert.True(hasOtherForAlice);
        Assert.False(hasOtherForBob);
    }

    /// <summary>
    /// 設計入力: 00_docs/20_API設計書/feature/01-user/3-エンドポイント仕様/01_3.03-更新系.md
    /// 章・見出し: # 01_3.03-更新系 > ## 1. 更新系エンドポイント仕様 > ### ユーザ更新
    /// 検証契約: banIndefinite=true のユーザー更新は同時指定された banDate を保存せず、既存期限をNULLへ戻す。
    /// </summary>
    [Fact]
    public async Task UpdateAsync_IndefiniteBanClearsBanDate()
    {
        await using var connection = new SqliteConnection("Data Source=:memory:");
        await connection.OpenAsync();
        var options = CreateOptions(connection);
        var now = DateTime.UtcNow;
        var user = CreateUser("Alice", false, now);
        user.BanDate = now.AddDays(7);
        await using (var setupContext = new AstralRecordDbContext(options))
        {
            await setupContext.Database.EnsureCreatedAsync();
            setupContext.Users.Add(user);
            await setupContext.SaveChangesAsync();
        }

        await using var dbContext = new AstralRecordDbContext(options);
        var repository = new UserRepository(
            dbContext,
            new HistoryDbContext(new DbContextOptions<HistoryDbContext>()));
        var requestedDate = now.AddDays(30);

        var updated = await repository.UpdateAsync(user.Uuid, new UserUpdateRequest
        {
            BanIndefinite = true,
            BanDate = requestedDate,
            UpdatedBy = user.Uuid,
        });

        Assert.NotNull(updated);
        Assert.True(updated!.BanIndefinite);
        Assert.Null(updated.BanDate);
        var persisted = await dbContext.Users.SingleAsync(candidate => candidate.Uuid == user.Uuid);
        Assert.Null(persisted.BanDate);
    }

    private static DbContextOptions<AstralRecordDbContext> CreateOptions(SqliteConnection connection) =>
        new DbContextOptionsBuilder<AstralRecordDbContext>()
            .UseSqlite(connection)
            .Options;

    private static UserEntity CreateUser(string mcid, bool isDeleted, DateTime now) => new()
    {
        Uuid = Guid.NewGuid(),
        Mcid = mcid,
        JoinDate = now,
        LastJoinDate = now,
        GlobalIp = "127.0.0.1",
        BanIndefinite = false,
        KickIp = true,
        Permission = 0,
        CreatedAt = now,
        UpdatedAt = now,
        CreatedBy = Guid.NewGuid(),
        UpdatedBy = Guid.NewGuid(),
        IsDeleted = isDeleted,
    };
}
