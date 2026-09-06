using System.Text.Json;

namespace AstralRecordApi.Models;

/// <summary>Plugin がローカル確定した account 単位 state を、再送可能な単一スナップショットとして保存する要求です。</summary>
public sealed class PlayerStateSnapshotSaveRequest
{
    public Guid SnapshotId { get; init; }
    public Guid AccountId { get; init; }
    public Guid UpdatedBy { get; init; }
    public IReadOnlyList<PlayerStateInventorySnapshot> Inventories { get; init; } = [];
    public IReadOnlyList<PlayerStateLoadoutSnapshot> Loadouts { get; init; } = [];
    public IReadOnlyList<PlayerStateEquipmentSnapshot> Equipment { get; init; } = [];

    // Section の payload は Plugin と API の section 契約に従って段階的に追加する。
    public JsonElement? LearnedSkills { get; init; }
    public JsonElement? SkillBindPresets { get; init; }
    public JsonElement? SkillTree { get; init; }
    public JsonElement? AccountProgress { get; init; }
}

public sealed class PlayerStateInventorySnapshot
{
    public Guid InventoryId { get; init; }
    public bool MetadataDirty { get; init; }
    public DateTime? ExpectedUpdatedAt { get; init; }
    public string? MetadataJson { get; init; }
    /// <summary>ロード時点の当該 inventory の有効 entry UUID 全集合。省略削除と並行追加を検出する。</summary>
    public IReadOnlyList<Guid> ExpectedEntryIds { get; init; } = [];
    public IReadOnlyList<PlayerStateInventoryEntrySnapshot> Entries { get; init; } = [];
}

public sealed class PlayerStateInventoryEntrySnapshot
{
    public Guid InventoryEntryId { get; init; }
    public DateTime? ExpectedUpdatedAt { get; init; }
    public int? SlotIndex { get; init; }
    public required string ItemCategory { get; init; }
    public string? ItemId { get; init; }
    public string? InstanceType { get; init; }
    public Guid? InstanceId { get; init; }
    public long Quantity { get; init; }
    public string? MetadataJson { get; init; }
}

public sealed class PlayerStateLoadoutSnapshot
{
    public Guid EquipmentLoadoutId { get; init; }
    public DateTime ExpectedUpdatedAt { get; init; }
    public IReadOnlyList<PlayerStateLoadoutSlotSnapshot> Slots { get; init; } = [];
}

public sealed class PlayerStateLoadoutSlotSnapshot
{
    public required string SlotType { get; init; }
    public int SlotIndex { get; init; }
    public Guid EquipmentInstanceId { get; init; }
}

public sealed class PlayerStateEquipmentSnapshot
{
    public Guid EquipmentInstanceId { get; init; }
    public DateTime ExpectedUpdatedAt { get; init; }
    public int EnhanceLevel { get; init; }
    public int RuneMaxSlots { get; init; }
    public int TranscendenceRank { get; init; }
    public int? DurabilityMax { get; init; }
    public int? DurabilityValue { get; init; }
    public IReadOnlyList<PlayerStateEquipmentEnchantSnapshot> Enchants { get; init; } = [];
    public IReadOnlyList<PlayerStateEquipmentRuneSnapshot> Runes { get; init; } = [];
}

public sealed class PlayerStateEquipmentEnchantSnapshot
{
    public Guid EnchantId { get; init; }
    public int SlotIndex { get; init; }
    public required string EnchantMasterId { get; init; }
    public required string EffectId { get; init; }
    public required string Status { get; init; }
    public required string Type { get; init; }
    public decimal Value { get; init; }
}

public sealed class PlayerStateEquipmentRuneSnapshot
{
    public Guid RuneId { get; init; }
    public int SlotIndex { get; init; }
    public required string ItemId { get; init; }
}

public sealed class PlayerStateLearnedSkillsSection
{
    public Guid AccountId { get; init; }
    public long ClientRevision { get; init; }
    public IReadOnlyList<PlayerStateLearnedSkillSnapshot> Skills { get; init; } = [];
    /// <summary>Full-list snapshot から除外して soft-delete する既存 skill と、その読込時 version。</summary>
    public IReadOnlyList<PlayerStateDeletedLearnedSkillSnapshot> DeletedSkills { get; init; } = [];
}

public sealed class PlayerStateDeletedLearnedSkillSnapshot
{
    public Guid LearnedSkillId { get; init; }
    public int ExpectedVersion { get; init; }
}

