using AstralRecordApi.Controllers;
using AstralRecordApi.Models;
using AstralRecordApi.Services;
using Microsoft.AspNetCore.Mvc;
using Xunit;

namespace AstralRecordApi.Tests.Controllers;

public class EquipmentControllerOrbOperationTests
{
    public static TheoryData<EquipmentOrbOperationRequest> InvalidRequests => new()
    {
        InvalidRequest(request => request.OperationId = Guid.Empty),
        InvalidRequest(request => request.AccountId = Guid.Empty),
        InvalidRequest(request => request.EquipmentInstanceId = Guid.Empty),
        InvalidRequest(request => request.OrbInventoryEntryId = Guid.Empty),
        InvalidRequest(request => request.OrbItemId = " "),
    };

    [Theory]
    [MemberData(nameof(InvalidRequests))]
    public async Task ApplyOrb_RejectsMissingIdentifiersWithoutCallingService(
        EquipmentOrbOperationRequest request)
    {
        var service = new TestEquipmentService();
        var controller = new EquipmentController(service);

        var result = await controller.ApplyOrb(request);

        Assert.IsType<BadRequestResult>(result);
        Assert.Null(service.AppliedRequest);
    }

    [Fact]
    public async Task ApplyOrb_ReturnsOkForTerminalBusinessFailure()
    {
        var request = ValidRequest();
        var response = new EquipmentOrbOperationResponse
        {
            OperationId = request.OperationId,
            Result = "PAYMENT_UNAVAILABLE",
            OperationType = "TRANSCENDENCE",
        };
        var service = new TestEquipmentService { ApplyResult = response };
        var controller = new EquipmentController(service);

        var result = await controller.ApplyOrb(request);

        var ok = Assert.IsType<OkObjectResult>(result);
        Assert.Same(response, ok.Value);
        Assert.Same(request, service.AppliedRequest);
    }

    [Fact]
    public async Task ApplyOrb_AcceptsOrbItemIdAtUtf16Boundary()
    {
        var request = ValidRequest();
        request.OrbItemId = " " + string.Concat(Enumerable.Repeat("😀", 64)) + " ";
        Assert.Equal(EquipmentOrbOperationRequest.OrbItemIdMaxLength, request.OrbItemId.Trim().Length);
        var service = new TestEquipmentService();

        var result = await new EquipmentController(service).ApplyOrb(request);

        Assert.IsType<OkObjectResult>(result);
        Assert.Same(request, service.AppliedRequest);
    }

    [Fact]
    public async Task ApplyOrb_RejectsOrbItemIdAboveUtf16BoundaryWithoutCallingService()
    {
        var request = ValidRequest();
        request.OrbItemId = string.Concat(Enumerable.Repeat("😀", 65));
        Assert.True(request.OrbItemId.Length > EquipmentOrbOperationRequest.OrbItemIdMaxLength);
        var service = new TestEquipmentService();

        var result = await new EquipmentController(service).ApplyOrb(request);

        Assert.IsType<BadRequestResult>(result);
        Assert.Null(service.AppliedRequest);
    }

    [Fact]
    public async Task ApplyOrb_ReturnsConflictForReusedOperationIdWithDifferentRequest()
    {
        var request = ValidRequest();
        var response = new EquipmentOrbOperationResponse
        {
            OperationId = request.OperationId,
            Result = "OPERATION_CONFLICT",
            OperationType = string.Empty,
        };
        var controller = new EquipmentController(new TestEquipmentService { ApplyResult = response });

        var result = await controller.ApplyOrb(request);

        var conflict = Assert.IsType<ConflictObjectResult>(result);
        Assert.Same(response, conflict.Value);
    }

    [Fact]
    public async Task GetOrbOperation_UsesAccountScopedLookupAndReturnsStoredResult()
    {
        var operationId = Guid.NewGuid();
        var accountId = Guid.NewGuid();
        var response = new EquipmentOrbOperationResponse
        {
            OperationId = operationId,
            Result = "APPLIED",
            OperationType = "REPAIR",
            PaymentConsumed = true,
        };
        var service = new TestEquipmentService
        {
            FindHandler = (requestedOperationId, requestedAccountId) =>
                requestedOperationId == operationId && requestedAccountId == accountId ? response : null,
        };
        var controller = new EquipmentController(service);

        var result = await controller.GetOrbOperation(operationId, accountId);

        var ok = Assert.IsType<OkObjectResult>(result);
        Assert.Same(response, ok.Value);
        Assert.Equal((operationId, accountId), service.LookupRequest);
    }

