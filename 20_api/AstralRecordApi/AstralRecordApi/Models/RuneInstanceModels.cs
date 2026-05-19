namespace AstralRecordApi.Models;

public class RuneCreateRequest
{
    public required string RuneId { get; set; }
    public Guid AccountId { get; set; }
    public string Source { get; set; } = string.Empty;
    public Guid CreatedBy { get; set; }
}

public class RuneInstanceResponse
{
    public Guid RuneInstanceId { get; init; }
    public Guid AccountId { get; init; }
    public string ItemId { get; init; } = string.Empty;
    public DateTime CreatedAt { get; init; }
    public DateTime UpdatedAt { get; init; }
    public IReadOnlyList<RuneInstanceStatRollResponse> StatRolls { get; init; } = [];
}

public class RuneInstanceStatRollResponse
{
    public Guid StatRollId { get; init; }
    public string Status { get; init; } = string.Empty;
    public string Type { get; init; } = string.Empty;
    public string Value { get; init; } = string.Empty;
    public int SortOrder { get; init; }
}
