namespace AstralRecordApi.Data.Entities;

public class PetInstanceEntity
{
    public Guid InstanceId { get; set; }
    public Guid AccountId { get; set; }
    public string SpeciesId { get; set; } = "";
    public string ItemId { get; set; } = "";
    public bool IsEgg { get; set; }
    public string Origin { get; set; } = "WILD";
    // Egg payload is private and never mapped to the response DTO.
    public string DetailsJson { get; set; } = "{}";
    public Guid? MaleParentId { get; set; }
    public Guid? FemaleParentId { get; set; }
    public long Version { get; set; } = 1;
    public DateTime CreatedAt { get; set; }
    public DateTime UpdatedAt { get; set; }
    public Guid CreatedBy { get; set; }
    public Guid UpdatedBy { get; set; }
    public bool IsDeleted { get; set; }
}

public class AccountPetStateEntity
{
    public Guid AccountId { get; set; }
    public Guid? EquippedPetId { get; set; }
    public DateTime UpdatedAt { get; set; }
    public Guid UpdatedBy { get; set; }
}

public class PetOperationEntity
{
    public Guid OperationId { get; set; }
    public Guid AccountId { get; set; }
    public string RequestHash { get; set; } = "";
    public string ResponseJson { get; set; } = "";
    public string AffectedEntryIdsJson { get; set; } = "[]";
    public DateTime CompletedAt { get; set; }
}
