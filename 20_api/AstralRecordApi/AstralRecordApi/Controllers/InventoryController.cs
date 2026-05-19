using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.AspNetCore.Mvc;

namespace AstralRecordApi.Controllers;

[ApiController]
[Route("api/[controller]")]
public class InventoryController(IInventoryRepository inventoryRepository) : ControllerBase
{
    [HttpGet]
    [ProducesResponseType(StatusCodes.Status200OK)]
    public async Task<IActionResult> GetByAccountId([FromQuery(Name = "account_id")] Guid accountId)
    {
        var inventories = await inventoryRepository.GetByAccountIdAsync(accountId);
        return Ok(inventories);
    }

    [HttpGet("{inventoryId:guid}")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<IActionResult> GetById(Guid inventoryId)
    {
        var inventory = await inventoryRepository.GetByIdAsync(inventoryId);
        if (inventory is null)
            return NotFound();

        return Ok(inventory);
    }

    [HttpPost]
    [ProducesResponseType(StatusCodes.Status201Created)]
    public async Task<IActionResult> Create([FromBody] InventoryCreateRequest request)
    {
        var created = await inventoryRepository.CreateAsync(request);
        return CreatedAtAction(nameof(GetById), new { inventoryId = created.InventoryId }, created);
    }

    [HttpPut("{inventoryId:guid}")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<IActionResult> Update(Guid inventoryId, [FromBody] InventoryUpdateRequest request)
    {
        var updated = await inventoryRepository.UpdateAsync(inventoryId, request);
        if (updated is null)
            return NotFound();

        return Ok(updated);
    }

    [HttpGet("{inventoryId:guid}/entries")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    public async Task<IActionResult> GetEntries(Guid inventoryId)
    {
        var entries = await inventoryRepository.GetEntriesByInventoryIdAsync(inventoryId);
        return Ok(entries);
    }

    [HttpGet("entries/{inventoryEntryId:guid}")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<IActionResult> GetEntryById(Guid inventoryEntryId)
    {
        var entry = await inventoryRepository.GetEntryByIdAsync(inventoryEntryId);
        if (entry is null)
            return NotFound();

        return Ok(entry);
    }

    [HttpPost("{inventoryId:guid}/entries")]
    [ProducesResponseType(StatusCodes.Status201Created)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<IActionResult> CreateEntry(Guid inventoryId, [FromBody] InventoryEntryCreateRequest request)
    {
        if (!HasValidPayload(request.ItemId, request.InstanceType, request.InstanceId) || request.Quantity < 1)
            return Problem(statusCode: StatusCodes.Status400BadRequest, title: "Validation failed", detail: "Entry payload is invalid.");

        var created = await inventoryRepository.CreateEntryAsync(inventoryId, request);
        if (created is null)
            return NotFound();

        return CreatedAtAction(nameof(GetEntryById), new { inventoryEntryId = created.InventoryEntryId }, created);
    }

    [HttpPut("entries/{inventoryEntryId:guid}")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<IActionResult> UpdateEntry(Guid inventoryEntryId, [FromBody] InventoryEntryUpdateRequest request)
    {
        if (!HasValidPayload(request.ItemId, request.InstanceType, request.InstanceId) || request.Quantity < 1)
            return Problem(statusCode: StatusCodes.Status400BadRequest, title: "Validation failed", detail: "Entry payload is invalid.");

        var updated = await inventoryRepository.UpdateEntryAsync(inventoryEntryId, request);
        if (updated is null)
            return NotFound();

        return Ok(updated);
    }

    [HttpDelete("entries/{inventoryEntryId:guid}")]
    [ProducesResponseType(StatusCodes.Status204NoContent)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<IActionResult> DeleteEntry(Guid inventoryEntryId, [FromQuery(Name = "updated_by")] Guid updatedBy)
    {
        var deleted = await inventoryRepository.DeleteEntryAsync(inventoryEntryId, updatedBy);
        if (deleted is null or false)
            return NotFound();

        return NoContent();
    }

    private static bool HasValidPayload(string? itemId, string? instanceType, Guid? instanceId)
    {
        var hasItemPayload = !string.IsNullOrWhiteSpace(itemId);
        var hasInstancePayload = !string.IsNullOrWhiteSpace(instanceType) && instanceId.HasValue;
        return hasItemPayload ^ hasInstancePayload;
    }
}
