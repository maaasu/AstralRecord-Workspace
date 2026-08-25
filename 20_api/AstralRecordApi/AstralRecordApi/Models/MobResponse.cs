using System.Text.Json;
using System.Text.Json.Serialization;

namespace AstralRecordApi.Models;

/// <summary>
/// Mob 詳細レスポンス。
/// MasterDataDB の <c>master_type IN ('mob.boss', 'mob.enemy', 'mob.npc')</c> の <c>payload_json</c> を
/// このモデルへデシリアライズして返却する。
/// </summary>
public class MobResponse
{
    public required int SchemaVersion { get; init; }

    public required string Id { get; init; }

    public required string Type { get; init; }

    public required string Category { get; init; }

    public required string Name { get; init; }

    public string? Title { get; init; }

    public int Level { get; init; }

    /// <summary>
    /// 同一 Mob マスタ内のレベルプロファイル。各要素は共通定義に対する部分上書きです。
    /// JsonElement のまま保持し、未指定項目を API の再シリアライズで補完しないようにします。
    /// </summary>
    public IReadOnlyList<JsonElement> Levels { get; init; } = [];

    public required string EntityType { get; init; }

    public bool NameVisible { get; init; } = true;

    /// <summary>
    /// NPC スキーマでは推奨項目ですが任意項目です。未指定時はクライアント側のカテゴリ既定値を維持します。
    /// </summary>
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public bool? DamageImmune { get; init; }

    public string? Icon { get; init; }

    public IReadOnlyList<string> Lore { get; init; } = [];

    public IReadOnlyList<string> Tags { get; init; } = [];

    public MobSkinResponse? Skin { get; init; }

    public MobVariantResponse? Variant { get; init; }

    public MobEquipmentResponse? Equipment { get; init; }

    public IReadOnlyList<MobBaseStatResponse> BaseStats { get; init; } = [];

    public MobShieldResponse? Shield { get; init; }

    public MobAiResponse? Ai { get; init; }

    public MobInteractionsResponse? Interactions { get; init; }

    public MobDropsResponse? Drops { get; init; }

    public MobChallengeResponse? Challenge { get; init; }
}

/// <summary>Mob 一覧レスポンス要素。主要項目のみ。</summary>
public class MobSummaryResponse
{
    public required string Id { get; init; }

    public required string Category { get; init; }

    public required string Name { get; init; }

    public int Level { get; init; }

    public required string EntityType { get; init; }

    public string? Icon { get; init; }

    public IReadOnlyList<string> Tags { get; init; } = [];
}

/// <summary>Mob スキン設定。</summary>
public class MobSkinResponse
{
    public string? Texture { get; init; }

    public string? Signature { get; init; }
}

/// <summary>Mob のバニラ外見差分を固定する設定。</summary>
public class MobVariantResponse
{
    public string? Age { get; init; }

    public string? Kind { get; init; }

    public string? Color { get; init; }

    public string? Style { get; init; }

    public string? Profession { get; init; }

    public string? VillagerType { get; init; }

    public int? VillagerLevel { get; init; }

    public string? Pattern { get; init; }

    public string? BodyColor { get; init; }

    public string? PatternColor { get; init; }

    public string? MainGene { get; init; }

    public string? HiddenGene { get; init; }
}

/// <summary>Mob 装備設定（表示のみ。ダメージ計算には影響しない）。</summary>
public class MobEquipmentResponse
{
    public string? MainHand { get; init; }

    public string? OffHand { get; init; }

    public string? Helmet { get; init; }

    public string? Chestplate { get; init; }

    public string? Leggings { get; init; }

    public string? Boots { get; init; }
}

/// <summary>Mob のステータス値（独自 StatusType ベース）。</summary>
public class MobBaseStatResponse
{
    public required string Status { get; init; }

    public required double Value { get; init; }
}

/// <summary>Mob のシールド設定。</summary>
public class MobShieldResponse
{
    public bool Enabled { get; init; }

    public double Max { get; init; }

    public double? RechargeTimeSeconds { get; init; }

    public double? RechargeAmount { get; init; }
}

/// <summary>Mob の AI 設定。</summary>
public class MobAiResponse
{
    public MobIdleResponse? Idle { get; init; }

    public MobTargetingResponse? Targeting { get; init; }

    public MobCombatResponse? Combat { get; init; }
}

/// <summary>Mob の待機行動設定。</summary>
public class MobIdleResponse
{
    public required string Behavior { get; init; }