    [Theory]
    [InlineData(true, false)]
    [InlineData(false, true)]
    public async Task GetOrbOperation_RejectsMissingIdentifiersWithoutLookingUpLedger(
        bool emptyOperationId,
        bool emptyAccountId)
    {
        var service = new TestEquipmentService();
        var controller = new EquipmentController(service);

        var result = await controller.GetOrbOperation(
            emptyOperationId ? Guid.Empty : Guid.NewGuid(),
            emptyAccountId ? Guid.Empty : Guid.NewGuid());

        Assert.IsType<BadRequestResult>(result);
        Assert.Null(service.LookupRequest);
    }

    [Fact]
    public async Task GetOrbOperation_HidesResultFromAnotherAccount()
    {
        var ownerId = Guid.NewGuid();
        var service = new TestEquipmentService
        {
            FindHandler = (_, accountId) => accountId == ownerId
                ? new EquipmentOrbOperationResponse
                {
                    OperationId = Guid.NewGuid(),
                    Result = "APPLIED",
                    OperationType = "ENHANCE",
                }
                : null,
        };
        var controller = new EquipmentController(service);

        var result = await controller.GetOrbOperation(Guid.NewGuid(), Guid.NewGuid());

        Assert.IsType<NotFoundResult>(result);
    }

    private static EquipmentOrbOperationRequest ValidRequest() => new()
    {
        OperationId = Guid.NewGuid(),
        AccountId = Guid.NewGuid(),
        EquipmentInstanceId = Guid.NewGuid(),
        OrbInventoryEntryId = Guid.NewGuid(),
        OrbItemId = "repair_orb",
    };

    private static EquipmentOrbOperationRequest InvalidRequest(Action<EquipmentOrbOperationRequest> mutate)
    {
        var request = ValidRequest();
        mutate(request);
        return request;
    }

    private sealed class TestEquipmentService : IEquipmentService
    {
        public EquipmentOrbOperationResponse ApplyResult { get; init; } = new()
        {
            OperationId = Guid.Empty,
            Result = "NOT_ELIGIBLE",
            OperationType = string.Empty,
        };

        public Func<Guid, Guid, EquipmentOrbOperationResponse?> FindHandler { get; init; } = (_, _) => null;

        public EquipmentOrbOperationRequest? AppliedRequest { get; private set; }

        public (Guid OperationId, Guid AccountId)? LookupRequest { get; private set; }

        public Task<EquipmentOrbOperationResponse> ApplyOrbAsync(EquipmentOrbOperationRequest request)
        {
            AppliedRequest = request;
            return Task.FromResult(ApplyResult);
        }

        public Task<EquipmentOrbOperationResponse?> FindOrbOperationAsync(Guid operationId, Guid accountId)
        {
            LookupRequest = (operationId, accountId);
            return Task.FromResult(FindHandler(operationId, accountId));
        }

        public Task<EquipmentInstanceResponse?> CreateAsync(EquipmentCreateRequest request) =>
            throw new NotSupportedException();

        public Task<EquipmentInstanceResponse?> GetByInstanceIdAsync(Guid instanceId) =>
            throw new NotSupportedException();

        public Task<EquipmentInstanceResponse?> DeleteEnchantAsync(EquipmentEnchantDeleteRequest request) =>
            throw new NotSupportedException();

        public Task<EquipmentInstanceResponse?> UpdateDurabilityAsync(EquipmentDurabilityUpdateRequest request) =>
            throw new NotSupportedException();

        public Task<bool> DeleteAsync(Guid instanceId) => throw new NotSupportedException();

        public Task<EquipmentInstanceResponse?> AttachRuneAsync(EquipmentRuneAttachRequest request) =>
            throw new NotSupportedException();

        public Task<EquipmentInstanceResponse?> DetachRuneAsync(EquipmentRuneDetachRequest request) =>
            throw new NotSupportedException();
    }
}
