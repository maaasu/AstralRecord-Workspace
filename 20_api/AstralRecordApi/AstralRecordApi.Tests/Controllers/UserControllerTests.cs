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

    private sealed class FakeUserRepository(IReadOnlyList<string> mcids) : IUserRepository
    {
        public string? LastPrefix { get; private set; }

        public Task<IReadOnlyList<string>> GetMcidsAsync(string? prefix)
        {
            LastPrefix = prefix;
            return Task.FromResult(mcids);
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
