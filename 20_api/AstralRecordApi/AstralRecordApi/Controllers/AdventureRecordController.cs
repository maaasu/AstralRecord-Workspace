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
}
