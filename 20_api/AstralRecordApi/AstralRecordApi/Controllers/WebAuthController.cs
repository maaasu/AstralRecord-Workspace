using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.AspNetCore.Mvc;

namespace AstralRecordApi.Controllers;

/// <summary>Web login challenge API</summary>
[ApiController]
[Route("api/web-auth")]
public class WebAuthController(IWebAuthRepository webAuthRepository) : ControllerBase
{
    /// <summary>Issues a one-time web login challenge for a Minecraft player.</summary>
    /// <param name="request">Challenge issue request.</param>
    /// <response code="201">Challenge issued.</response>
    /// <response code="400">Request body is invalid.</response>
    /// <response code="404">Player user was not found.</response>
    [HttpPost("challenges")]
    [ProducesResponseType(StatusCodes.Status201Created)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<IActionResult> CreateChallenge([FromBody] WebLoginChallengeCreateRequest request)
    {
        if (request.UserUuid == Guid.Empty ||
            string.IsNullOrWhiteSpace(request.Mcid) ||
            string.IsNullOrWhiteSpace(request.ServerId) ||
            request.RequestedAt == default)
        {
            return BadRequest(new { message = "userUuid, mcid, serverId, and requestedAt are required." });
        }

        var created = await webAuthRepository.CreateChallengeAsync(request);
        if (created is null)
            return NotFound();

        return Created($"/api/web-auth/challenges/{created.ChallengeId}", created);
    }

    /// <summary>Consumes a one-time web login challenge.</summary>
    /// <param name="request">Challenge consume request.</param>
    /// <response code="200">Challenge consumed.</response>
    /// <response code="400">Code is invalid, expired, revoked, or already consumed.</response>
    [HttpPost("challenges/consume")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    public async Task<IActionResult> ConsumeChallenge([FromBody] WebLoginChallengeConsumeRequest request)
    {
        if (string.IsNullOrWhiteSpace(request.LoginCode))
            return BadRequest(new { message = "loginCode is required." });

        var consumed = await webAuthRepository.ConsumeChallengeAsync(request);
        if (consumed is null)
            return BadRequest(new { message = "loginCode is invalid or expired." });

        return Ok(consumed);
    }
}
