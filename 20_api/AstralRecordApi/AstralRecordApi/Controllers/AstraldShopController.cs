using System.Security.Cryptography;
using System.Text;
using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.AspNetCore.Mvc;

namespace AstralRecordApi.Controllers;

/// <summary>有償アストラルド消耗品のWeb直接購入とゲームサーバー保存境界での確定。</summary>
[ApiController]
[Route("api/web/astrald-shop")]
public sealed class AstraldShopController(IAstraldShopRepository repository, IConfiguration configuration) : ControllerBase
{
    /// <summary>有償ショップマスタと購入先チャンネル一覧を取得します。</summary>
    [HttpGet("catalog")]
    public async Task<IActionResult> Catalog() => Ok(await repository.GetCatalogAsync());

    /// <summary>本人の現在アカウントへの購入要求を受け付けます。</summary>
    [HttpPost("purchases")]
    public async Task<IActionResult> Create([FromQuery(Name = "actor_user_uuid")] Guid actor,
        AstraldShopPurchaseRequest request)
    {
        if (!TrustedWeb(actor)) return StatusCode(403);
        var result = await repository.CreateAsync(actor, request);
        return result.Status == "REJECTED" ? Conflict(result) : Ok(result);
    }

    /// <summary>本人の購入確定結果を取得します。</summary>
    [HttpGet("purchases/{operationId:guid}")]
    public async Task<IActionResult> Get(Guid operationId, [FromQuery(Name = "actor_user_uuid")] Guid actor)
    {
        if (!TrustedWeb(actor)) return StatusCode(403);
        return await repository.GetAsync(actor, operationId) is { } result ? Ok(result) : NotFound();
    }

    /// <summary>ゲームサーバーがオンライン中アカウントの保留購入を列挙します。</summary>
    [HttpGet("purchases/pending/{accountId:guid}")]
    public async Task<IActionResult> Pending(Guid accountId) => Ok(await repository.PendingAsync(accountId));

    /// <summary>ゲームサーバーが保存境界内で保留購入を確定します。</summary>
    [HttpPost("purchases/{operationId:guid}/process")]
    public async Task<IActionResult> Process(Guid operationId, AstraldShopProcessRequest request)
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
