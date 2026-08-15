using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.AspNetCore.Mvc;

namespace AstralRecordApi.Controllers;

[ApiController]
[Route("api/trade")]
public sealed class TradeController(ITradeRepository tradeRepository) : ControllerBase
{
    /// <summary>
    /// 両参加者の提示 item と Gold を単一トランザクションで交換します。
    /// </summary>
    [HttpPost("commit")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    [ProducesResponseType(StatusCodes.Status409Conflict)]
    public async Task<IActionResult> Commit([FromBody] TradeCommitRequest request)
    {
        var result = await tradeRepository.CommitAsync(request);
        return result.Succeeded
            ? Ok(result.Value)
            : Problem(statusCode: result.StatusCode, title: result.ErrorCode ?? "trade.error", detail: result.Detail);
    }
}
