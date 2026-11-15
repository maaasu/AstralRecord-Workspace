using System.Text.Json.Nodes;

namespace SkillTreeEditor.Server.Models;

public sealed record ProgressionDesignCatalog(
    IReadOnlyList<ProgressionDesignClass> Classes,
    IReadOnlyList<ProgressionDesignPreset> Presets,
    IReadOnlyList<ProgressionDesignItem> Items,
    IReadOnlyList<ProgressionDesignSource> Sources,
    IReadOnlyList<ProgressionDesignUsage> Usages,
    IReadOnlyList<ProgressionDesignAddTarget> AddTargets,
    IReadOnlyDictionary<string, string> Revisions,
    IReadOnlyList<ProgressionDesignSkill> Skills,
    IReadOnlyList<ProgressionDesignNode> Nodes,
    IReadOnlyList<ProgressionDesignEnchantChoice> EnchantChoices);

public sealed record ProgressionDesignClass(string Id, string Name, string Path, IReadOnlyList<string> PrerequisiteClassIds,
    int MaxLevel = 0, IReadOnlyList<string>? UsableSkillIds = null);

public sealed record ProgressionDesignPreset(string Id, string Name, string Path, string ClassId, string BuildId,
    IReadOnlyList<ProgressionDesignCheckpoint> Checkpoints);

public sealed record ProgressionDesignCheckpoint(int Level, int ClassLevel, string Pointer,
    IReadOnlyList<ProgressionDesignEquipment> Equipment, IReadOnlyList<int> Nodes,
    IReadOnlyList<ProgressionDesignSkillAssignment> Skills, IReadOnlyList<ProgressionDesignSkillAssignment> Passives);

public sealed record ProgressionDesignSkillAssignment(int Slot, string SkillId, string Pointer, int SkillLevel,
    IReadOnlyList<ProgressionDesignSigilAssignment> Sigils);
public sealed record ProgressionDesignSigilAssignment(int Slot, string ItemId, string Pointer);
public sealed record ProgressionDesignSigilSlots(int Level, int Slots);
public sealed record ProgressionDesignSkill(string Id, string Name, string Path, string? Icon, string? IconTexture,
    int? CustomModelData, bool Passive, bool BindRequired, int RequiredLevel, int MaxLevel,
    IReadOnlyList<ProgressionDesignSigilSlots> SigilSlotsByLevel, IReadOnlyList<string> AllowedSigilIds);
public sealed record ProgressionDesignNode(int Id, string Name, string Path, string? Icon, string? IconTexture,
    int? CustomModelData, IReadOnlyList<string> SkillIds);

public sealed record ProgressionDesignEquipment(string Slot, string ItemId, string Pointer, int EnhanceLevel,
    IReadOnlyList<ProgressionDesignEnchant> Enchants);
public sealed record ProgressionDesignEnchant(string EnchantMasterId, string EffectId, decimal Value);
public sealed record ProgressionDesignEnchantChoice(string EnchantMasterId, string EquipmentType, string EffectId,
    string Name, string Type, decimal Min, decimal Max, string SourceItemId);

public sealed record ProgressionDesignItem(string Id, string Name, string Path, string? Category, string? Rarity, string? Icon,
    string? IconTexture, int? CustomModelData, string? Slot, string? HandType, int RequiredLevel,
    IReadOnlyList<string> RequiredClasses, IReadOnlyList<string> CompatibleSlots, int MaxEnhanceLevel,
    string? SigilEquipGroupId = null, IReadOnlyList<string>? UsableSkillIds = null,
    IReadOnlyList<ProgressionDesignClassRequirement>? RequiredClassLevels = null,
    int MaxEnchantSlots = 0, string? EnchantEquipmentType = null);
public sealed record ProgressionDesignClassRequirement(string ClassId, int Level);

public sealed record ProgressionDesignLocation(string Path, string Pointer);
public sealed record ProgressionDesignField(string Path, string Pointer, JsonNode Value);
public sealed record ProgressionDesignIngredient(string ItemId, string Path, string Pointer, string ItemPointer,
    IReadOnlyList<ProgressionDesignField> Quantity);

public sealed record ProgressionDesignContext(string Kind, string Id, string Name, string Path, string Pointer);

public sealed record ProgressionDesignSource(string Id, string Kind, string Name, string ItemId, string Path, string Pointer,
    string ItemPath, string ItemPointer, IReadOnlyList<ProgressionDesignField> Quantity,
    IReadOnlyList<ProgressionDesignField> Rates, IReadOnlyList<ProgressionDesignIngredient> Ingredients,
    IReadOnlyList<ProgressionDesignContext> Context, int? Level, string? Mode, string Availability,
    IReadOnlyList<string> Warnings, ProgressionDesignLocation? IngredientContainer, IReadOnlyList<ProgressionDesignField> Costs);

public sealed record ProgressionDesignUsage(string ItemId, string Kind, string Name, string Path, string Pointer,
    string? OutputItemId, IReadOnlyList<ProgressionDesignField> Quantity);

public sealed record ProgressionDesignAddTarget(string Id, string Kind, string Name, string Path, string Pointer,
    string? Icon, int? CustomModelData, string? IconTexture, int? Level);

public sealed record ProgressionDesignPreviewRequest(IReadOnlyList<ProgressionDesignPreviewDocument> Documents);
public sealed record ProgressionDesignPreviewDocument(string Path, JsonObject Content, string? Revision = null);
