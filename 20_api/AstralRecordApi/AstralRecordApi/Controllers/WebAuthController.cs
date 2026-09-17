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

    /// <summary>固定ログインIDとパスワードでWebセッション情報を取得します。</summary>
    /// <response code="200">認証に成功した。</response>
    /// <response code="400">IDまたはパスワードが不正、あるいは利用停止中。</response>
    /// <response code="429">同じIDの試行回数上限に達した。</response>
    [HttpPost("password/login")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    [ProducesResponseType(StatusCodes.Status429TooManyRequests)]
    public async Task<IActionResult> LoginWithPassword([FromBody] WebPasswordLoginRequest request)
    {
        var result = await webAuthRepository.LoginWithPasswordAsync(request);
        return result.Status switch
        {
            WebPasswordLoginStatus.Succeeded => Ok(result.Response),
            WebPasswordLoginStatus.Throttled => StatusCode(StatusCodes.Status429TooManyRequests, new { message = "login is temporarily unavailable." }),
            _ => BadRequest(new { message = "login is invalid." }),
        };
    }

    /// <summary>Web固定ログインID・有効状態・セッション版を取得します。</summary>
    [HttpGet("users/{userUuid:guid}/credentials")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<IActionResult> GetCredential(Guid userUuid)
    {
        var credential = await webAuthRepository.GetCredentialAsync(userUuid);
        return credential is null ? NotFound(new { message = "credentials are not available." }) : Ok(credential);
    }

    /// <summary>Web固定ログインID・パスワードを有効化、変更、または無効化します。</summary>
    /// <remarks>呼出元Webは保護Cookieから sessionVersion と codeAuthenticationProof を導出し、APIが証明を検証します。</remarks>
    [HttpPost("users/{userUuid:guid}/credentials")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    [ProducesResponseType(StatusCodes.Status401Unauthorized)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<IActionResult> UpdateCredential(Guid userUuid, [FromBody] WebCredentialUpdateRequest request)
    {
        var result = await webAuthRepository.UpdateCredentialAsync(userUuid, request);
        return result.Status switch
        {
            WebCredentialUpdateStatus.Succeeded => Ok(result.Response),
            WebCredentialUpdateStatus.Stale => Unauthorized(new { message = "session is stale." }),
            WebCredentialUpdateStatus.NotFound => NotFound(new { message = "credentials are not available." }),
            WebCredentialUpdateStatus.Throttled => StatusCode(StatusCodes.Status429TooManyRequests, new { message = "credentials are temporarily unavailable." }),
            _ => BadRequest(new { message = "credential update is invalid." }),
        };
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
