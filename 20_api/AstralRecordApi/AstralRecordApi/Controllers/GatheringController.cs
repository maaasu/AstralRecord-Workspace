using AstralRecordApi.Repositories;
using Microsoft.AspNetCore.Mvc;

namespace AstralRecordApi.Controllers;

/// <summary>採集オブジェクト API</summary>
[ApiController]
[Route("api/gathering")]
public class GatheringController(IGatheringRepository gatheringRepository, ILogger<GatheringController> logger)
    : ControllerBase
{
    /// <summary>採集オブジェクト一覧を取得します。</summary>
    /// <param name="category">フィルタカテゴリ。<c>MINING</c> / <c>HARVESTING</c>。未指定なら全件。</param>
    /// <response code="200">採集オブジェクト一覧</response>
    /// <response code="400">不正な category 値</response>
    [HttpGet]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    public IActionResult GetAll([FromQuery] string? category = null)
    {
        try
        {
            return Ok(gatheringRepository.GetAllSummaries(category));
        }
        catch (ArgumentException ex)
        {
            logger.LogWarning("Invalid gathering category query: {Category}", category);
            return Problem(
                title: "Invalid category",
                detail: ex.Message,
                statusCode: StatusCodes.Status400BadRequest);
        }
        catch (Exception ex)
        {
            logger.LogError(ex, "Failed to get gathering list");
            throw;
        }
    }

    /// <summary>採集オブジェクト詳細を取得します。</summary>
    /// <param name="gatheringId">採集オブジェクト ID</param>
    /// <response code="200">採集オブジェクト詳細</response>
    /// <response code="404">指定 ID が存在しない</response>
    [HttpGet("{gatheringId}")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public IActionResult GetById(string gatheringId)
    {
        try
        {
            var gathering = gatheringRepository.GetById(gatheringId);
            if (gathering is null)
                return NotFound();

            return Ok(gathering);
        }
        catch (Exception ex)
        {
            logger.LogError(ex, "Failed to get gathering {GatheringId}", gatheringId);
            throw;
        }
    }
}
