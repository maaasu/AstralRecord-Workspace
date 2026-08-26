using AstralRecordApi.Controllers;
using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Xunit;

namespace AstralRecordApi.Tests.Controllers;

public class AccountControllerTests
{
    [Fact]
    public async Task Delete_ReturnsBadRequest_WhenDeletedByIsEmpty()
    {
        var controller = new AccountController(new DeleteRepository(null));

        var result = await controller.Delete(Guid.NewGuid(), new AccountDeleteRequest());

        var problem = Assert.IsType<ObjectResult>(result);
        Assert.Equal(StatusCodes.Status400BadRequest, problem.StatusCode);
    }

    [Fact]
    public async Task Delete_ReturnsSelectedReplacementOrRemainingAccount()
    {
        var deletedAccountId = Guid.NewGuid();
        var selectedAccountId = Guid.NewGuid();
        var controller = new AccountController(new DeleteRepository(new AccountDeleteResponse
        {
            DeletedAccountId = deletedAccountId,
            UserId = Guid.NewGuid(),
            DeletedSlotIndex = 2,
            SelectedAccountId = selectedAccountId,
            CreatedReplacement = true,
        }));

        var result = await controller.Delete(deletedAccountId, new AccountDeleteRequest { DeletedBy = Guid.NewGuid() });

        var ok = Assert.IsType<OkObjectResult>(result);
        var response = Assert.IsType<AccountDeleteResponse>(ok.Value);
        Assert.Equal(selectedAccountId, response.SelectedAccountId);
        Assert.True(response.CreatedReplacement);
    }

    private sealed class DeleteRepository(AccountDeleteResponse? response) : IAccountRepository
    {
        public Task<IReadOnlyList<AccountResponse>> GetByUserIdAsync(Guid userId) =>
            throw new NotSupportedException();

        public Task<AccountResponse?> GetByUuidAsync(Guid uuid) =>
            throw new NotSupportedException();

        public Task<AccountResponse> CreateAsync(AccountCreateRequest request) =>
            throw new NotSupportedException();

        public Task<AccountResponse?> UpdateAsync(Guid uuid, AccountUpdateRequest request) =>
            throw new NotSupportedException();

        public Task<AccountDeleteResponse?> DeleteAsync(Guid uuid, AccountDeleteRequest request) =>
            Task.FromResult(response);
    }
}
