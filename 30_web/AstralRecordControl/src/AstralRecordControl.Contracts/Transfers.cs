namespace AstralRecordControl.Contracts;

public enum TransferStatus { Created, Uploading, Verified, Consumed, Expired, Failed }

public sealed record TransferCreateRequest
{
    public Guid? SourceHostId { get; init; }
    public Guid? DestinationHostId { get; init; }
    public Guid OperationId { get; init; }
    public Guid ServerId { get; init; }
    public long? SizeBytes { get; init; }
    public string? Sha256 { get; init; }
}

public sealed record TransferPrepareRequest(long SizeBytes, string Sha256);
public sealed record TransferCompleteRequest(long SizeBytes, string Sha256);

public sealed record TransferInfo
{
    public Guid TransferId { get; init; }
    public Guid? SourceHostId { get; init; }
    public Guid? DestinationHostId { get; init; }
    public Guid OperationId { get; init; }
    public Guid ServerId { get; init; }
    public Guid? ConsumingOperationId { get; init; }
    public TransferStatus Status { get; init; }
    public long? SizeBytes { get; init; }
    public string? Sha256 { get; init; }
    public long CommittedOffset { get; init; }
    public int ChunkSizeBytes { get; init; } = 4 * 1024 * 1024;
    public DateTimeOffset ExpiresAtUtc { get; init; }
}
