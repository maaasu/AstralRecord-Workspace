using AstralRecordApi.Repositories;
using Microsoft.AspNetCore.Mvc;

namespace AstralRecordApi.Controllers;

/// <summary>ルート API</summary>
[ApiController]
[Route("api/[controller]")]
public class LootController(ILootRepository lootRepository) : ControllerBase
{
    /// <summary>ルートプール一覧取得</summary>
    /// <response code="200">ルートプール一覧取得成功</response>
    [HttpGet("pool")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    public IActionResult GetAllPools()
    {
        var pools = lootRepository.GetAllPools();
        return Ok(pools);
    }

    /// <summary>ルートプール取得</summary>
    /// <param name="poolId">プール ID</param>
    /// <response code="200">ルートプール取得成功</response>
    /// <response code="404">指定プール ID が存在しない</response>
    [HttpGet("pool/{poolId}")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public IActionResult GetPoolById(string poolId)
    {
        var pool = lootRepository.GetPoolById(poolId);
        if (pool is null)
            return NotFound();

        return Ok(pool);
    }

    /// <summary>ルートテーブル一覧取得</summary>
    /// <response code="200">ルートテーブル一覧取得成功</response>
    [HttpGet("table")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    public IActionResult GetAllTables()
    {
        var tables = lootRepository.GetAllTables();
        return Ok(tables);
    }

    /// <summary>ルートテーブル取得</summary>
    /// <param name="tableId">テーブル ID</param>
    /// <response code="200">ルートテーブル取得成功</response>
    /// <response code="404">指定テーブル ID が存在しない</response>
    [HttpGet("table/{tableId}")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public IActionResult GetTableById(string tableId)
    {
        var table = lootRepository.GetTableById(tableId);
        if (table is null)
            return NotFound();

        return Ok(table);
    }
}
