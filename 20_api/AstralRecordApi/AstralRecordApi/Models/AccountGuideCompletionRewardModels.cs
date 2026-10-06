namespace AstralRecordApi.Models;

public sealed class AccountGuideCompletionRewardOptions
{
    public List<MailRewardResponse> Rewards { get; set; } = [];
}

public sealed class AccountGuideCompletionRewardClaimRequest
{
    public Guid UpdatedBy { get; set; }
}

public sealed record AccountGuideCompletionRewardResponse(
    Guid AccountId,
    bool Configured,
    bool AllCompleted,
    bool Claimed,
    bool CanClaim,
    string? MailId);

public sealed class AccountGuideCompletionRewardUnavailableException(string reason) : Exception(reason);
