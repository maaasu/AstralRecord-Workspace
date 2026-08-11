using AstralRecordApi.Repositories;
using Microsoft.AspNetCore.Mvc;

namespace AstralRecordApi.Controllers;

/// <summary>共通エンチャントマスタ API</summary>
[ApiController]
[Route("api/enchant")]
public class EnchantController(IEnchantRepository enchantRepository) : ControllerBase
{
    /// <summary>指定IDの共通エンチャントマスタを取得する。</summary>
    /// <param name="enchantMasterId">共通エンチャントマスタID</param>
    /// <response code="200">取得成功</response>
    /// <response code="404">対象マスタが存在しない</response>
    [HttpGet("{enchantMasterId}")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public IActionResult GetById(string enchantMasterId)
    {
        var enchant = enchantRepository.GetById(enchantMasterId);
        return enchant is null ? NotFound() : Ok(enchant);
    }
}
