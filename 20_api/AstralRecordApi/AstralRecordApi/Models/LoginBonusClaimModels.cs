namespace AstralRecordApi.Models;

public class LoginBonusClaimRequest
{
    public DateOnly ClaimDate { get; set; }
    public Guid UpdatedBy { get; set; }
}

public class LoginBonusClaimResponse
{
    public Guid LoginBonusClaimId { get; init; }
    public Guid AccountId { get; init; }
    public DateOnly ClaimDate { get; init; }
    public DateTime ClaimedAt { get; init; }
    public DateTime CreatedAt { get; init; }
    public DateTime UpdatedAt { get; init; }
    public Guid CreatedBy { get; init; }
    public Guid UpdatedBy { get; init; }
    public bool IsDeleted { get; init; }
    public bool WasCreated { get; init; }
}
