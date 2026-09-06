using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.AspNetCore.Mvc;

namespace AstralRecordApi.Controllers;

[ApiController]
[Route("api/player-state")]
public sealed class PlayerStateController(IPlayerStateSnapshotRepository repository) : ControllerBase
{
    /// <summary>Plugin のローカル確定 player state を、snapshotId により冪等な単一 transaction で保存します。</summary>
    [HttpPost("snapshots")]
    [ProducesResponseType(typeof(PlayerStateSnapshotAck), StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    [ProducesResponseType(StatusCodes.Status409Conflict)]
    public async Task<IActionResult> SaveSnapshot([FromBody] PlayerStateSnapshotSaveRequest request)
    {
        var result = await repository.SaveAsync(request);
        if (result.Succeeded)
            return Ok(result.Ack);

        return result.Failure switch
        {
            PlayerStateSnapshotSaveFailure.AccountNotFound => NotFound(),
            PlayerStateSnapshotSaveFailure.Invalid => Problem(
                statusCode: StatusCodes.Status400BadRequest,
                title: "Player state snapshot validation failed",
                detail: result.Detail),
            _ => Conflict(new ProblemDetails
            {
                Status = StatusCodes.Status409Conflict,
                Title = "Player state snapshot conflict",
                Detail = result.Detail,
            }),
        };
    }
}
