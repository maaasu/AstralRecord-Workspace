using AstralRecordApi.Controllers;
using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.AspNetCore.Mvc;
using System.Text.Json;
using Xunit;

namespace AstralRecordApi.Tests.Controllers;

public class AccountSkillTreeControllerTests
{
    [Fact]
    public async Task RepairInvalidState_ReturnsBadRequest_WhenExpectedVersionIsNegative()
    {
        var controller = new AccountSkillTreeController(new ValidatingRepository());
        var request = new AccountSkillTreeInvalidStateRepairRequest
        {
            UserId = Guid.NewGuid(),
            ExpectedVersion = -1,
            UpdatedBy = Guid.NewGuid(),
        };

        var result = await controller.RepairInvalidState(Guid.NewGuid(), request);

        Assert.IsType<BadRequestResult>(result);
    }

    [Fact]
    public async Task RepairInvalidState_AcceptsLegacyRequestWithRepairKey()
    {
        var controller = new AccountSkillTreeController(new ValidatingRepository());
        var request = JsonSerializer.Deserialize<AccountSkillTreeInvalidStateRepairRequest>(
            """
            {
              "userId": "11111111-1111-1111-1111-111111111111",
              "updatedBy": "22222222-2222-2222-2222-222222222222",
              "repairKey": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
              "expectedVersion": 1
            }
            """,
            JsonSerializerOptions.Web);

        Assert.NotNull(request);
        Assert.Equal(1, request.ExpectedVersion);
        Assert.IsType<OkObjectResult>(await controller.RepairInvalidState(Guid.NewGuid(), request));
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
            if (request.ExpectedVersion < 0)
                throw new ArgumentException("Invalid repair request.", nameof(request));
            return Task.FromResult(new AccountSkillTreeStateResponse
            {
                AccountId = accountId,
                UnlockedNodes = [],
            });
        }
    }
}
