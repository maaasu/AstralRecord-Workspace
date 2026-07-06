namespace AstralRecordApi.Data.Entities;

public class LoginBonusClaimEntity
{
    public Guid LoginBonusClaimId { get; set; }
    public Guid AccountId { get; set; }
    public DateOnly ClaimDate { get; set; }
    public DateTime ClaimedAt { get; set; }
    public DateTime CreatedAt { get; set; }
    public DateTime UpdatedAt { get; set; }
    public Guid CreatedBy { get; set; }
    public Guid UpdatedBy { get; set; }
    public bool IsDeleted { get; set; }
}
