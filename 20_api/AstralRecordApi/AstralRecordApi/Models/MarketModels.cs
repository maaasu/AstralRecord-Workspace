using Microsoft.AspNetCore.Http;

namespace AstralRecordApi.Models;

public class MarketListingResponse
{
    public Guid ListingId { get; init; }
    public Guid SellerAccountId { get; init; }
    public string SellerAccountName { get; init; } = string.Empty;
    public Guid? BuyerAccountId { get; init; }
    public Guid? SourceInventoryEntryId { get; init; }
    public string ItemCategory { get; init; } = string.Empty;
    public string ItemId { get; init; } = string.Empty;
    public string? InstanceType { get; init; }
    public Guid? InstanceId { get; init; }
    public long Quantity { get; init; }
    public string CurrencyId { get; init; } = string.Empty;
    public long UnitPrice { get; init; }
    public long TotalPrice { get; init; }
    public long PriceFloor { get; init; }
    public long? ReferenceUnitPrice { get; init; }
    public decimal? PriceDeviationRate { get; init; }
    public string PriceConfidence { get; init; } = string.Empty;
    public string? ValuationSignature { get; init; }
    public string? ValuationSnapshotJson { get; init; }
    public string Status { get; init; } = string.Empty;
    public string? StatusReason { get; init; }
    public DateTime ListedAt { get; init; }
    public DateTime ExpiresAt { get; init; }
    public DateTime? SoldAt { get; init; }
    public DateTime? CanceledAt { get; init; }
    public int Version { get; init; }
    public DateTime CreatedAt { get; init; }
    public DateTime UpdatedAt { get; init; }
}

public class MarketListingCreateRequest
{
    public Guid SellerAccountId { get; set; }
    public Guid? SourceInventoryEntryId { get; set; }
    public required string ItemCategory { get; set; }
    public required string ItemId { get; set; }
    public string? InstanceType { get; set; }
    public Guid? InstanceId { get; set; }
    public long Quantity { get; set; } = 1;
    public required string CurrencyId { get; set; }
    public long UnitPrice { get; set; }
    public DateTime? ExpiresAt { get; set; }
    public Guid CreatedBy { get; set; }
}

public class MarketPriceQuoteRequest
{
    public Guid? AccountId { get; set; }
    public required string ItemCategory { get; set; }
    public required string ItemId { get; set; }
    public string? InstanceType { get; set; }
    public Guid? InstanceId { get; set; }
    public long Quantity { get; set; } = 1;
    public long? UnitPrice { get; set; }
}

public class MarketPriceQuoteResponse
{
    public string ItemCategory { get; init; } = string.Empty;
    public string ItemId { get; init; } = string.Empty;
    public string? InstanceType { get; init; }
    public Guid? InstanceId { get; init; }
    public long SellPrice { get; init; }
    public long SuggestedUnitPrice { get; init; }
    public long? ReferenceUnitPrice { get; init; }
    public int SampleCount { get; init; }
    public string ReferenceScope { get; init; } = string.Empty;
    public string Confidence { get; init; } = string.Empty;
    public long AllowedMinUnitPrice { get; init; }
    public long AllowedMaxUnitPrice { get; init; }
    public string Judgement { get; init; } = string.Empty;
    public string? ValuationSignature { get; init; }
    public decimal? RollQualityScore { get; init; }
    public string? RollQualityBucket { get; init; }
    public DateTime EvaluatedAt { get; init; }
}

public class MarketPurchaseRequest
{
    public Guid BuyerAccountId { get; set; }
    public required string IdempotencyKey { get; set; }
    public Guid UpdatedBy { get; set; }
}

public class MarketCancelRequest
{
    public Guid SellerAccountId { get; set; }
    public string? Reason { get; set; }
    public Guid UpdatedBy { get; set; }
}

public class MarketTransactionResponse
{
    public Guid TransactionId { get; init; }
    public Guid ListingId { get; init; }
    public Guid SellerAccountId { get; init; }
    public Guid BuyerAccountId { get; init; }
    public string ItemCategory { get; init; } = string.Empty;
    public string ItemId { get; init; } = string.Empty;
    public string? InstanceType { get; init; }
    public Guid? InstanceId { get; init; }
    public long Quantity { get; init; }
    public string CurrencyId { get; init; } = string.Empty;
    public long UnitPrice { get; init; }
    public long TotalPrice { get; init; }
    public long FeeAmount { get; init; }
    public long SellerProceeds { get; init; }
    /// <summary>
    /// 購入者の Plugin インベントリ再同期に必要な、API 側で更新した entry ID です。
    /// </summary>
    public IReadOnlyList<Guid> AffectedInventoryEntryIds { get; init; } = Array.Empty<Guid>();
    public DateTime CompletedAt { get; init; }
}

public class MarketAccountSummaryResponse
{
    public Guid AccountId { get; init; }
    public int ActiveListingCount { get; init; }
    /// <summary>互換用の旧フィールドです。MaxListingSlotCount と同じ値を返します。</summary>
    public int MaxActiveListingCount { get; init; }
    /// <summary>ACTIVE / SUSPENDED / CANCELED を含む、消費済み出品枠数です。</summary>
    public int UsedListingSlotCount { get; init; }
    /// <summary>出品中・取り下げ済みを通算して使える出品枠の上限です。</summary>
    public int MaxListingSlotCount { get; init; }
    public int CompletedTradeCount { get; init; }
    public string Tier { get; init; } = string.Empty;
    public DateTime? SuspendedUntil { get; init; }
    public DateTime UpdatedAt { get; init; }
}

public class MarketListingQuery
{
    public Guid? SellerAccountId { get; init; }
    public string? ItemCategory { get; init; }
    public string? ItemId { get; init; }
    public string? Status { get; init; }
    public long? MinPrice { get; init; }
    public long? MaxPrice { get; init; }
    public string? Sort { get; init; }
    public int Page { get; init; } = 1;
    public int PageSize { get; init; } = 50;
}

public class MarketOperationResult<T>
{
    public bool Succeeded { get; init; }
    public T? Value { get; init; }
    public int StatusCode { get; init; }
    public string? ErrorCode { get; init; }
    public string? Detail { get; init; }

    public static MarketOperationResult<T> Success(T value) => new()
    {
        Succeeded = true,
        Value = value,
        StatusCode = StatusCodes.Status200OK,
    };

    public static MarketOperationResult<T> Failure(int statusCode, string errorCode, string detail) => new()
    {
        Succeeded = false,
        StatusCode = statusCode,
        ErrorCode = errorCode,
        Detail = detail,
    };
}
