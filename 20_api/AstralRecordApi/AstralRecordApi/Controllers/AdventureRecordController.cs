using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.AspNetCore.Mvc;

namespace AstralRecordApi.Controllers;

/// <summary>冒険記録 API</summary>
[ApiController]
[Route("api/adventure-record")]
public class AdventureRecordController(IAdventureRecordRepository adventureRecordRepository) : ControllerBase
{
    /// <summary>アカウント単位の Mob 討伐記録を取得</summary>
    /// <param name="accountId">アカウント ID（query: account_id）</param>
    /// <param name="category">Mob カテゴリ（ENEMY / BOSS、省略時は全件）</param>
    /// <response code="200">Mob 討伐記録一覧取得成功</response>
    [HttpGet("mob")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    public async Task<IActionResult> GetMobRecords(
        [FromQuery(Name = "account_id")] Guid accountId,
        [FromQuery(Name = "category")] string? category)
    {
        var records = await adventureRecordRepository.GetMobRecordsByAccountIdAsync(accountId, category);
        return Ok(records);
    }

    /// <summary>Mob 討伐を記録</summary>
    /// <param name="request">討伐記録内容</param>
    /// <response code="200">Mob 討伐記録の登録または更新成功</response>
    [HttpPost("mob/defeat")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    public async Task<IActionResult> RecordMobDefeat([FromBody] AccountMobDefeatRequest request)
    {
        var record = await adventureRecordRepository.RecordMobDefeatAsync(request);
        return Ok(record);
    }

    /// <summary>アカウント単位のダンジョン踏破記録を取得</summary>
    /// <param name="accountId">アカウント ID（query: account_id）</param>
    /// <response code="200">ダンジョン踏破記録一覧取得成功</response>
    /// <response code="400">アカウント ID が空</response>
    [HttpGet("dungeon")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    public async Task<IActionResult> GetDungeonRecords(
        [FromQuery(Name = "account_id")] Guid accountId)
    {
        if (accountId == Guid.Empty)
            return BadRequest();
        var records = await adventureRecordRepository.GetDungeonRecordsByAccountIdAsync(accountId);
        return Ok(records);
    }

    /// <summary>ダンジョン踏破を登録または加算</summary>
    /// <param name="request">踏破記録内容</param>
    /// <response code="200">踏破記録の登録または更新成功</response>
    /// <response code="400">入力値が不正</response>
    [HttpPost("dungeon/clear")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    public async Task<IActionResult> RecordDungeonClear([FromBody] AccountDungeonClearRequest request)
    {
        if (request.AccountId == Guid.Empty
            || request.UpdatedBy == Guid.Empty
            || string.IsNullOrWhiteSpace(request.DungeonId)
            || request.DungeonId.Trim().Length > 100)
            return BadRequest();
        var record = await adventureRecordRepository.RecordDungeonClearAsync(request);
        return Ok(record);
    }
}
