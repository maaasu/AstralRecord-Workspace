using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.AspNetCore.Mvc;

namespace AstralRecordApi.Controllers;

/// <summary>ログインボーナス受取履歴API</summary>
[ApiController]
[Route("api/login-bonus")]
public class LoginBonusController(ILoginBonusClaimRepository loginBonusClaimRepository) : ControllerBase
{
    /// <summary>アカウント単位のログインボーナス受取履歴を取得します。</summary>
    /// <param name="accountId">アカウントID</param>
    /// <param name="from">取得開始日。省略時は下限なし。</param>
    /// <param name="to">取得終了日。省略時は上限なし。</param>
    /// <response code="200">取得成功</response>
    /// <response code="404">アカウントが存在しない</response>
    [HttpGet("claims")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<IActionResult> GetClaims(
        [FromQuery(Name = "account_id")] Guid accountId,
        [FromQuery] DateOnly? from,
        [FromQuery] DateOnly? to)
    {
        try
        {
            return Ok(await loginBonusClaimRepository.GetByAccountIdAsync(accountId, from, to));
        }
        catch (KeyNotFoundException)
        {
            return NotFound();
        }
    }

    /// <summary>ログインボーナス受取済み日を登録します。</summary>
    /// <param name="accountId">アカウントID</param>
    /// <param name="request">受取日と更新者</param>
    /// <response code="200">登録成功、または登録済み</response>
    /// <response code="400">リクエストが不正</response>
    /// <response code="404">アカウントが存在しない</response>
    [HttpPost("accounts/{accountId:guid}/claims")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<IActionResult> Claim(Guid accountId, [FromBody] LoginBonusClaimRequest request)
    {
        try
        {
            return Ok(await loginBonusClaimRepository.ClaimAsync(accountId, request));
        }
        catch (ArgumentException ex)
        {
            return BadRequest(ex.Message);
        }
        catch (KeyNotFoundException)
        {
            return NotFound();
        }
    }

    /// <summary>報酬付与に失敗したログインボーナス受取登録を取り消します。</summary>
    /// <param name="accountId">アカウントID</param>
    /// <param name="claimDate">取消対象日</param>
    /// <response code="204">取消成功、または既に取消済み</response>
    /// <response code="400">リクエストが不正</response>
    /// <response code="404">アカウントが存在しない</response>
    [HttpDelete("accounts/{accountId:guid}/claims/{claimDate}")]
    [ProducesResponseType(StatusCodes.Status204NoContent)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<IActionResult> Cancel(Guid accountId, DateOnly claimDate)
    {
        try
        {
            await loginBonusClaimRepository.CancelAsync(accountId, claimDate, accountId);
            return NoContent();
        }
        catch (ArgumentException ex)
        {
            return BadRequest(ex.Message);
        }
        catch (KeyNotFoundException)
        {
            return NotFound();
        }
    }
}
