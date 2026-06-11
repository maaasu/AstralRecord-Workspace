using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using AstralRecordApi.Services;
using Microsoft.AspNetCore.Mvc;

namespace AstralRecordApi.Controllers;

[ApiController]
[Route("api/market")]
public class MarketController(
    IMarketRepository marketRepository,
    IMarketPriceService marketPriceService
) : ControllerBase
{
    /// <summary>
    /// マーケット出品を条件検索します。
    /// </summary>
    [HttpGet("listings")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    public async Task<IActionResult> GetListings(
        [FromQuery(Name = "seller_account_id")] Guid? sellerAccountId,
        [FromQuery(Name = "item_category")] string? itemCategory,
        [FromQuery(Name = "item_id")] string? itemId,
        [FromQuery] string? status,
        [FromQuery(Name = "min_price")] long? minPrice,
        [FromQuery(Name = "max_price")] long? maxPrice,
        [FromQuery] string? sort,
        [FromQuery] int page = 1,
        [FromQuery(Name = "page_size")] int pageSize = 50
    )
    {
        if (page < 1 || pageSize is < 1 or > 100)
            return Problem(statusCode: StatusCodes.Status400BadRequest, title: "Validation failed", detail: "Paging query is invalid.");

        var listings = await marketRepository.GetListingsAsync(new MarketListingQuery
        {
            SellerAccountId = sellerAccountId,
            ItemCategory = itemCategory,
            ItemId = itemId,
            Status = status,
            MinPrice = minPrice,
            MaxPrice = maxPrice,
            Sort = sort,
            Page = page,
            PageSize = pageSize,
        });

        return Ok(listings);
    }

    /// <summary>
    /// 指定されたマーケット出品を取得します。
    /// </summary>
    [HttpGet("listings/{listingId:guid}")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<IActionResult> GetListing(Guid listingId)
    {
        var listing = await marketRepository.GetListingAsync(listingId);
        return listing is null ? NotFound() : Ok(listing);
    }

    /// <summary>
    /// アカウント単位のマーケット利用状態と出品上限を取得します。
    /// </summary>
    [HttpGet("accounts/{accountId:guid}/summary")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<IActionResult> GetAccountSummary(Guid accountId)
    {
        var summary = await marketRepository.GetAccountSummaryAsync(accountId);
        return summary is null ? NotFound() : Ok(summary);
    }

    /// <summary>
    /// 出品予定商品の相場見積と価格ガード判定を返します。
    /// </summary>
    [HttpPost("price-quote")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<IActionResult> CreatePriceQuote([FromBody] MarketPriceQuoteRequest request)
    {
        if (request.Quantity < 1)
            return Problem(statusCode: StatusCodes.Status400BadRequest, title: "Validation failed", detail: "Quantity is invalid.");

        var quote = await marketPriceService.CreateQuoteAsync(request);
        return quote is null ? NotFound() : Ok(quote);
    }

    /// <summary>
    /// マーケット出品を作成します。
    /// </summary>
    [HttpPost("listings")]
    [ProducesResponseType(StatusCodes.Status201Created)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    [ProducesResponseType(StatusCodes.Status409Conflict)]
    public async Task<IActionResult> CreateListing([FromBody] MarketListingCreateRequest request)
    {
        var result = await marketRepository.CreateListingAsync(request);
        if (!result.Succeeded)
            return Error(result);

        return CreatedAtAction(nameof(GetListing), new { listingId = result.Value!.ListingId }, result.Value);
    }

    /// <summary>
    /// マーケット出品を購入確定します。
    /// </summary>
    [HttpPost("listings/{listingId:guid}/purchase")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    [ProducesResponseType(StatusCodes.Status409Conflict)]
    public async Task<IActionResult> PurchaseListing(Guid listingId, [FromBody] MarketPurchaseRequest request)
    {
        var result = await marketRepository.PurchaseListingAsync(listingId, request);
        return result.Succeeded ? Ok(result.Value) : Error(result);
    }

    /// <summary>
    /// マーケット出品をキャンセルし、escrow 中の品を返却します。
    /// </summary>
    [HttpPost("listings/{listingId:guid}/cancel")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    [ProducesResponseType(StatusCodes.Status403Forbidden)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    [ProducesResponseType(StatusCodes.Status409Conflict)]
    public async Task<IActionResult> CancelListing(Guid listingId, [FromBody] MarketCancelRequest request)
    {
        var result = await marketRepository.CancelListingAsync(listingId, request);
        return result.Succeeded ? Ok(result.Value) : Error(result);
    }

    private IActionResult Error<T>(MarketOperationResult<T> result)
    {
        return Problem(
            statusCode: result.StatusCode,
            title: result.ErrorCode ?? "market.error",
            detail: result.Detail);
    }
}
