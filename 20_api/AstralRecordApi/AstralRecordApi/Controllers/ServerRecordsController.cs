using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;

namespace AstralRecordApi.Controllers;

[ApiController]
[Route("api/server-records")]
[Authorize]
public sealed class ServerRecordsController(IServerRecordsRepository repository) : ControllerBase
{
    /// <summary>運営権限保有者を除外した全サーバーレコードを取得します。集計結果は最大10秒間共有します。</summary>
    [HttpGet]
    [ProducesResponseType(typeof(ServerRecordsResponse), StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    public async Task<ActionResult<ServerRecordsResponse>> Get([FromQuery] int limit = 10, CancellationToken cancellationToken = default)
    {
        if (limit is < 1 or > 100)
            return BadRequest(new { message = "limit must be between 1 and 100." });
        return Ok(await repository.GetAsync(limit, cancellationToken));
    }
}
