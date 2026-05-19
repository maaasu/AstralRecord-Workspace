using AstralRecordApi.Repositories;
using Microsoft.AspNetCore.Mvc;

namespace AstralRecordApi.Controllers;

/// <summary>セット効果 API</summary>
[ApiController]
[Route("api/seteffect")]
public class SetEffectController(ISetEffectRepository setEffectRepository) : ControllerBase
{
    /// <summary>セット効果一覧取得</summary>
    /// <response code="200">セット効果一覧取得成功</response>
    [HttpGet]
    [ProducesResponseType(StatusCodes.Status200OK)]
    public IActionResult GetAll()
    {
        var setEffects = setEffectRepository.GetAll();
        return Ok(setEffects);
    }

    /// <summary>セット効果取得</summary>
    /// <param name="setId">セット効果 ID</param>
    /// <response code="200">セット効果取得成功</response>
    /// <response code="404">指定 ID のセット効果が存在しない</response>
    [HttpGet("{setId}")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public IActionResult GetById(string setId)
    {
        var setEffect = setEffectRepository.GetById(setId);
        if (setEffect is null)
            return NotFound();

        return Ok(setEffect);
    }
}
