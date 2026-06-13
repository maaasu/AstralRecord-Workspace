using AstralRecordApi.Repositories;
using Microsoft.AspNetCore.Mvc;

namespace AstralRecordApi.Controllers;

/// <summary>採集スポナー API</summary>
[ApiController]
[Route("api/gathering-spawner")]
public class GatheringSpawnerController(
    IGatheringSpawnerRepository gatheringSpawnerRepository,
    ILogger<GatheringSpawnerController> logger) : ControllerBase
{
    /// <summary>採集スポナー一覧を取得します。</summary>
    /// <response code="200">採集スポナー一覧</response>
    [HttpGet]
    [ProducesResponseType(StatusCodes.Status200OK)]
    public IActionResult GetAll()
    {
        try
        {
            return Ok(gatheringSpawnerRepository.GetAllSummaries());
        }
        catch (Exception ex)
        {
            logger.LogError(ex, "Failed to get gathering spawner list");
            throw;
        }
    }

    /// <summary>採集スポナー詳細を取得します。</summary>
    /// <param name="spawnerId">採集スポナー ID</param>
    /// <response code="200">採集スポナー詳細</response>
    /// <response code="404">指定 ID が存在しない</response>
    [HttpGet("{spawnerId}")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public IActionResult GetById(string spawnerId)
    {
        try
        {
            var spawner = gatheringSpawnerRepository.GetById(spawnerId);
            if (spawner is null)
                return NotFound();

            return Ok(spawner);
        }
        catch (Exception ex)
        {
            logger.LogError(ex, "Failed to get gathering spawner {SpawnerId}", spawnerId);
            throw;
        }
    }
}
