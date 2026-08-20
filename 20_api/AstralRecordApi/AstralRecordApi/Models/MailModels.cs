namespace AstralRecordApi.Models;

public class MailRewardResponse
{
    public required string ItemId { get; init; }
    public required string Category { get; init; }
    public int Amount { get; init; }
}

public class MailResponse
{
    public required int SchemaVersion { get; init; }
    public required string Id { get; init; }
    public required string Icon { get; init; }
    public required string Title { get; init; }
    public required string Body { get; init; }
    public DateTime PublishFrom { get; init; }
    public DateTime? PublishTo { get; init; }
    public bool FirstLoginOnly { get; init; }
    public bool ReceiveOnRead { get; init; }
    public IReadOnlyList<MailRewardResponse> Rewards { get; init; } = [];
    public bool IsRead { get; init; }
    public DateTime? ReadAt { get; init; }
    public bool IsDeleted { get; init; }
}

public class MailActionRequest
{
    public Guid UserId { get; set; }
    public Guid UpdatedBy { get; set; }
}
