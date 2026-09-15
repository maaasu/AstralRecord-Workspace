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

    /// <summary>MCID から一意に解決できるプレイヤーを取得します。</summary>
    /// <param name="mcid">完全一致で検索する Minecraft ID。</param>
    /// <response code="200">プレイヤーを一意に解決できた。</response>
    /// <response code="404">登録済みプレイヤーが存在しない。</response>
    /// <response code="409">同じ MCID の登録が複数あり、一意に解決できない。</response>
    [HttpGet("users/by-mcid/{mcid}")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    [ProducesResponseType(StatusCodes.Status409Conflict)]
    public async Task<IActionResult> ResolveUserByMcid(string mcid)
    {
        var resolved = await webAuthRepository.ResolveUserByMcidAsync(mcid);
        return resolved.Status switch
        {
            WebLoginChallengeUserResolveStatus.Found => Ok(resolved.Response),
            WebLoginChallengeUserResolveStatus.Ambiguous => Conflict(new { message = "mcid is ambiguous." }),
            _ => NotFound(),
        };
    }

    /// <summary>Web 管理機能を利用できるかを取得します。</summary>
    /// <param name="userUuid">確認するプレイヤー UUID。</param>
    /// <response code="200">Web 管理フラグを返した。</response>
    [HttpGet("users/{userUuid:guid}/authorization")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    public async Task<IActionResult> GetAuthorization(Guid userUuid)
    {
        var webAdmin = await webAuthRepository.IsWebAdminAsync(userUuid);
        return Ok(new WebAuthorizationResponse { WebAdmin = webAdmin });
    }
}