public sealed class PlayerStateLearnedSkillSnapshot
{
    public Guid LearnedSkillId { get; init; }
    public required string SkillId { get; init; }
    public int Level { get; init; }
    public int? ExpectedVersion { get; init; }
    public int? TargetVersion { get; init; }
    public IReadOnlyList<PlayerStateLearnedSkillSigilSnapshot> Sigils { get; init; } = [];
}

public sealed class PlayerStateLearnedSkillSigilSnapshot
{
    public Guid LearnedSkillSigilId { get; init; }
    public required string SigilId { get; init; }
    public required string EquipGroupId { get; init; }
    public int SlotIndex { get; init; }
}

public sealed class PlayerStateSkillBindPresetsSection
{
    public Guid AccountId { get; init; }
    public long ClientRevision { get; init; }
    public int SelectedPresetIndex { get; init; }
    public IReadOnlyList<PlayerStateSkillBindPresetSnapshot> Presets { get; init; } = [];
}

public sealed class PlayerStateSkillBindPresetSnapshot
{
    public int PresetIndex { get; init; }
    public IReadOnlyList<string?> ActiveSkillSlots { get; init; } = [];
    public string? LeftClickSkillId { get; init; }
    public IReadOnlyList<string?> PassiveSkillSlots { get; init; } = [];
    public int? ExpectedVersion { get; init; }
    public int? TargetVersion { get; init; }
}

public sealed class PlayerStateSkillTreeSection
{
    public Guid AccountId { get; init; }
    public long ClientRevision { get; init; }
    public int? ExpectedVersion { get; init; }
    public int? TargetVersion { get; init; }
    public IReadOnlyList<AccountSkillTreeUnlockedNodeModel> UnlockedNodes { get; init; } = [];
}

public sealed class PlayerStateAccountProgressSection
{
    public Guid AccountId { get; init; }
    public long ClientRevision { get; init; }
    public int ExpectedProgressVersion { get; init; }
    public int Level { get; init; }
    public long TotalExperience { get; init; }
    public required string ClassId { get; init; }
    public int ClassLevel { get; init; }
    public long ClassExperience { get; init; }
    public IReadOnlyList<AccountClassProgressUpdateRequest> ClassProgresses { get; init; } = [];
    /// <summary>
    /// mode が dirty の場合だけ送る。null はこの snapshot が mode を変更しないことを表す。
    /// </summary>
    public byte? Mode { get; init; }
}

/// <summary>ACK は state content を返さず、Plugin が pending snapshot の metadata だけを更新するための値に限る。</summary>
public sealed class PlayerStateSnapshotAck
{
    public Guid SnapshotId { get; init; }
    public Guid AccountId { get; init; }
    public IReadOnlyList<PlayerStateInventoryEntryAck> Entries { get; init; } = [];
    public IReadOnlyList<PlayerStateInventoryAck> Inventories { get; init; } = [];
    public IReadOnlyList<PlayerStateLoadoutAck> Loadouts { get; init; } = [];
    public IReadOnlyList<PlayerStateEquipmentAck> Equipment { get; init; } = [];
    public JsonElement? LearnedSkills { get; init; }
    public JsonElement? SkillBindPresets { get; init; }
    public JsonElement? SkillTree { get; init; }
    public JsonElement? AccountProgress { get; init; }
}

public sealed class PlayerStateInventoryEntryAck
{
    public Guid InventoryEntryId { get; init; }
    public DateTime UpdatedAt { get; init; }
    public bool IsDeleted { get; init; }
}

public sealed class PlayerStateInventoryAck
{
    public Guid InventoryId { get; init; }
    public DateTime UpdatedAt { get; init; }
}

public sealed class PlayerStateLoadoutAck
{
    public Guid EquipmentLoadoutId { get; init; }
    public DateTime UpdatedAt { get; init; }
}

public sealed class PlayerStateEquipmentAck
{
    public Guid EquipmentInstanceId { get; init; }
    public DateTime UpdatedAt { get; init; }
}

public enum PlayerStateSnapshotSaveFailure
{
    None,
    Invalid,
    AccountNotFound,
    Conflict,
    UnsupportedSection,
}

public sealed record PlayerStateSnapshotSaveResult(
    PlayerStateSnapshotAck? Ack,
    PlayerStateSnapshotSaveFailure Failure,
    string? Detail = null)
{
    public bool Succeeded => Failure == PlayerStateSnapshotSaveFailure.None && Ack is not null;
}
