using System.Text.Json.Serialization;

namespace AstralRecordApi.Models;

public class PetMasterResponse
{
    public string Id { get; set; } = "pets";
    public PetRules Rules { get; set; } = new();
    public PetExperienceMaster Experience { get; set; } = new();
    public List<PetSpeciesMaster> Species { get; set; } = [];
}

public class PetRules
{
    public int MaxLevel { get; set; } = 100;
    public int WildMaxTier { get; set; } = 4;
    public int BreedMaxTier { get; set; } = 10;
    public int[] SkillSlotLevels { get; set; } = [1, 20, 50];
    public int BreedMinLevel { get; set; } = 20;
    public int BreedCooldownHours { get; set; } = 24;
    public double MutationChance { get; set; } = .01;
    public double WildPotentialChance { get; set; } = .001;
    public double PotentialGrowthMultiplier { get; set; } = 2;
    public double MaleChance { get; set; } = .5;
    public int[] PotentialCountWeights { get; set; } = [20, 75, 5];
    public double EggDropChance { get; set; } = .0001;
    public string ReviveOrbItemId { get; set; } = "40a00020";
    public string FacilityId { get; set; } = "pet_center";
    public List<PetMaterialMaster> BreedMaterials { get; set; } = [];
    public List<PetMaterialMaster> ReviveMaterials { get; set; } = [];
}

public class PetExperienceMaster
{
    public double Base { get; set; } = 500;
    public double Quadratic { get; set; } = 120;
    public int TierInterval { get; set; } = 10;
    public double TierBonus { get; set; } = 850;
    public int[] Wave { get; set; } = [0, 90, 35, 140, 60, 185, 95, 230];
    public int MilestoneInterval { get; set; } = 5;
    public double MilestoneBase { get; set; } = 600;
    public double MilestoneLinear { get; set; } = 80;
    public double ActivityRate { get; set; } = 1;
}

public class PetSpeciesMaster
{
    public string Id { get; set; } = "";
    public string DisplayName { get; set; } = "";
    public string EntityType { get; set; } = "";
    public string EggItemId { get; set; } = "";
    public string PetItemId { get; set; } = "";
    public double DropWeight { get; set; } = 1;
    public string[] Names { get; set; } = [];
    public double SizeMin { get; set; } = .85;
    public double SizeMax { get; set; } = 1.15;
    public List<PetMaterialMaster> HatchMaterials { get; set; } = [];
    public Dictionary<string, PetStatMaster> Stats { get; set; } = [];
    public List<PetSkillMaster> Skills { get; set; } = [];
    public PetBasicAttackMaster? BasicAttack { get; set; }
}

public class PetBasicAttackMaster
{
    public double DamageRatio { get; set; }
    public double CooldownSeconds { get; set; }
    public double Range { get; set; }
}

public class PetMaterialMaster
{
    public string ItemId { get; set; } = "";
    public long Quantity { get; set; }
}

public class PetStatMaster
{
    public List<PetTierMaster> Tiers { get; set; } = [];
    public double GrowthPerLevel { get; set; }
    public double InheritanceMin { get; set; }
    public double InheritanceMax { get; set; }
    public double Scale { get; set; } = 100;
    public double ValueWeightPower { get; set; } = 2;
}

public class PetTierMaster
{
    public int Tier { get; set; }
    public double Min { get; set; }
    public double Max { get; set; }
    public double Weight { get; set; }
}

public class PetSkillMaster
{
    public string Id { get; set; } = "";
    public string Name { get; set; } = "";
    public int Tier { get; set; }
    public double Weight { get; set; } = 1;
    public string Trigger { get; set; } = "";
    public string Effect { get; set; } = "";
    public double CooldownSeconds { get; set; }
    public double Value { get; set; }
    public double Chance { get; set; } = 1;
    public double DurationSeconds { get; set; }
    public int HitCount { get; set; } = 1;
    public int AttackCount { get; set; } = 1;
}

public class PetAccountResponse
{
    public Guid AccountId { get; init; }
    public Guid? EquippedPetId { get; init; }
    public IReadOnlyList<PetInstanceResponse> Instances { get; init; } = [];
}

public class PetInstanceResponse
{
    public Guid InstanceId { get; init; }
    public Guid AccountId { get; init; }
    public string SpeciesId { get; init; } = "";
    public string ItemId { get; init; } = "";
    public bool IsEgg { get; init; }
    public string Origin { get; init; } = "";
    public bool TradeAllowed { get; init; }
    public long Version { get; init; }
    public DateTime CreatedAt { get; init; }
    public DateTime UpdatedAt { get; init; }
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public PetDetailsResponse? Details { get; init; }
}

public class PetDetailsResponse
{
    public string Name { get; set; } = "";
    public string Sex { get; set; } = "";
    public double Size { get; set; }
    public int Level { get; set; } = 1;
    public long Experience { get; set; }
    public double HealthRatio { get; set; } = 1;
    public bool IsDead { get; set; }
    public DateTime? BreedAvailableAt { get; set; }
    public Dictionary<string, PetStatResponse> Stats { get; set; } = [];
    public List<PetSkillResponse> Skills { get; set; } = [];
    public Dictionary<string, long> Cooldowns { get; set; } = [];
}

public class PetStatResponse
{
    public int Tier { get; set; }
    public double BaseValue { get; set; }
    public bool Potential { get; set; }
    public double CurrentValue { get; set; }
}

public class PetSkillResponse
{
    public string Id { get; set; } = "";
    public int Tier { get; set; }
    public int SlotIndex { get; set; }
}

public class PetOperationRequest
{
    public Guid OperationId { get; set; }
    public Guid UpdatedBy { get; set; }
}
public class PetEggCreateRequest : PetOperationRequest { public string SpeciesId { get; set; } = ""; }
public class PetFacilityRequest : PetOperationRequest { public string FacilityId { get; set; } = ""; }
public class PetBreedRequest : PetFacilityRequest
{
    public Guid MaleId { get; set; }
    public Guid FemaleId { get; set; }
}
public class PetProgressRequest : PetOperationRequest
{
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? ServerId { get; set; }
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public Guid? ServerSessionId { get; set; }
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public Guid? AccountSessionId { get; set; }
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? AccountLeaseToken { get; set; }
    public long ExpectedVersion { get; set; }
    public long Experience { get; set; }
    public double HealthRatio { get; set; }
    public bool IsDead { get; set; }
    public Dictionary<string, long>? Cooldowns { get; set; }
}
public class PetReviveRequest : PetFacilityRequest { public Guid? OrbInventoryEntryId { get; set; } }
public class PetEquipRequest : PetOperationRequest { public Guid? PetId { get; set; } }
public class PetRenameRequest : PetOperationRequest { public string Name { get; set; } = ""; }

public class PetMutationResponse
{
    public PetInstanceResponse? Instance { get; set; }
    public Guid? EquippedPetId { get; set; }
    public InventoryOperationSnapshotResponse? InventorySnapshot { get; set; }
}

public record PetMutationResult(PetMutationResponse? Response, string? Failure = null)
{
    public bool Succeeded => Failure is null;
}
