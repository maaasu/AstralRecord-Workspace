namespace AstralRecordApi.Models;

public class AccountLearnedSkillResponse
{
    public Guid LearnedSkillId { get; init; }
    public Guid AccountId { get; init; }
    public required string SkillId { get; init; }
    public int Level { get; init; }
    public IReadOnlyList<AccountLearnedSkillSigilResponse> Sigils { get; init; } = [];
    public int Version { get; init; }
    public DateTime CreatedAt { get; init; }
    public DateTime UpdatedAt { get; init; }
}

public class AccountLearnedSkillSigilResponse
{
    public Guid LearnedSkillSigilId { get; init; }
    public required string SigilId { get; init; }
    public required string EquipGroupId { get; init; }
    public int SlotIndex { get; init; }
}

public class AccountLearnedSkillLearnRequest
{
    public required string SkillId { get; init; }
    public Guid GemInventoryEntryId { get; init; }
    public Guid UpdatedBy { get; init; }
}

public class AccountLearnedSkillLevelUpRequest
{
    public Guid GemInventoryEntryId { get; init; }
    public Guid UpdatedBy { get; init; }
}

public class AccountLearnedSkillAttachSigilRequest
{
    public required string SigilId { get; init; }
    public Guid SigilInventoryEntryId { get; init; }
    public Guid UpdatedBy { get; init; }
}

public enum AccountLearnedSkillMutationFailure
{
    None,
    AccountNotFound,
    LearnedSkillNotFound,
    SkillNotFound,
    SigilNotFound,
    InvalidMaterial,
    MaxLevelReached,
    NoSigilSlot,
    SigilNotAllowed,
    DuplicateSigilGroup,
}

public record AccountLearnedSkillMutationResult(
    AccountLearnedSkillResponse? Skill,
    AccountLearnedSkillMutationFailure Failure)
{
    public bool Succeeded => Failure == AccountLearnedSkillMutationFailure.None && Skill is not null;
}
