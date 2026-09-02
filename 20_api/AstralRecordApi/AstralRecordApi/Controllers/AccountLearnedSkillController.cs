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

    /// <summary>スキルmasterの必要素材を原子的に消費し、更新後個体と実消費entry・数量を返します。</summary>
    [HttpPost("learn")]
    public async Task<IActionResult> Learn(Guid accountId, [FromBody] AccountLearnedSkillLearnRequest request)
        => ToMaterialActionResult(await repository.LearnAsync(accountId, request));

    /// <summary>スキルmasterの必要素材を原子的に消費し、レベル更新後個体と実消費entry・数量を返します。</summary>
    [HttpPost("{learnedSkillId:guid}/level-up")]
    public async Task<IActionResult> LevelUp(
        Guid accountId,
        Guid learnedSkillId,
        [FromBody] AccountLearnedSkillLevelUpRequest request)
        => ToMaterialActionResult(await repository.LevelUpAsync(accountId, learnedSkillId, request));

    /// <summary>指定した習得済みスキル個体へ対応するオーブとシジルを各1個消費して装着します。</summary>
    [HttpPost("{learnedSkillId:guid}/sigils")]
    public async Task<IActionResult> AttachSigil(
        Guid accountId,
        Guid learnedSkillId,
        [FromBody] AccountLearnedSkillAttachSigilRequest request)
        => ToActionResult(await repository.AttachSigilAsync(accountId, learnedSkillId, request));

    /// <summary>指定した装着済みシジルを対応するオーブ1個を消費して取り外し、アカウントの BAG へシジルを1個返却します。</summary>
    [HttpPost("{learnedSkillId:guid}/sigils/{learnedSkillSigilId:guid}/detach")]
    public async Task<IActionResult> DetachSigil(
        Guid accountId,
        Guid learnedSkillId,
        Guid learnedSkillSigilId,
        [FromBody] AccountLearnedSkillDetachSigilRequest request)
    {
        var result = await repository.DetachSigilAsync(
            accountId,
            learnedSkillId,
            learnedSkillSigilId,
            request);
        if (!result.Succeeded)
            return ToActionResult(result);

        return Ok(new AccountLearnedSkillDetachSigilResponse
        {
            Skill = result.Skill!,
            ReturnedInventoryEntryId = result.ReturnedInventoryEntryId!.Value,
        });
    }

    [HttpPost("{learnedSkillId:guid}/forget")]
    public async Task<IActionResult> Forget(
        Guid accountId,
        Guid learnedSkillId,
        [FromBody] AccountLearnedSkillForgetRequest request)
        => ToActionResult(await repository.ForgetAsync(accountId, learnedSkillId, request));

    private IActionResult ToActionResult(AccountLearnedSkillMutationResult result)
    {
        if (result.Succeeded)
            return Ok(result.Skill);

        return result.Failure switch
        {
            AccountLearnedSkillMutationFailure.AccountNotFound
                or AccountLearnedSkillMutationFailure.LearnedSkillNotFound
                or AccountLearnedSkillMutationFailure.SkillNotFound
                or AccountLearnedSkillMutationFailure.SigilNotFound
                or AccountLearnedSkillMutationFailure.SigilAttachmentNotFound => NotFound(new { failure = result.Failure.ToString() }),
            _ => Conflict(new { failure = result.Failure.ToString() }),
        };
    }

    private IActionResult ToMaterialActionResult(AccountLearnedSkillMutationResult result)
    {
        if (!result.Succeeded)
            return ToActionResult(result);

        return Ok(new AccountLearnedSkillMaterialMutationResponse
        {
            Skill = result.Skill!,
            ConsumedMaterials = result.ConsumedMaterials ?? [],
        });
    }
}
