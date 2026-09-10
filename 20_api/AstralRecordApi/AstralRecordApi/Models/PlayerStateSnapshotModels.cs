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
    public JsonElement? Waystones { get; init; }
    public JsonElement? QuestState { get; init; }
    public JsonElement? LoginBonusClaims { get; init; }
    public JsonElement? GuideProgress { get; init; }
    public JsonElement? AdventureRecords { get; init; }
    public JsonElement? PlayerSettings { get; init; }
    public JsonElement? MailClaim { get; init; }
    public JsonElement? MailDelete { get; init; }
}

public sealed class PlayerStateInventorySnapshot
{
    public Guid InventoryId { get; init; }
    public bool IsNew { get; init; }
    public string? InventoryType { get; init; }
    public string? InventoryProfile { get; init; }
    public int? SlotCapacity { get; init; }
    /// <summary>新規 inventory の必須属性。既存 inventory では null。</summary>
    public bool? IsEnabled { get; init; }
    public bool MetadataDirty { get; init; }
    public DateTime? ExpectedUpdatedAt { get; init; }
    public string? MetadataJson { get; init; }
    /// <summary>ロード時点の当該 inventory の有効 entry 全集合と timestamp。省略削除、並行追加・更新を検出する。</summary>
    public IReadOnlyList<PlayerStateExpectedInventoryEntry> ExpectedEntries { get; init; } = [];
    /// <summary>この inventory を保存済み parent とする entry の明示削除です。各 ID は ExpectedEntries に含めます。</summary>
    public IReadOnlyList<Guid> DeletedEntryIds { get; init; } = [];
    public IReadOnlyList<PlayerStateInventoryEntrySnapshot> Entries { get; init; } = [];
}

public sealed class PlayerStateExpectedInventoryEntry
{
    public Guid InventoryEntryId { get; init; }
    public DateTime UpdatedAt { get; init; }
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
    public bool IsNew { get; init; }
    public string? LoadoutProfile { get; init; }
    public string? LoadoutName { get; init; }
    public int SortOrder { get; init; }
    /// <summary>新規 loadout の必須属性。既存 loadout では null。</summary>
    public bool? IsActive { get; init; }
    public string? MetadataJson { get; init; }
    public DateTime? ExpectedUpdatedAt { get; init; }
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
    /// <summary>true の場合は Plugin が確定した新規個体をこの snapshot transaction 内で作成する。</summary>
    public bool IsNew { get; init; }
    /// <summary>新規作成時の装備マスタ ID。既存更新では null。</summary>
    public string? ItemId { get; init; }
    /// <summary>既存更新時の楽観ロック値。新規作成では null。</summary>
    public DateTime? ExpectedUpdatedAt { get; init; }
    public int EnhanceLevel { get; init; }
    public int RuneMaxSlots { get; init; }
    public int TranscendenceRank { get; init; }
    public int? DurabilityMax { get; init; }
    public int? DurabilityValue { get; init; }
    public IReadOnlyList<PlayerStateEquipmentStatRollSnapshot> StatRolls { get; init; } = [];
    public IReadOnlyList<PlayerStateEquipmentEnchantSnapshot> Enchants { get; init; } = [];
    public IReadOnlyList<PlayerStateEquipmentRuneSnapshot> Runes { get; init; } = [];
}

