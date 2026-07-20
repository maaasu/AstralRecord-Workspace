using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.AspNetCore.Mvc;

namespace AstralRecordApi.Controllers;

/// <summary>アカウント単位のガイド進行 API</summary>
[ApiController]
[Route("api/account-guide")]
public class AccountGuideController(IAccountGuideProgressRepository repository) : ControllerBase
{
    /// <summary>アカウントの完了済みガイド手順を取得します。</summary>
    [HttpGet("{accountId:guid}")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<IActionResult> GetByAccountId(Guid accountId)
    {
        try
        {
            return Ok(await repository.GetByAccountIdAsync(accountId));
        }
        catch (KeyNotFoundException)
        {
            return NotFound();
        }
    }

    /// <summary>指定したガイド手順を完了済みとして冪等登録します。</summary>
    [HttpPost("{accountId:guid}/steps/complete")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<IActionResult> CompleteStep(
        Guid accountId,
        [FromBody] AccountGuideStepCompleteRequest request)
    {
        try
        {
            return Ok(await repository.CompleteStepAsync(accountId, request));
        }
        catch (ArgumentException exception)
        {
            return BadRequest(exception.Message);
        }
        catch (KeyNotFoundException)
        {
            return NotFound();
        }
    }
}
