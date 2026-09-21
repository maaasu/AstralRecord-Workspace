using AstralRecordApi.Controllers;
using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.AspNetCore.Mvc;
using Xunit;

namespace AstralRecordApi.Tests.Controllers;

public sealed class PlayerActivityControllerTests
{
    [Fact]
    public async Task AdminEndpoints_RejectAnActorWithoutWebAdminPermission()
    {
        var result = await new PlayerActivityController(new Repository(), new Authorization(false)).GetMobs(Guid.NewGuid(), new PlayerActivityQuery());
        Assert.Equal(403, Assert.IsType<StatusCodeResult>(result).StatusCode);
    }

    [Fact]
    public async Task RecordBatch_RejectsAnEmptyBatchId()
    {
        var result = await new PlayerActivityController(new Repository(), new Authorization(true)).RecordBatch(new PlayerActivityBatchRequest());
        Assert.IsType<BadRequestObjectResult>(result);
    }

    private sealed class Authorization(bool admin) : IWebAuthRepository
    {
        public Task<bool> IsWebAdminAsync(Guid userUuid) => Task.FromResult(admin);
        public Task<WebLoginChallengeCreateResponse?> CreateChallengeAsync(WebLoginChallengeCreateRequest request) => throw new NotSupportedException();
        public Task<WebLoginChallengeConsumeResponse?> ConsumeChallengeAsync(WebLoginChallengeConsumeRequest request) => throw new NotSupportedException();
        public Task<bool> IsTrustedBrowserAsync(Guid userUuid, Guid sessionVersion, string? token) => throw new NotSupportedException();
        public Task<WebLoginChallengeUserResolveResult> ResolveUserByMcidAsync(string mcid) => throw new NotSupportedException();
        public Task<WebPasswordLoginResult> LoginWithPasswordAsync(WebPasswordLoginRequest request) => throw new NotSupportedException();
        public Task<WebCredentialResponse?> GetCredentialAsync(Guid userUuid) => throw new NotSupportedException();
        public Task<WebCredentialUpdateResult> UpdateCredentialAsync(Guid userUuid, WebCredentialUpdateRequest request) => throw new NotSupportedException();
    }

    private sealed class Repository : IPlayerActivityRepository
    {
        public Task<PlayerActivityBatchResponse> RecordBatchAsync(PlayerActivityBatchRequest request) => throw new ArgumentException();
        public Task<PagedPlayerActivityResponse<SameIpActivityResponse>> GetSameIpAsync(PlayerActivityQuery query) => throw new NotSupportedException();
        public Task<PagedPlayerActivityResponse<PlayerTradeActivityResponse>> GetTradesAsync(PlayerActivityQuery query) => throw new NotSupportedException();
        public Task<PagedPlayerActivityResponse<DungeonClearActivityResponse>> GetDungeonsAsync(PlayerActivityQuery query) => throw new NotSupportedException();
        public Task<PagedPlayerActivityResponse<DungeonPlayerSummaryResponse>> GetDungeonPlayersAsync(PlayerActivityQuery query) => throw new NotSupportedException();
        public Task<PagedPlayerActivityResponse<MobRankingResponse>> GetMobsAsync(PlayerActivityQuery query) => throw new NotSupportedException();
        public Task<PagedPlayerActivityResponse<MobPlayerSummaryResponse>> GetMobPlayersAsync(string mobId, PlayerActivityQuery query) => throw new NotSupportedException();
        public Task<PagedPlayerActivityResponse<MobPlayerDeathResponse>> GetMobDeathsAsync(string mobId, PlayerActivityQuery query) => throw new NotSupportedException();
    }
}
