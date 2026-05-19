using AstralRecordApi.Models;
using Microsoft.AspNetCore.Mvc;

namespace AstralRecordApi.Controllers;

/// <summary>疎通確認 API</summary>
[ApiController]
[Route("api/[controller]")]
public class HealthController : ControllerBase
{
    /// <summary>疎通確認</summary>
    /// <remarks>外部システムから API サーバーへ接続できるかを確認します。</remarks>
    /// <response code="200">API サーバーへの接続成功</response>
    [HttpGet]
    [ProducesResponseType<HealthCheckResponse>(StatusCodes.Status200OK)]
    public ActionResult<HealthCheckResponse> Get()
    {
        return Ok(new HealthCheckResponse
        {
            Status = "ok",
            Service = "AstralRecordApi",
            Timestamp = DateTimeOffset.UtcNow
        });
    }
}