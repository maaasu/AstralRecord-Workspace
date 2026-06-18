namespace AstralRecordApi.Models;

public class AccountWaystoneUnlockRequest
{
    public required string WaystoneId { get; set; }
    public Guid UpdatedBy { get; set; }
}

public class AccountWaystoneUnlockResponse
{
    public Guid AccountWaystoneUnlockId { get; init; }
    public Guid AccountId { get; init; }
    public string WaystoneId { get; init; } = string.Empty;
    public DateTime UnlockedAt { get; init; }
    public DateTime CreatedAt { get; init; }
    public DateTime UpdatedAt { get; init; }
    public Guid CreatedBy { get; init; }
    public Guid UpdatedBy { get; init; }
    public bool IsDeleted { get; init; }
}

public class AccountWaystoneStateResponse
{
    public Guid AccountId { get; init; }
    public IReadOnlyList<string> UnlockedWaystoneIds { get; init; } = [];
}
