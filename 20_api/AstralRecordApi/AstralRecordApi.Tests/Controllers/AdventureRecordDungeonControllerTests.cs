using AstralRecordApi.Controllers;
using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.AspNetCore.Mvc;
using Xunit;

namespace AstralRecordApi.Tests.Controllers;

public class AdventureRecordDungeonControllerTests
{
    [Fact]
    public async Task GetDungeonRecords_ReturnsBadRequestForEmptyAccountId()
    {
        var repository = new RecordingRepository();

        var result = await new AdventureRecordController(repository)
            .GetDungeonRecords(Guid.Empty);

        Assert.IsType<BadRequestResult>(result);
        Assert.Equal(0, repository.ListCalls);
    }

    [Fact]
    public async Task RecordDungeonClear_DelegatesValidRequestAndReturnsRecord()
    {
        var repository = new RecordingRepository();
        var request = new AccountDungeonClearRequest
        {
            AccountId = Guid.NewGuid(),
            DungeonId = "twilight_mine",
            UpdatedBy = Guid.NewGuid(),
        };

        var result = await new AdventureRecordController(repository)
            .RecordDungeonClear(request);

        var ok = Assert.IsType<OkObjectResult>(result);
        var response = Assert.IsType<AccountDungeonRecordResponse>(ok.Value);
        Assert.Equal("twilight_mine", response.DungeonId);
        Assert.Equal(1, repository.ClearCalls);
    }

    [Theory]
    [InlineData("")]
    [InlineData("   ")]
    public async Task RecordDungeonClear_ReturnsBadRequestForBlankDungeonId(string dungeonId)
    {
        var repository = new RecordingRepository();
        var request = new AccountDungeonClearRequest
        {
            AccountId = Guid.NewGuid(),
            DungeonId = dungeonId,
            UpdatedBy = Guid.NewGuid(),
        };

        var result = await new AdventureRecordController(repository)
            .RecordDungeonClear(request);

        Assert.IsType<BadRequestResult>(result);
        Assert.Equal(0, repository.ClearCalls);
    }

    private sealed class RecordingRepository : IAdventureRecordRepository
    {
        public int ListCalls { get; private set; }
        public int ClearCalls { get; private set; }

        public Task<IReadOnlyList<AccountMobRecordResponse>> GetMobRecordsByAccountIdAsync(
            Guid accountId,
            string? category) => Task.FromResult<IReadOnlyList<AccountMobRecordResponse>>([]);

        public Task<AccountMobRecordResponse> RecordMobDefeatAsync(AccountMobDefeatRequest request) =>
            throw new NotSupportedException();

        public Task<IReadOnlyList<AccountDungeonRecordResponse>> GetDungeonRecordsByAccountIdAsync(
            Guid accountId)
        {
            ListCalls++;
            return Task.FromResult<IReadOnlyList<AccountDungeonRecordResponse>>([]);
        }

        public Task<AccountDungeonRecordResponse> RecordDungeonClearAsync(
            AccountDungeonClearRequest request)
        {
            ClearCalls++;
            var now = DateTime.UtcNow;
            return Task.FromResult(new AccountDungeonRecordResponse
            {
                AccountDungeonRecordId = Guid.NewGuid(),
                AccountId = request.AccountId,
                DungeonId = request.DungeonId,
                ClearCount = 1,
                FirstClearedAt = now,
                LastClearedAt = now,
                CreatedAt = now,
                UpdatedAt = now,
                CreatedBy = request.UpdatedBy,
                UpdatedBy = request.UpdatedBy,
            });
        }
    }
}
