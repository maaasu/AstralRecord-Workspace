using AstralRecordApi.Repositories;
using Microsoft.AspNetCore.Mvc;

namespace AstralRecordApi.Controllers;

/// <summary>Mob API</summary>
[ApiController]
[Route("api/[controller]")]
public class MobController(IMobRepository mobRepository, ILogger<MobController> logger) : ControllerBase
{
    /// <summary>Mob 一覧取得（主要項目のみ）</summary>
    /// <param name="category">フィルタ。<c>BOSS</c> / <c>ENEMY</c> / <c>NPC</c>。未指定なら全カテゴリ</param>
    /// <response code="200">Mob 一覧取得成功</response>
    /// <response code="400">不正な category 値</response>
    [HttpGet]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    public IActionResult GetAll([FromQuery] string? category = null)
    {
        try
        {
            var mobs = mobRepository.GetAllSummaries(category);
            return Ok(mobs);
        }
        catch (ArgumentException ex)
        {
            logger.LogWarning("Invalid category query: {Category}", category);
            return Problem(
                title: "Invalid category",
                detail: ex.Message,
                statusCode: StatusCodes.Status400BadRequest);
        }
        catch (Exception ex)
        {
            logger.LogError(ex, "Failed to get mob list");
            throw;
        }
    }

    /// <summary>Mob 取得</summary>
    /// <param name="mobId">Mob テンプレート ID</param>
    /// <response code="200">Mob 取得成功</response>
    /// <response code="404">指定 mob ID が存在しない</response>
    [HttpGet("{mobId}")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public IActionResult GetById(string mobId)
    {
        try
        {
            var mob = mobRepository.GetById(mobId);
            if (mob is null)
                return NotFound();

            return Ok(mob);
        }
        catch (Exception ex)
        {
            logger.LogError(ex, "Failed to get mob {MobId}", mobId);
            throw;
        }
    }
}
