using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.AspNetCore.Mvc;

namespace AstralRecordApi.Controllers;

[ApiController]
[Route("api/account-skills/{accountId:guid}")]
public class AccountLearnedSkillController(IAccountLearnedSkillRepository repository) : ControllerBase
{
    [HttpGet]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<IActionResult> Get(Guid accountId)
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

    [HttpPost("learn")]
    public async Task<IActionResult> Learn(Guid accountId, [FromBody] AccountLearnedSkillLearnRequest request)
        => ToActionResult(await repository.LearnAsync(accountId, request));

    [HttpPost("{learnedSkillId:guid}/level-up")]
    public async Task<IActionResult> LevelUp(
        Guid accountId,
        Guid learnedSkillId,
        [FromBody] AccountLearnedSkillLevelUpRequest request)
        => ToActionResult(await repository.LevelUpAsync(accountId, learnedSkillId, request));

    [HttpPost("{learnedSkillId:guid}/sigils")]
    public async Task<IActionResult> AttachSigil(
        Guid accountId,
        Guid learnedSkillId,
        [FromBody] AccountLearnedSkillAttachSigilRequest request)
        => ToActionResult(await repository.AttachSigilAsync(accountId, learnedSkillId, request));

    private IActionResult ToActionResult(AccountLearnedSkillMutationResult result)
    {
        if (result.Succeeded)
            return Ok(result.Skill);

        return result.Failure switch
        {
            AccountLearnedSkillMutationFailure.AccountNotFound
                or AccountLearnedSkillMutationFailure.LearnedSkillNotFound
                or AccountLearnedSkillMutationFailure.SkillNotFound
                or AccountLearnedSkillMutationFailure.SigilNotFound => NotFound(new { failure = result.Failure.ToString() }),
            _ => Conflict(new { failure = result.Failure.ToString() }),
        };
    }
}
