using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.AspNetCore.Mvc;

namespace AstralRecordApi.Controllers;

/// <summary>アカウント単位のウェイストーン開放状態API</summary>
[ApiController]
[Route("api/account-waystone")]
public class AccountWaystoneController(IAccountWaystoneRepository accountWaystoneRepository) : ControllerBase
{
    /// <summary>アカウントの開放済みウェイストーンID一覧を取得します。</summary>
    /// <param name="accountId">アカウントID</param>
    /// <response code="200">取得成功</response>
    /// <response code="404">アカウントが存在しない</response>
    [HttpGet("{accountId:guid}")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<IActionResult> GetByAccountId(Guid accountId)
    {
        try
        {
            return Ok(await accountWaystoneRepository.GetByAccountIdAsync(accountId));
        }
        catch (KeyNotFoundException)
        {
            return NotFound();
        }
    }

    /// <summary>指定ウェイストーンを開放済みとして登録します。</summary>
    /// <param name="accountId">アカウントID</param>
    /// <param name="request">開放するウェイストーンIDと更新者</param>
    /// <response code="200">既に開放済み、または開放成功</response>
    /// <response code="404">アカウントが存在しない</response>
    [HttpPost("{accountId:guid}/unlock")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<IActionResult> Unlock(Guid accountId, [FromBody] AccountWaystoneUnlockRequest request)
    {
        try
        {
            return Ok(await accountWaystoneRepository.UnlockAsync(accountId, request));
        }
        catch (KeyNotFoundException)
        {
            return NotFound();
        }
    }
}
