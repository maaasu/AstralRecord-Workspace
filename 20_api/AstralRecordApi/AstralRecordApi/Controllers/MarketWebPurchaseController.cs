using System.Security.Cryptography;
using System.Text;
using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.AspNetCore.Mvc;

namespace AstralRecordApi.Controllers;

/// <summary>本人のWebマーケット購入要求と、ゲームサーバーによる保留要求処理を提供します。</summary>
[ApiController]
[Route("api/market/web-purchases")]
public sealed class MarketWebPurchaseController(IMarketRepository repository, IConfiguration configuration) : ControllerBase
{
    /// <summary>Web本人の出品購入を受け付け、オフラインなら直ちに確定します。</summary>
    [HttpPost("{listingId:guid}")]
    public async Task<IActionResult> Create(Guid listingId,
        [FromQuery(Name = "actor_user_uuid")] Guid actor, [FromBody] MarketWebPurchaseRequest request)
    {
        if (WebCredentialMissing()) return StatusCode(503,
            new { message = "Webマーケット購入の認証設定が完了していません。" });
        if (!TrustedWeb(actor)) return Forbid();
        var result = await repository.CreateWebPurchaseAsync(listingId, actor, request);
        return result.Succeeded ? Ok(result.Value) : Problem(
            statusCode: result.StatusCode, title: result.ErrorCode, detail: result.Detail);
    }

    /// <summary>Web本人へ購入要求の確定状態を返します。</summary>
    [HttpGet("{operationId:guid}")]
    public async Task<IActionResult> Get(Guid operationId, [FromQuery(Name = "actor_user_uuid")] Guid actor)
    {
        if (WebCredentialMissing()) return StatusCode(503,
            new { message = "Webマーケット購入の認証設定が完了していません。" });
        if (!TrustedWeb(actor)) return Forbid();
        var result = await repository.GetWebPurchaseAsync(operationId, actor);
        return result is null ? NotFound() : Ok(result);
    }

    /// <summary>ゲームサーバーがログイン中アカウントの保留購入要求を取得します。</summary>
    [HttpGet("pending/{accountId:guid}")]
    public async Task<IActionResult> Pending(Guid accountId) =>
        Ok(await repository.GetPendingWebPurchasesAsync(accountId));

    /// <summary>ゲームサーバーがアカウント保存境界内で保留購入を確定します。</summary>
    [HttpPost("{operationId:guid}/process")]
    public async Task<IActionResult> Process(Guid operationId, [FromBody] MarketPurchaseRequest request)
    {
        if (request.WebOperationId != operationId || !request.PreparedOnline)
            return BadRequest();
        var purchase = await repository.GetWebPurchaseAsync(operationId, request.UpdatedBy);
        if (purchase is null || purchase.BuyerAccountId != request.BuyerAccountId) return NotFound();
        var result = await repository.PurchaseListingAsync(purchase.ListingId, request);
        if (!result.Succeeded && result.StatusCode is >= 400 and < 500
            && result.ErrorCode != "market.online_session")
            await repository.RejectWebPurchaseAsync(operationId, result.ErrorCode ?? "market.purchase_rejected");
        return result.Succeeded ? Ok(result.Value) : Problem(
            statusCode: result.StatusCode, title: result.ErrorCode, detail: result.Detail);
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

    private bool WebCredentialMissing() =>
        string.IsNullOrWhiteSpace(configuration["Donations:WebKey"])
        || string.IsNullOrWhiteSpace(Request.Headers["X-Donation-Web-Key"].ToString());
}
