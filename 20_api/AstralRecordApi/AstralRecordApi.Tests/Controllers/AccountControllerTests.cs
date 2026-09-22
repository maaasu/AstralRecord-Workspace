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
    public async Task Create_AllowsSystemUserNilUuid()
    {
        var response = new AccountResponse { Uuid = Guid.NewGuid() };
        var repository = new CreateRepository(response);
        var controller = new AccountController(repository);
        var request = new AccountCreateRequest
        {
            UserId = Guid.NewGuid(),
            AccountName = "Alice",
            SlotIndex = 0,
            Mode = 0,
            CreatedBy = Guid.Empty,
        };

        var result = await controller.Create(request);

        var created = Assert.IsType<CreatedAtActionResult>(result);
        Assert.Same(response, created.Value);
        Assert.Same(request, repository.Request);
    }

    [Fact]
    public async Task Create_RejectsEmptyUserId()
    {
        var repository = new CreateRepository(new AccountResponse());
        var controller = new AccountController(repository);

        var result = await controller.Create(new AccountCreateRequest
        {
            UserId = Guid.Empty,
            AccountName = "Alice",
            Mode = 0,
            CreatedBy = Guid.Empty,
        });

        var problem = Assert.IsType<ObjectResult>(result);
        Assert.Equal(StatusCodes.Status400BadRequest, problem.StatusCode);
        Assert.Null(repository.Request);
    }

    [Fact]
    public async Task Clone_AllowsSystemUserNilUuid()
    {
        var response = new AccountCloneResponse
        {
            Account = new AccountResponse { Uuid = Guid.NewGuid() },
        };
        var repository = new CloneRepository(response);
        var controller = new AccountController(repository);
        var sourceUuid = Guid.NewGuid();
        var request = new AccountCloneRequest
        {
            TargetUserId = Guid.NewGuid(),
            TargetSlotIndex = 1,
            CreatedBy = Guid.Empty,
        };

        var result = await controller.Clone(sourceUuid, request);

        var created = Assert.IsType<CreatedAtActionResult>(result);
        Assert.Same(response, created.Value);
        Assert.Equal(sourceUuid, repository.SourceUuid);
        Assert.Same(request, repository.Request);
    }

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

        public Task<AccountResponse?> ResolveAsync(string? selector, string? userMcid) =>
            throw new NotSupportedException();

        public Task<AccountResponse> CreateAsync(AccountCreateRequest request) =>
            throw new NotSupportedException();

        public Task<AccountCloneResponse?> CloneAsync(Guid sourceUuid, AccountCloneRequest request) =>
            throw new NotSupportedException();

        public Task<AccountResponse?> UpdateAsync(Guid uuid, AccountUpdateRequest request) =>
            throw new NotSupportedException();

        public Task<AccountDeleteResponse?> DeleteAsync(Guid uuid, AccountDeleteRequest request) =>
            Task.FromResult(response);
    }

    private sealed class CreateRepository(AccountResponse response) : IAccountRepository
    {
        public AccountCreateRequest? Request { get; private set; }

        public Task<AccountResponse> CreateAsync(AccountCreateRequest request)
        {
            Request = request;
            return Task.FromResult(response);
        }

        public Task<IReadOnlyList<AccountResponse>> GetByUserIdAsync(Guid userId) =>
            throw new NotSupportedException();

        public Task<AccountResponse?> GetByUuidAsync(Guid uuid) =>
            throw new NotSupportedException();

        public Task<AccountResponse?> ResolveAsync(string? selector, string? userMcid) =>
            throw new NotSupportedException();

        public Task<AccountCloneResponse?> CloneAsync(Guid sourceUuid, AccountCloneRequest request) =>
            throw new NotSupportedException();

        public Task<AccountResponse?> UpdateAsync(Guid uuid, AccountUpdateRequest request) =>
            throw new NotSupportedException();

        public Task<AccountDeleteResponse?> DeleteAsync(Guid uuid, AccountDeleteRequest request) =>
            throw new NotSupportedException();
    }

    private sealed class CloneRepository(AccountCloneResponse response) : IAccountRepository
    {
        public Guid? SourceUuid { get; private set; }
        public AccountCloneRequest? Request { get; private set; }

        public Task<AccountCloneResponse?> CloneAsync(Guid sourceUuid, AccountCloneRequest request)
        {
            SourceUuid = sourceUuid;
            Request = request;
            return Task.FromResult<AccountCloneResponse?>(response);
        }

        public Task<IReadOnlyList<AccountResponse>> GetByUserIdAsync(Guid userId) =>
            throw new NotSupportedException();

        public Task<AccountResponse?> GetByUuidAsync(Guid uuid) =>
            throw new NotSupportedException();

        public Task<AccountResponse?> ResolveAsync(string? selector, string? userMcid) =>
            throw new NotSupportedException();

        public Task<AccountResponse> CreateAsync(AccountCreateRequest request) =>
            throw new NotSupportedException();

        public Task<AccountResponse?> UpdateAsync(Guid uuid, AccountUpdateRequest request) =>
            throw new NotSupportedException();

        public Task<AccountDeleteResponse?> DeleteAsync(Guid uuid, AccountDeleteRequest request) =>
            throw new NotSupportedException();
    }
}
