using System.Security.Cryptography;
using System.Text;
using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.AspNetCore.Mvc;

namespace AstralRecordApi.Controllers;

/// <summary>Dedicated development and channel world-placement snapshots.</summary>
[ApiController]
[Route("api/world-placements")]
public sealed class WorldPlacementsController(IWorldPlacementRepository repository, IConfiguration configuration) : ControllerBase
{
    /// <summary>Returns one environment's complete placement snapshot.</summary>
    [HttpGet("{environment}")]
    public async Task<IActionResult> Get(string environment, CancellationToken cancellationToken)
    {
        if (!ValidEnvironment(environment)) return NotFound();
        if (Standalone && environment == "channels") return NotFound();
        if (!ConnectionEnabled(environment)) return StatusCode(StatusCodes.Status503ServiceUnavailable);
        var snapshot = await repository.GetAsync(environment, cancellationToken);
        Response.Headers.ETag = $"\"{snapshot.Revision}\"";
        return Request.Headers.IfNoneMatch == $"\"{snapshot.Revision}\"" ? StatusCode(304) : Ok(snapshot);
    }

    /// <summary>Atomically replaces the development snapshot when its revision matches.</summary>
    [HttpPut("dev")]
    public async Task<IActionResult> Replace([FromBody] WorldPlacementReplaceRequest request, CancellationToken cancellationToken)
    {
        if (!WriteAuthorized()) return Unauthorized();
        if (!ConnectionEnabled("dev")) return StatusCode(StatusCodes.Status503ServiceUnavailable);
        if (request is null || !RevisionValid(request.ExpectedRevision)) return BadRequest();
        try { WorldPlacementValidation.Validate(request.Placements); }
        catch (ArgumentException ex) { return BadRequest(ex.Message); }
        var result = await repository.ReplaceDevAsync(request, cancellationToken);
        return result is null ? Conflict() : Ok(result);
    }

    /// <summary>Creates or updates one development placement with revision checking.</summary>
    [HttpPut("{environment}/{placementId}")]
    public async Task<IActionResult> Upsert(string environment, string placementId,
        [FromBody] WorldPlacementUpsertRequest request, CancellationToken cancellationToken)
    {
        if (environment != "dev") return NotFound();
        if (!WriteAuthorized()) return Unauthorized();
        if (!ConnectionEnabled("dev")) return StatusCode(StatusCodes.Status503ServiceUnavailable);
        if (request is null || !RevisionValid(request.ExpectedRevision) || request.Placement is null
            || placementId != request.Placement.PlacementId) return BadRequest();
        try { WorldPlacementValidation.Validate([request.Placement]); }
        catch (ArgumentException ex) { return BadRequest(ex.Message); }
        var result = await repository.UpsertDevAsync(request, cancellationToken);
        return result is null ? Conflict() : Ok(result);
    }

    /// <summary>Deletes one development placement with revision checking.</summary>
    [HttpDelete("{environment}/{placementId}")]
    public async Task<IActionResult> Delete(string environment, string placementId,
        [FromQuery] string expectedRevision, CancellationToken cancellationToken)
    {
        if (environment != "dev") return NotFound();
        if (!WriteAuthorized()) return Unauthorized();
        if (!ConnectionEnabled("dev")) return StatusCode(StatusCodes.Status503ServiceUnavailable);
        if (!RevisionValid(expectedRevision) || string.IsNullOrWhiteSpace(placementId)) return BadRequest();
        var result = await repository.DeleteDevAsync(placementId, expectedRevision, cancellationToken);
        return result is null ? Conflict() : Ok(result);
    }

    /// <summary>Explicitly publishes the current development snapshot to channels.</summary>
    [HttpPost("sync")]
    public async Task<IActionResult> Sync([FromBody] WorldPlacementSyncRequest request, CancellationToken cancellationToken)
    {
        if (Standalone) return NotFound();
        if (!SyncAuthorized()) return Unauthorized();
        if (!ConnectionEnabled("dev") || !ConnectionEnabled("channels")) return StatusCode(StatusCodes.Status503ServiceUnavailable);
        if (request is null || !RevisionValid(request.ExpectedDevRevision) || !RevisionValid(request.ExpectedChannelRevision))
            return BadRequest();
        var result = await repository.SyncAsync(request, cancellationToken);
        return result is null ? Conflict() : Ok(result);
    }

    private static bool ValidEnvironment(string value) => value is "dev" or "channels";
    private bool Standalone => configuration.GetValue<bool>("WorldPlacements:Standalone");
    private bool ConnectionEnabled(string environment) => !string.IsNullOrWhiteSpace(
        configuration.GetConnectionString(environment == "dev" ? "WorldPlacementDev" : "WorldPlacement"));
    private static bool RevisionValid(string? value) => long.TryParse(value, out var revision) && revision >= 0;

    private bool WriteAuthorized() => KeyMatches("WriteKey");
    private bool SyncAuthorized() => KeyMatches("SyncKey");

    private bool KeyMatches(string keyName)
    {
        var expected = configuration[$"WorldPlacements:{keyName}"];
        var supplied = Request.Headers["X-World-Placement-Key"].ToString();
        if (string.IsNullOrWhiteSpace(expected) || string.IsNullOrWhiteSpace(supplied)) return false;
        var left = SHA256.HashData(Encoding.UTF8.GetBytes(expected));
        var right = SHA256.HashData(Encoding.UTF8.GetBytes(supplied));
        return CryptographicOperations.FixedTimeEquals(left, right);
    }
}
