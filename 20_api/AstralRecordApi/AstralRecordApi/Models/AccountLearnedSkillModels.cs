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
    public Guid OrbInventoryEntryId { get; init; }
    public Guid UpdatedBy { get; init; }
}

public class AccountLearnedSkillDetachSigilRequest
{
    public Guid OrbInventoryEntryId { get; init; }
    public Guid UpdatedBy { get; init; }
}

public class AccountLearnedSkillDetachSigilResponse
{
    public required AccountLearnedSkillResponse Skill { get; init; }
    public Guid ReturnedInventoryEntryId { get; init; }
}

public class AccountLearnedSkillForgetRequest
{
    public Guid UpdatedBy { get; init; }
}

public enum AccountLearnedSkillMutationFailure
{
    None,
    AccountNotFound,
    LearnedSkillNotFound,
    SkillNotFound,
    SigilNotFound,
    SigilAttachmentNotFound,
    InventoryNotFound,
    InvalidMaterial,
    MaxLevelReached,
    NoSigilSlot,
    SigilNotAllowed,
    DuplicateSigilGroup,
}

public record AccountLearnedSkillMutationResult(
    AccountLearnedSkillResponse? Skill,
    AccountLearnedSkillMutationFailure Failure,
    Guid? ReturnedInventoryEntryId = null)
{
    public bool Succeeded => Failure == AccountLearnedSkillMutationFailure.None && Skill is not null;
}
