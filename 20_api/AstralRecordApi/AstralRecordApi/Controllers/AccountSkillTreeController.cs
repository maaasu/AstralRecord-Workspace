using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.AspNetCore.Mvc;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordApi.Controllers;

[ApiController]
[Route("api/account-skilltree")]
public class AccountSkillTreeController(IAccountSkillTreeStateRepository accountSkillTreeStateRepository, ISkillTreeOperationRepository? runtime = null) : ControllerBase
{
    [HttpGet("{accountId:guid}")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<IActionResult> GetByAccountId(Guid accountId)
    {
        try
        {
            var state = await accountSkillTreeStateRepository.GetByAccountIdAsync(accountId);
            if (state.DefinitionGenerationId is not null || runtime is not null && await runtime.RequiresRuntimeAuthorityAsync(accountId))
            {
                var headers = HttpContext?.Request.Headers;
                var generation = headers?["X-SkillTree-Generation"].ToString();
                var serverId = headers?["X-SkillTree-Server"].ToString();
                var token = headers?["X-SkillTree-Account-Token"].ToString();
                if (runtime is null || generation is null || state.DefinitionGenerationId is not null && generation != state.DefinitionGenerationId || serverId is null || token is null
                    || !Guid.TryParse(headers?["X-SkillTree-Boot"].ToString(), out var boot)
                    || !Guid.TryParse(headers?["X-SkillTree-Account-Session"].ToString(), out var session)
                    || !await runtime.ValidateRuntimeStateSaveAsync(accountId, serverId, boot, generation, session, token))
                    return Conflict(new { reason = "DEFINITION_MISMATCH" });
            }
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
        catch (InvalidOperationException)
        {
            return Conflict(new { message = "世代付きスキルツリー状態はPlugin確定処理からのみ変更できます。" });
        }
    }

    /// <summary>
    /// 現行のスキルツリー構造と整合しない選択状態を全解除します。
    /// </summary>
    /// <param name="accountId">補修対象アカウント UUID</param>
    /// <param name="request">アカウント所有者、期待版数・定義世代、現行セッション、更新者</param>
    /// <response code="200">補修済み状態</response>
    /// <response code="400">所有者・更新者 UUID が空、期待版数または定義世代が不正</response>
    /// <response code="404">対象アカウントが存在しない</response>
    /// <response code="409">期待版数・定義世代またはセッションが現在の状態と一致しない</response>
    [HttpPost("{accountId:guid}/repair-invalid-state")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    [ProducesResponseType(StatusCodes.Status409Conflict)]
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
        catch (DbUpdateConcurrencyException)
        {
            return Conflict();
        }
        catch (ArgumentException)
        {
            return BadRequest();
        }
    }
}
