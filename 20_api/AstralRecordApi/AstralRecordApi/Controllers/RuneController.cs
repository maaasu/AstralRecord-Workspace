using AstralRecordApi.Models;
using AstralRecordApi.Services;
using Microsoft.AspNetCore.Mvc;

namespace AstralRecordApi.Controllers;

/// <summary>ルーンインスタンス API</summary>
[ApiController]
[Route("api/rune")]
public class RuneController(IRuneService runeService) : ControllerBase
{
    /// <summary>ルーンインスタンス作成</summary>
    /// <response code="404">指定した runeId または accountId が存在しない</response>
    [HttpPost("instances")]
    [ProducesResponseType(StatusCodes.Status201Created)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<IActionResult> Create([FromBody] RuneCreateRequest request)
    {
        var created = await runeService.CreateAsync(request);
        if (created is null)
            return NotFound();

        return CreatedAtAction(nameof(GetByInstanceId), new { instanceId = created.RuneInstanceId }, created);
    }

    /// <summary>ルーンインスタンス取得</summary>
    [HttpGet("instances/{instanceId:guid}")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<IActionResult> GetByInstanceId(Guid instanceId)
    {
        var instance = await runeService.GetByInstanceIdAsync(instanceId);
        if (instance is null)
            return NotFound();

        return Ok(instance);
    }
}
