using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.AspNetCore.Mvc;

namespace AstralRecordApi.Controllers;

/// <summary>装備プリセット API</summary>
[ApiController]
[Route("api/equipment/loadouts")]
public class EquipmentLoadoutController(IEquipmentLoadoutRepository loadoutRepository) : ControllerBase
{
    [HttpGet]
    [ProducesResponseType(StatusCodes.Status200OK)]
    public async Task<IActionResult> GetByAccountId(
        [FromQuery(Name = "account_id")] Guid accountId,
        [FromQuery(Name = "loadout_profile")] string? loadoutProfile)
    {
        var loadouts = await loadoutRepository.GetByAccountIdAsync(accountId, loadoutProfile);
        return Ok(loadouts);
    }

    [HttpGet("{loadoutId:guid}")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<IActionResult> GetById(Guid loadoutId)
    {
        var loadout = await loadoutRepository.GetByIdAsync(loadoutId);
        if (loadout is null)
            return NotFound();

        return Ok(loadout);
    }

    [HttpPost]
    [ProducesResponseType(StatusCodes.Status201Created)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    public async Task<IActionResult> Create([FromBody] EquipmentLoadoutCreateRequest request)
    {
        if (string.IsNullOrWhiteSpace(request.LoadoutProfile) || string.IsNullOrWhiteSpace(request.LoadoutName))
            return Problem(statusCode: StatusCodes.Status400BadRequest, title: "Validation failed", detail: "Loadout profile and name are required.");

        var created = await loadoutRepository.CreateAsync(request);
        return CreatedAtAction(nameof(GetById), new { loadoutId = created.EquipmentLoadoutId }, created);
    }

    [HttpPut("{loadoutId:guid}")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<IActionResult> Update(Guid loadoutId, [FromBody] EquipmentLoadoutUpdateRequest request)
    {
        if (request.LoadoutProfile is not null && string.IsNullOrWhiteSpace(request.LoadoutProfile))
            return Problem(statusCode: StatusCodes.Status400BadRequest, title: "Validation failed", detail: "Loadout profile is invalid.");

        if (request.LoadoutName is not null && string.IsNullOrWhiteSpace(request.LoadoutName))
            return Problem(statusCode: StatusCodes.Status400BadRequest, title: "Validation failed", detail: "Loadout name is invalid.");

        var updated = await loadoutRepository.UpdateAsync(loadoutId, request);
        if (updated is null)
            return NotFound();

        return Ok(updated);
    }

    [HttpDelete("{loadoutId:guid}")]
    [ProducesResponseType(StatusCodes.Status204NoContent)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<IActionResult> Delete(Guid loadoutId, [FromQuery(Name = "updated_by")] Guid updatedBy)
    {
        var deleted = await loadoutRepository.DeleteAsync(loadoutId, updatedBy);
        if (!deleted)
            return NotFound();

        return NoContent();
    }

    [HttpPost("{loadoutId:guid}/activate")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<IActionResult> Activate(Guid loadoutId, [FromBody] EquipmentLoadoutActivateRequest request)
    {
        var activated = await loadoutRepository.ActivateAsync(loadoutId, request.UpdatedBy);
        if (activated is null)
            return NotFound();

        return Ok(activated);
    }

    [HttpGet("{loadoutId:guid}/slots")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<IActionResult> GetSlots(Guid loadoutId)
    {
        var slots = await loadoutRepository.GetSlotsAsync(loadoutId);
        if (slots is null)
            return NotFound();

        return Ok(slots);
    }

    [HttpPut("{loadoutId:guid}/slots")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<IActionResult> UpsertSlot(Guid loadoutId, [FromBody] EquipmentLoadoutSlotUpsertRequest request)
    {
        if (string.IsNullOrWhiteSpace(request.SlotType) || request.SlotIndex < 0)
            return Problem(statusCode: StatusCodes.Status400BadRequest, title: "Validation failed", detail: "Slot payload is invalid.");

        var slot = await loadoutRepository.UpsertSlotAsync(loadoutId, request);
        if (slot is null)
            return NotFound();

        return Ok(slot);
    }

    [HttpDelete("{loadoutId:guid}/slots/{slotType}/{slotIndex:int}")]
    [ProducesResponseType(StatusCodes.Status204NoContent)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<IActionResult> DeleteSlot(
        Guid loadoutId,
        string slotType,
        int slotIndex,
        [FromQuery(Name = "updated_by")] Guid updatedBy)
    {
        var deleted = await loadoutRepository.DeleteSlotAsync(loadoutId, slotType, slotIndex, updatedBy);
        if (deleted is null or false)
            return NotFound();

        return NoContent();
    }
}
