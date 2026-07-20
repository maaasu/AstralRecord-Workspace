namespace AstralRecordApi.Models;

public class AccountGuideStepCompleteRequest
{
    public required string GuideId { get; set; }
    public required string StepId { get; set; }
    public Guid UpdatedBy { get; set; }
}

public class AccountGuideStepProgressResponse
{
    public Guid AccountGuideStepProgressId { get; init; }
    public Guid AccountId { get; init; }
    public string GuideId { get; init; } = string.Empty;
    public string StepId { get; init; } = string.Empty;
    public DateTime CompletedAt { get; init; }
    public DateTime CreatedAt { get; init; }
    public Guid CreatedBy { get; init; }
}

public class AccountGuideProgressResponse
{
    public Guid AccountId { get; init; }
    public IReadOnlyList<AccountGuideStepProgressResponse> CompletedSteps { get; init; } = [];
}
