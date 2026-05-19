using AstralRecordApi.Repositories;
using Microsoft.AspNetCore.Mvc;

namespace AstralRecordApi.Controllers;

/// <summary>アイテム API</summary>
[ApiController]
[Route("api/[controller]")]
public class ItemController(IItemRepository itemRepository) : ControllerBase
{
    /// <summary>アイテム一覧取得（最小項目）</summary>
    /// <response code="200">アイテム一覧取得成功</response>
    [HttpGet]
    [ProducesResponseType(StatusCodes.Status200OK)]
    public IActionResult GetAll()
    {
        var items = itemRepository.GetAllSummaries();
        return Ok(items);
    }

    /// <summary>アイテム取得</summary>
    /// <param name="itemId">アイテム ID</param>
    /// <response code="200">アイテム取得成功</response>
    /// <response code="404">対象アイテムが存在しない</response>
    [HttpGet("{itemId}")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public IActionResult GetById(string itemId)
    {
        var item = itemRepository.GetById(itemId);
        if (item is null)
            return NotFound();

        return Ok(item);
    }
}