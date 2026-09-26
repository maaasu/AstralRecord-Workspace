using System.Security.Cryptography;
using System.Text;
using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.AspNetCore.Mvc;

namespace AstralRecordApi.Controllers;

/// <summary>Web本人向けメール一覧と通貨添付の直接受取。</summary>
[ApiController]
[Route("api/web/mail")]
public sealed class WebMailController(IWebMailRepository repository, IConfiguration configuration) : ControllerBase
{
    /// <summary>本人の現在アカウントに届いたメールを返します。</summary>
    [HttpGet]
    public async Task<IActionResult> List([FromQuery(Name = "actor_user_uuid")] Guid actor,
        [FromQuery(Name = "account_id")] Guid accountId, [FromQuery] string? filter)
    {
        if (!TrustedWeb(actor)) return StatusCode(403);
        return await repository.ListAsync(actor, accountId, filter) is { } rows ? Ok(rows) : StatusCode(403);
    }

    /// <summary>通貨の添付だけを本人に付与し、非通貨の添付はゲーム内受取用に残します。</summary>
    [HttpPost("{mailId}/claim-currency")]
    public async Task<IActionResult> Claim(string mailId, [FromQuery(Name = "actor_user_uuid")] Guid actor,
        WebMailCurrencyClaimRequest request)
    {
        if (!TrustedWeb(actor)) return StatusCode(403);
        var result = await repository.CreateAsync(actor, mailId, request);
        return result.Status == "REJECTED" ? Conflict(result) : Ok(result);
    }

    /// <summary>本人のWeb通貨受取状態を取得します。</summary>
    [HttpGet("claims/{operationId:guid}")]
    public async Task<IActionResult> Get(Guid operationId, [FromQuery(Name = "actor_user_uuid")] Guid actor)
    {
        if (!TrustedWeb(actor)) return StatusCode(403);
        return await repository.GetAsync(actor, operationId) is { } result ? Ok(result) : NotFound();
    }

    /// <summary>ゲームサーバーがオンライン中アカウントの保留受取を列挙します。</summary>
    [HttpGet("claims/pending/{accountId:guid}")]
    public async Task<IActionResult> Pending(Guid accountId) => Ok(await repository.PendingAsync(accountId));

    /// <summary>ゲームサーバーが保存境界内で通貨受取を確定します。</summary>
    [HttpPost("claims/{operationId:guid}/process")]
    public async Task<IActionResult> Process(Guid operationId, WebMailClaimProcessRequest request)
    {
        if (!request.PreparedOnline) return BadRequest();
        var result = await repository.ProcessAsync(operationId, request);
        return result is null ? NotFound() : result.Status == "REJECTED" ? Conflict(result) : Ok(result);
    }

    private bool TrustedWeb(Guid actor)
    {
        Response.Headers.CacheControl = "no-store";
        var expected = configuration["Donations:WebKey"];
        var supplied = Request.Headers["X-Donation-Web-Key"].ToString();
        return actor != Guid.Empty && !string.IsNullOrWhiteSpace(expected)
            && CryptographicOperations.FixedTimeEquals(
                Encoding.UTF8.GetBytes(expected), Encoding.UTF8.GetBytes(supplied));
    }
}
