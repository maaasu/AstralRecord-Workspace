using System.Text.Json.Nodes;

namespace SkillTreeEditor.Server.Models;

public sealed record ProgressionDesignCatalog(
    IReadOnlyList<ProgressionDesignClass> Classes,
    IReadOnlyList<ProgressionDesignPreset> Presets,
    IReadOnlyList<ProgressionDesignItem> Items,
    IReadOnlyList<ProgressionDesignSource> Sources,
    IReadOnlyList<ProgressionDesignUsage> Usages,
    IReadOnlyList<ProgressionDesignAddTarget> AddTargets,
    IReadOnlyDictionary<string, string> Revisions);

public sealed record ProgressionDesignClass(string Id, string Name, string Path, IReadOnlyList<string> PrerequisiteClassIds);

public sealed record ProgressionDesignPreset(string Id, string Name, string Path, string ClassId, string BuildId,
    IReadOnlyList<ProgressionDesignCheckpoint> Checkpoints);

public sealed record ProgressionDesignCheckpoint(int Level, int ClassLevel, string Pointer,
    IReadOnlyList<ProgressionDesignEquipment> Equipment);

public sealed record ProgressionDesignEquipment(string Slot, string ItemId, string Pointer, int EnhanceLevel);

public sealed record ProgressionDesignItem(string Id, string Name, string Path, string? Category, string? Rarity, string? Icon,
    string? IconTexture, int? CustomModelData, string? Slot, string? HandType, int RequiredLevel,
    IReadOnlyList<string> RequiredClasses, IReadOnlyList<string> CompatibleSlots, int MaxEnhanceLevel);

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
