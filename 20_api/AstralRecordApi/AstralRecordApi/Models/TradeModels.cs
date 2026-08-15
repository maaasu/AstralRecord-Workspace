using Microsoft.AspNetCore.Http;

namespace AstralRecordApi.Models;

/// <summary>
/// プレイヤー間トレードを一括確定するリクエストです。
/// </summary>
public sealed class TradeCommitRequest
{
    public Guid OperationId { get; set; }
    public Guid PlayerAAccountId { get; set; }
    public Guid PlayerBAccountId { get; set; }
    public List<TradeCommitItemRequest> PlayerAItems { get; set; } = [];
    public List<TradeCommitItemRequest> PlayerBItems { get; set; } = [];
    public long PlayerAGold { get; set; }
    public long PlayerBGold { get; set; }
    public Guid UpdatedBy { get; set; }
}

/// <summary>
/// トレードで移管する、提示元 inventory entry を特定する明細です。
/// </summary>
public sealed class TradeCommitItemRequest
{
    public Guid SourceInventoryEntryId { get; set; }
    public long Quantity { get; set; }
}

/// <summary>
/// トレード確定後に Plugin 側で再同期する entry ID を返します。
/// </summary>
public sealed class TradeCommitResponse
{
    public Guid OperationId { get; init; }
    public IReadOnlyList<Guid> PlayerAAffectedInventoryEntryIds { get; init; } = Array.Empty<Guid>();
    public IReadOnlyList<Guid> PlayerBAffectedInventoryEntryIds { get; init; } = Array.Empty<Guid>();
    public DateTime CompletedAt { get; init; }
}

public sealed class TradeOperationResult<T>
{
    public bool Succeeded { get; init; }
    public T? Value { get; init; }
    public int StatusCode { get; init; }
    public string? ErrorCode { get; init; }
    public string? Detail { get; init; }

    public static TradeOperationResult<T> Success(T value) => new()
    {
        Succeeded = true,
        Value = value,
        StatusCode = StatusCodes.Status200OK,
    };

    public static TradeOperationResult<T> Failure(int statusCode, string errorCode, string detail) => new()
    {
        Succeeded = false,
        StatusCode = statusCode,
        ErrorCode = errorCode,
        Detail = detail,
    };
}
