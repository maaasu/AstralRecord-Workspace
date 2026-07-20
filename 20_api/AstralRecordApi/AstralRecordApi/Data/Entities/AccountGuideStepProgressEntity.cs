namespace AstralRecordApi.Data.Entities;

public class AccountGuideStepProgressEntity
{
    public Guid AccountGuideStepProgressId { get; set; }
    public Guid AccountId { get; set; }
    public string GuideId { get; set; } = string.Empty;
    public string StepId { get; set; } = string.Empty;
    public DateTime CompletedAt { get; set; }
    public DateTime CreatedAt { get; set; }
    public Guid CreatedBy { get; set; }
}
