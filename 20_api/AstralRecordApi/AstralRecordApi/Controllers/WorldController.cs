using AstralRecordApi.Repositories;
using Microsoft.AspNetCore.Mvc;

namespace AstralRecordApi.Controllers;

/// <summary>World API</summary>
[ApiController]
[Route("api/[controller]")]
public class WorldController(IWorldRepository worldRepository, ILogger<WorldController> logger) : ControllerBase
{
    /// <summary>World マスタ一覧を取得する</summary>
    /// <response code="200">World マスタ一覧取得成功</response>
    [HttpGet]
    [ProducesResponseType(StatusCodes.Status200OK)]
    public IActionResult GetAll()
    {
        try
        {
            return Ok(worldRepository.GetAllSummaries());
        }
        catch (Exception ex)
        {
            logger.LogError(ex, "Failed to get world list");
            throw;
        }
    }

    /// <summary>World マスタ詳細を取得する</summary>
    /// <param name="worldId">World マスタ ID</param>
    /// <response code="200">World マスタ取得成功</response>
    /// <response code="404">指定 world ID が存在しない</response>
    [HttpGet("{worldId}")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public IActionResult GetById(string worldId)
    {
        try
        {
            var world = worldRepository.GetById(worldId);
            if (world is null)
                return NotFound();

            return Ok(world);
        }
        catch (Exception ex)
        {
            logger.LogError(ex, "Failed to get world {WorldId}", worldId);
            throw;
        }
    }
}
