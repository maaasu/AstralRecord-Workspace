using AstralRecordApi.Controllers;
using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Xunit;

namespace AstralRecordApi.Tests.Controllers;

public class InventoryControllerTests
{
    [Fact]
    public async Task ReplaceEntries_ReturnsNotFound_WhenInventoryDoesNotExist()
    {
        var controller = new InventoryController(new ReplaceRepository(null));

        var result = await controller.ReplaceEntries(Guid.NewGuid(), EmptyRequest());

        Assert.IsType<NotFoundResult>(result);
    }

    [Fact]
    public async Task ReplaceEntries_ReturnsConflict_WhenInventoryExistsButSnapshotIsStale()
    {
        var controller = new InventoryController(new ReplaceRepository(new InventoryResponse()));

        var result = await controller.ReplaceEntries(Guid.NewGuid(), EmptyRequest());

        var conflict = Assert.IsType<ObjectResult>(result);
        Assert.Equal(StatusCodes.Status409Conflict, conflict.StatusCode);
        var problem = Assert.IsType<ProblemDetails>(conflict.Value);
        Assert.Equal("Inventory entry snapshot conflict", problem.Title);
    }

    private static InventoryEntryReplaceRequest EmptyRequest() => new()
    {
        UpdatedBy = Guid.NewGuid(),
        Entries = [],
    };

    private sealed class ReplaceRepository(InventoryResponse? inventory) : IInventoryRepository
    {
        public Task<IReadOnlyList<InventoryResponse>> GetByAccountIdAsync(Guid accountId) =>
            throw new NotSupportedException();

        public Task<InventoryResponse?> GetByIdAsync(Guid inventoryId) =>
            Task.FromResult(inventory);

        public Task<InventoryResponse> CreateAsync(InventoryCreateRequest request) =>
            throw new NotSupportedException();

        public Task<InventoryResponse?> UpdateAsync(Guid inventoryId, InventoryUpdateRequest request) =>
            throw new NotSupportedException();

        public Task<IReadOnlyList<InventoryEntryResponse>> GetEntriesByInventoryIdAsync(Guid inventoryId) =>
            throw new NotSupportedException();

        public Task<InventoryEntryResponse?> GetEntryByIdAsync(Guid inventoryEntryId) =>
            throw new NotSupportedException();

        public Task<InventoryEntryResponse?> CreateEntryAsync(Guid inventoryId, InventoryEntryCreateRequest request) =>
            throw new NotSupportedException();

        public Task<InventoryEntryResponse?> UpdateEntryAsync(Guid inventoryEntryId, InventoryEntryUpdateRequest request) =>
            throw new NotSupportedException();

        public Task<IReadOnlyList<InventoryEntryResponse>?> ReplaceEntriesAsync(
            Guid inventoryId,
            InventoryEntryReplaceRequest request
        ) => Task.FromResult<IReadOnlyList<InventoryEntryResponse>?>(null);

        public Task<bool?> DeleteEntryAsync(Guid inventoryEntryId, Guid updatedBy) =>
            throw new NotSupportedException();
    }
}