public sealed class PlayerStateEquipmentStatRollSnapshot
{
    public Guid StatRollId { get; init; }
    public required string Status { get; init; }
    public required string Min { get; init; }
    public required string Max { get; init; }
    public int SortOrder { get; init; }
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

/// <summary>
/// Plugin がローカルで確定した開放済みウェイストーンを追記する section です。
/// 取消は扱わず、既存の開放 ID は no-op とします。
/// </summary>
public sealed class PlayerStateWaystonesSection
{
    public long ClientRevision { get; init; }
    public IReadOnlyList<string> UnlockedWaystoneIds { get; init; } = [];
}

/// <summary>クエスト進行全体を期待 version 付きで置換する section です。</summary>
public sealed class PlayerStateQuestStateSection
{
    public Guid AccountId { get; init; }
    public long ClientRevision { get; init; }
    public int ExpectedVersion { get; init; }
    public IReadOnlyList<AccountQuestActiveRequest> ActiveQuests { get; init; } = [];
    public IReadOnlyList<AccountQuestCompletionRequest> Completions { get; init; } = [];
    public IReadOnlyList<AccountQuestCooldownRequest> Cooldowns { get; init; } = [];
}

/// <summary>今回の完成状態と同時に確定するログインボーナス受取日です。</summary>
public sealed class PlayerStateLoginBonusClaimsSection
{
    public long ClientRevision { get; init; }
    public IReadOnlyList<DateOnly> ClaimDates { get; init; } = [];
}

/// <summary>完了済みガイド手順の追記、または読込済み完全集合との整合確認を行う section です。</summary>
public sealed class PlayerStateGuideProgressSection
{
    public Guid AccountId { get; init; }
    public long ClientRevision { get; init; }
    public bool IsFullSnapshot { get; init; }
    public IReadOnlyList<PlayerStateGuideStepKey> CompletedStepKeys { get; init; } = [];
}

public sealed class PlayerStateGuideStepKey
{
    public required string GuideId { get; init; }
    public required string StepId { get; init; }
}

/// <summary>今回の確定イベント数だけを加算する冒険記録 section です。</summary>
public sealed class PlayerStateAdventureRecordsSection
{
    public Guid AccountId { get; init; }
    public long ClientRevision { get; init; }
    public IReadOnlyList<PlayerStateMobDefeatDelta> MobDefeatDeltas { get; init; } = [];
    public IReadOnlyList<PlayerStateDungeonClearDelta> DungeonClearDeltas { get; init; } = [];
}

public sealed class PlayerStateMobDefeatDelta
{
    public required string MobId { get; init; }
    public required string MobCategory { get; init; }
    public long Delta { get; init; }
}

public sealed class PlayerStateDungeonClearDelta
{
    public required string DungeonId { get; init; }
    public long Delta { get; init; }
}

/// <summary>user 単位設定の完全 state。既存行は ExpectedVersion を必須とします。</summary>
public sealed class PlayerStatePlayerSettingsSection
{
    public Guid UserId { get; init; }
    public long ClientRevision { get; init; }
    public IReadOnlyList<PlayerStatePlayerSettingSnapshot> Settings { get; init; } = [];
}

public sealed class PlayerStatePlayerSettingSnapshot
{
    public Guid UserSettingId { get; init; }
    public required string SettingKey { get; init; }
    public required string SettingValueJson { get; init; }
    /// <summary>既存設定の読込時 version。新規設定では null。</summary>
    public int? ExpectedVersion { get; init; }
}

/// <summary>メール既読と添付報酬の完成状態を同じ transaction で確定する section です。</summary>
public sealed class PlayerStateMailClaimSection
{
    public Guid AccountId { get; init; }
    public Guid ClientRevision { get; init; }
    public required string MailId { get; init; }
}

/// <summary>メール削除状態を完成スナップショットと同じ transaction で確定する section です。</summary>
public sealed class PlayerStateMailDeleteSection
{
    public Guid AccountId { get; init; }
    public Guid ClientRevision { get; init; }
    public required string MailId { get; init; }
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
    public JsonElement? Waystones { get; init; }
    public JsonElement? QuestState { get; init; }
    public JsonElement? LoginBonusClaims { get; init; }
    public JsonElement? GuideProgress { get; init; }
    public JsonElement? AdventureRecords { get; init; }
    public JsonElement? PlayerSettings { get; init; }
    public JsonElement? MailClaim { get; init; }
    public JsonElement? MailDelete { get; init; }
}

/// <summary>通信結果が失われた Plugin が、冪等 snapshot の確定結果を照会する応答です。</summary>
public sealed class PlayerStateSnapshotStatusResponse
{
    public required string Status { get; init; }
    public required PlayerStateSnapshotAck Ack { get; init; }
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
