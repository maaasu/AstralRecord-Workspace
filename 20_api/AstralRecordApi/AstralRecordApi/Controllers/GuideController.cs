using AstralRecordApi.Repositories;
using Microsoft.AspNetCore.Mvc;

namespace AstralRecordApi.Controllers;

/// <summary>ゲーム内ガイド API</summary>
[ApiController]
[Route("api/[controller]")]
public class GuideController(IGuideRepository guideRepository) : ControllerBase
{
    /// <summary>ガイドマスター一覧を取得します。</summary>
    /// <response code="200">ガイド一覧取得成功</response>
    [HttpGet]
    [ProducesResponseType(StatusCodes.Status200OK)]
    public IActionResult GetAll()
    {
        return Ok(guideRepository.GetAll());
    }

    /// <summary>ガイドマスター詳細を取得します。</summary>
    /// <param name="guideId">ガイド ID</param>
    /// <response code="200">ガイド取得成功</response>
    /// <response code="404">指定したガイド ID が存在しない</response>
    [HttpGet("{guideId}")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public IActionResult GetById(string guideId)
    {
        var guide = guideRepository.GetById(guideId);
        return guide is null ? NotFound() : Ok(guide);
    }
}
