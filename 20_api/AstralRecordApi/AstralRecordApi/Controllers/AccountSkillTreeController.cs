using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.AspNetCore.Mvc;

namespace AstralRecordApi.Controllers;

[ApiController]
[Route("api/account-skilltree")]
public class AccountSkillTreeController(IAccountSkillTreeStateRepository accountSkillTreeStateRepository) : ControllerBase
{
    [HttpGet("{accountId:guid}")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<IActionResult> GetByAccountId(Guid accountId)
    {
        try
        {
            var state = await accountSkillTreeStateRepository.GetByAccountIdAsync(accountId);
            return Ok(state);
        }
        catch (KeyNotFoundException)
        {
            return NotFound();
        }
    }

    [HttpPut("{accountId:guid}")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<IActionResult> Upsert(Guid accountId, [FromBody] AccountSkillTreeStateUpsertRequest request)
    {
        try
        {
            var state = await accountSkillTreeStateRepository.UpsertAsync(accountId, request);
            return Ok(state);
        }
        catch (KeyNotFoundException)
        {
            return NotFound();
        }
    }

    /// <summary>
    /// 現行のスキルツリー構造と整合しない選択状態を全解除し、対象ユーザーへ補償メールを配信します。
    /// </summary>
    /// <param name="accountId">補修対象アカウント UUID</param>
    /// <param name="request">対象ユーザー、構造識別キー、更新者</param>
    /// <response code="200">補修済み状態</response>
    /// <response code="404">対象アカウントまたは補償メールマスタが存在しない</response>
    [HttpPost("{accountId:guid}/repair-invalid-state")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<IActionResult> RepairInvalidState(
        Guid accountId,
        [FromBody] AccountSkillTreeInvalidStateRepairRequest request)
    {
        try
        {
            return Ok(await accountSkillTreeStateRepository.RepairInvalidStateAsync(accountId, request));
        }
        catch (KeyNotFoundException)
        {
            return NotFound();
        }
    }
}
