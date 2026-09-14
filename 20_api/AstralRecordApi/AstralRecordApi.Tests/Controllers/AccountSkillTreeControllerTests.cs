using AstralRecordApi.Controllers;
using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.AspNetCore.Mvc;
using Xunit;

namespace AstralRecordApi.Tests.Controllers;

public class AccountSkillTreeControllerTests
{
    private const string ValidRepairKey = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    [Theory]
    [InlineData("invalid", 1)]
    [InlineData(ValidRepairKey, -1)]
    public async Task RepairInvalidState_ReturnsBadRequest_WhenRequestIsInvalid(
        string repairKey,
        int expectedVersion)
    {
        var controller = new AccountSkillTreeController(new ValidatingRepository());
        var request = new AccountSkillTreeInvalidStateRepairRequest
        {
            UserId = Guid.NewGuid(),
            RepairKey = repairKey,
            ExpectedVersion = expectedVersion,
            UpdatedBy = Guid.NewGuid(),
        };

        var result = await controller.RepairInvalidState(Guid.NewGuid(), request);

        Assert.IsType<BadRequestResult>(result);
    }

    private sealed class ValidatingRepository : IAccountSkillTreeStateRepository
    {
        public Task<AccountSkillTreeStateResponse> GetByAccountIdAsync(Guid accountId) =>
            throw new NotSupportedException();

        public Task<AccountSkillTreeStateResponse> UpsertAsync(
            Guid accountId,
            AccountSkillTreeStateUpsertRequest request) =>
            throw new NotSupportedException();

        public Task<AccountSkillTreeStateResponse> RepairInvalidStateAsync(
            Guid accountId,
            AccountSkillTreeInvalidStateRepairRequest request)
        {
            if (request.RepairKey.Length != 64 || request.ExpectedVersion < 0)
                throw new ArgumentException("Invalid repair request.", nameof(request));
            return Task.FromResult(new AccountSkillTreeStateResponse
            {
                AccountId = accountId,
                UnlockedNodes = [],
            });
        }
    }
}