    public double WanderRadius { get; init; } = 10.0;

    public double Speed { get; init; } = 1.0;
}

/// <summary>Mob のターゲット選定設定。</summary>
public class MobTargetingResponse
{
    public required string Strategy { get; init; }

    public required double AggroRange { get; init; }

    public double? DeaggroRange { get; init; }

    public double LeashRange { get; init; } = 30.0;

    public bool RetaliateOnly { get; init; }
}

/// <summary>Mob の戦闘設定。</summary>
public class MobCombatResponse
{
    public required string Style { get; init; }

    public double PreferredRange { get; init; } = 1.0;

    /// <summary>
    /// 通常攻撃設定。マスタに定義された Mob だけが通常攻撃を持つ。
    /// </summary>
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public MobNormalAttackResponse? NormalAttack { get; init; }

    /// <summary>
    /// 旧マスタの直接指定形式。未定義時に既定値を補完せず、通常攻撃を追加しない。
    /// </summary>
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public long? AttackIntervalTicks { get; init; }

    public IReadOnlyList<MobSkillBindingResponse> Skills { get; init; } = [];
}

/// <summary>Mob の通常攻撃設定。</summary>
public class MobNormalAttackResponse
{
    public required double Range { get; init; }

    public required long IntervalTicks { get; init; }
}

/// <summary>Mob 専用スキルの発動設定。</summary>
public class MobSkillBindingResponse
{
    public required string Id { get; init; }

    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public double? ActivationRange { get; init; }

    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public long? CooldownTicks { get; init; }

    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public long? CastTimeTicks { get; init; }

    public IReadOnlyDictionary<string, double> Params { get; init; } = new Dictionary<string, double>();
}

/// <summary>NPC のクリックインタラクション設定。</summary>
public class MobInteractionsResponse
{
    public IReadOnlyList<MobInteractionActionResponse> LeftClick { get; init; } = [];

    public IReadOnlyList<MobInteractionActionResponse> RightClick { get; init; } = [];
}

/// <summary>NPC クリック時に実行するアクション 1 件。</summary>
public class MobInteractionActionResponse
{
    public required string Id { get; init; }

    public Dictionary<string, string> Params { get; init; } = [];
}

/// <summary>Mob のドロップ設定。</summary>
public class MobDropsResponse
{
    public int Exp { get; init; }

    public MobMoneyDropResponse? Money { get; init; }

    public IReadOnlyList<MobDropItemResponse> Items { get; init; } = [];

    public string? LootTable { get; init; }
}

/// <summary>Mob の金銭ドロップ範囲。</summary>
public class MobMoneyDropResponse
{
    public int Min { get; init; }

    public int Max { get; init; }
}

/// <summary>Mob のドロップアイテム 1 エントリ。</summary>
public class MobDropItemResponse
{
    public required string ItemId { get; init; }

    public required double Rate { get; init; }

    public string Amount { get; init; } = "1";

    public bool LuckAffected { get; init; } = true;

    public bool Hidden { get; init; }
}

/// <summary>Boss challenge settings attached to a BOSS mob.</summary>
public class MobChallengeResponse
{
    public required string FieldWorldId { get; init; }

    public required MobChallengeLocationResponse EntryLocation { get; init; }

    public double EntryRadius { get; init; } = 3.0D;

    public required MobChallengeLocationResponse PlayerSpawnLocation { get; init; }

    public required MobChallengeLocationResponse BossSpawnLocation { get; init; }

    public int PartyMin { get; init; } = 1;

    public int PartyMax { get; init; } = 6;

    public long TimeLimitSeconds { get; init; } = 600;

    public int DeathLimit { get; init; } = 5;

    public long ReviveDelaySeconds { get; init; } = 5;

    public MobChallengeScalingResponse? Scaling { get; init; }
}

/// <summary>Boss challenge location definition.</summary>
public class MobChallengeLocationResponse
{
    public string? WorldId { get; init; }

    public required double X { get; init; }

    public required double Y { get; init; }

    public required double Z { get; init; }

    public double Yaw { get; init; }

    public double Pitch { get; init; }
}

/// <summary>Boss challenge participant scaling settings.</summary>
public class MobChallengeScalingResponse
{
    public bool Enabled { get; init; }

    public double HealthPerExtraPlayer { get; init; }

    public double AttackPerExtraPlayer { get; init; }
}
