using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.AspNetCore.Mvc;

namespace AstralRecordApi.Controllers;

[ApiController]
[Route("api/account-guide/{accountId:guid}/completion-reward")]
public sealed class AccountGuideCompletionRewardController(IAccountGuideCompletionRewardRepository repository) : ControllerBase
{
    [HttpGet]
    [ProducesResponseType(typeof(AccountGuideCompletionRewardResponse), StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<IActionResult> Get(Guid accountId)
    {
        try { return Ok(await repository.GetAsync(accountId)); }
        catch (KeyNotFoundException) { return NotFound(); }
    }

    [HttpPost("claim")]
    [ProducesResponseType(typeof(AccountGuideCompletionRewardResponse), StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status409Conflict)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<IActionResult> Claim(Guid accountId, [FromBody] AccountGuideCompletionRewardClaimRequest request)
    {
        try { return Ok(await repository.ClaimAsync(accountId, request.UpdatedBy)); }
        catch (AccountGuideCompletionRewardUnavailableException exception) { return Conflict(exception.Message); }
        catch (KeyNotFoundException) { return NotFound(); }
    }
}
