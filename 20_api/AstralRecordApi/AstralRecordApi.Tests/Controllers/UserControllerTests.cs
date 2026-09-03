using AstralRecordApi.Controllers;
using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.AspNetCore.Mvc;
using Xunit;

namespace AstralRecordApi.Tests.Controllers;

public class UserControllerTests
{
    /// <summary>
    /// 設計入力: 00_docs/20_API設計書/feature/01-user/3-エンドポイント仕様/01_3.01-取得系.md
    /// 章・見出し: # 01_3.01-取得系 > ## 1. 取得系エンドポイント仕様 > ### Minecraft ID 一覧取得
    /// 検証契約: prefix を受け取った MCID 一覧取得は repository の候補を 200 OK の JSON 配列として返す。
    /// </summary>
    [Fact]
    public async Task GetMcids_ReturnsRepositoryCandidates()
    {
        var repository = new FakeUserRepository(new[] { "Alice", "Alfred" });
        var controller = new UserController(repository);

        var result = await controller.GetMcids("Al");

        var ok = Assert.IsType<OkObjectResult>(result);
        var mcids = Assert.IsAssignableFrom<IReadOnlyList<string>>(ok.Value);
        Assert.Equal(new[] { "Alice", "Alfred" }, mcids);
        Assert.Equal("Al", repository.LastPrefix);
    }

    /// <summary>
    /// 設計入力: 00_docs/20_API設計書/feature/01-user/3-エンドポイント仕様/01_3.01-取得系.md
    /// 章・見出し: # 01_3.01-取得系 > ## 1. 取得系エンドポイント仕様 > ### 同一グローバルIPの別ユーザー有無取得
    /// 検証契約: globalIp と除外 UUID を受け取った同一IPの別ユーザー確認は repository の boolean を 200 OK で返す。
    /// </summary>
    [Fact]
    public async Task HasOtherByGlobalIp_ReturnsRepositoryResult()
    {
        var excludingUuid = Guid.NewGuid();
        var repository = new FakeUserRepository(Array.Empty<string>(), true);
        var controller = new UserController(repository);

        var result = await controller.HasOtherByGlobalIp("127.0.0.1", excludingUuid);

        var ok = Assert.IsType<OkObjectResult>(result);
        Assert.True(Assert.IsType<bool>(ok.Value));
        Assert.Equal("127.0.0.1", repository.LastGlobalIp);
        Assert.Equal(excludingUuid, repository.LastExcludingUuid);
    }

    /// <summary>
    /// 設計入力: 00_docs/20_API設計書/feature/01-user/3-エンドポイント仕様/01_3.01-取得系.md
    /// 章・見出し: # 01_3.01-取得系 > ## 1. 取得系エンドポイント仕様 > ### 同一グローバルIPの別ユーザー有無取得
    /// 検証契約: globalIp または除外 UUID が未指定の場合は repository を呼ばず 400 Bad Request を返す。
    /// </summary>
    [Fact]
    public async Task HasOtherByGlobalIp_RejectsBlankIpOrMissingUuid()
    {
        var repository = new FakeUserRepository(Array.Empty<string>(), true);
        var controller = new UserController(repository);

        var result = await controller.HasOtherByGlobalIp(" ", Guid.NewGuid());

        Assert.IsType<BadRequestResult>(result);
        Assert.Null(repository.LastGlobalIp);
        Assert.Null(repository.LastExcludingUuid);

        result = await controller.HasOtherByGlobalIp("127.0.0.1", null);

        Assert.IsType<BadRequestResult>(result);
        Assert.Null(repository.LastGlobalIp);
        Assert.Null(repository.LastExcludingUuid);
    }

    private sealed class FakeUserRepository(
        IReadOnlyList<string> mcids,
        bool hasOtherByGlobalIp = false) : IUserRepository
    {
        public string? LastPrefix { get; private set; }
        public string? LastGlobalIp { get; private set; }
        public Guid? LastExcludingUuid { get; private set; }

        public Task<IReadOnlyList<string>> GetMcidsAsync(string? prefix)
        {
            LastPrefix = prefix;
            return Task.FromResult(mcids);
        }

        public Task<bool> HasOtherByGlobalIpAsync(string globalIp, Guid excludingUuid)
        {
            LastGlobalIp = globalIp;
            LastExcludingUuid = excludingUuid;
            return Task.FromResult(hasOtherByGlobalIp);
        }

        public Task<UserResponse?> GetByUuidAsync(Guid uuid) =>
            throw new NotSupportedException();

        public Task<UserResponse?> GetByMcidAsync(string mcid) =>
            throw new NotSupportedException();

        public Task<UserResponse> CreateAsync(UserCreateRequest request) =>
            throw new NotSupportedException();

        public Task<UserResponse?> UpdateAsync(Guid uuid, UserUpdateRequest request) =>
            throw new NotSupportedException();

        public Task<UserHistoryResponse> CreateHistoryAsync(UserHistoryCreateRequest request) =>
            throw new NotSupportedException();
    }
}
