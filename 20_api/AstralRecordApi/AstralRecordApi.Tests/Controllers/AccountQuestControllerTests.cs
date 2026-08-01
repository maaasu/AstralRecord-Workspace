using AstralRecordApi.Controllers;
using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.AspNetCore.Mvc;
using Xunit;

namespace AstralRecordApi.Tests.Controllers;

public class AccountQuestControllerTests
{
    [Fact]
    public async Task Upsert_ReturnsBadRequest_WhenRepositoryRejectsInvalidState()
    {
        var controller = new AccountQuestController(new RejectingRepository());
        var request = new AccountQuestStateUpsertRequest
        {
            ActiveQuests = [],
            Completions = [],
            Cooldowns = [],
            UpdatedBy = Guid.Empty,
        };

        var result = await controller.Upsert(Guid.NewGuid(), request);

        Assert.IsType<BadRequestResult>(result);
    }

    private sealed class RejectingRepository : IAccountQuestStateRepository
    {
        public Task<AccountQuestStateResponse> GetByAccountIdAsync(Guid accountId) =>
            throw new NotSupportedException();

        public Task<AccountQuestStateResponse> UpsertAsync(Guid accountId, AccountQuestStateUpsertRequest request) =>
            throw new ArgumentException("Invalid account quest state.", nameof(request));
    }
}
