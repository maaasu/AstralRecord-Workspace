namespace AstralRecordApi.Data.Entities;

public class AccountGuideCompletionRewardEntity
{
    public Guid AccountId { get; set; }
    public string? MailId { get; set; }
    public DateTime ClaimedAt { get; set; }
    public Guid ClaimedBy { get; set; }
    public Guid? InheritedFromAccountId { get; set; }
}
