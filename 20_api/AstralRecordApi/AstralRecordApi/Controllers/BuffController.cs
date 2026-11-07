using AstralRecordApi.Repositories;
using Microsoft.AspNetCore.Mvc;

namespace AstralRecordApi.Controllers;

/// <summary>バフ API</summary>
[ApiController]
[Route("api/[controller]")]
public class BuffController(IBuffRepository buffRepository) : ControllerBase
{
    /// <summary>Java 版 HUD 用テクスチャを含むバフ一覧の主要項目を取得する。</summary>
    /// <response code="200">バフ一覧取得成功</response>
    [HttpGet]
    [ProducesResponseType(StatusCodes.Status200OK)]
    public IActionResult GetAll()
    {
        var buffs = buffRepository.GetAllSummaries();
        return Ok(buffs);
    }

    /// <summary>Java 版 HUD 用テクスチャを含むバフ定義を取得する。</summary>
    /// <param name="buffId">バフ ID</param>
    /// <response code="200">バフ取得成功</response>
    /// <response code="404">指定 buff ID が存在しない</response>
    [HttpGet("{buffId}")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public IActionResult GetById(string buffId)
    {
        var buff = buffRepository.GetById(buffId);
        if (buff is null)
            return NotFound();

        return Ok(buff);
    }
}