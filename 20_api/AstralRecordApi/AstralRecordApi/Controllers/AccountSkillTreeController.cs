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
}
