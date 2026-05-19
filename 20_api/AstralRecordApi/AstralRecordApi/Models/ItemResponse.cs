namespace AstralRecordApi.Models;

public class ItemResponse
{
    public required int SchemaVersion { get; init; }

    public required string Id { get; init; }

    public required string Category { get; init; }

    public required string Name { get; init; }

    public required string Icon { get; init; }

    public required string Rarity { get; init; }

    public int SaleValue { get; init; }

    public int? CustomModelData { get; init; }

    public int MaxStack { get; init; } = 64;

    public IReadOnlyList<string> Lore { get; init; } = [];

    public bool UnTradeable { get; init; }

    public bool UnSellable { get; init; }

    public ItemConsumableResponse? Consumable { get; init; }

    public ItemEquipmentResponse? Equipment { get; init; }

    public ItemCurrencyResponse? Currency { get; init; }

    public ItemBundleResponse? Bundle { get; init; }

    public ItemRuneResponse? Rune { get; init; }
}

public class ItemSummaryResponse
{
    public required string Id { get; init; }

    public required string Category { get; init; }
}

public class ItemConsumableResponse
{
    public ItemConsumableOnUseResponse? OnUse { get; init; }

    public IReadOnlyList<ItemConsumableEffectResponse> Effects { get; init; } = [];
}

public class ItemConsumableOnUseResponse
{
    public string? Sound { get; init; }

    public string? Effect { get; init; }

    public int Amount { get; init; } = 1;
}

public class ItemConsumableEffectResponse
{
    public required string Type { get; init; }

    public double Rate { get; init; } = 100;

    public double? Value { get; init; }

    public string? Status { get; init; }

    public bool IsPercent { get; init; }

    public string? BuffId { get; init; }
}

public class ItemEquipmentResponse
{
    public required string Slot { get; init; }

    public string HandType { get; init; } = "ONE";

    public string? Tag { get; init; }

    public int RequiredLevel { get; init; }

    public IReadOnlyList<string> RequiredClasses { get; init; } = [];

    public string? SetId { get; init; }

    public IReadOnlyList<ItemEquipmentStatResponse> Stats { get; init; } = [];

    public ItemEquipmentDurabilityResponse? Durability { get; init; }

    public ItemEquipmentOnUseResponse? OnUse { get; init; }

    public IReadOnlyList<string> Skills { get; init; } = [];

    public ItemEquipmentEnhanceResponse? Enhance { get; init; }

    public ItemEquipmentEnchantResponse? Enchant { get; init; }

    public ItemEquipmentRuneResponse? Rune { get; init; }

    public IReadOnlyList<ItemEquipmentTranscendenceResponse> Transcendence { get; init; } = [];
}

public class ItemEquipmentEnhanceResponse
{
    public int MaxLevel { get; init; }

    public IReadOnlyList<ItemEquipmentEnhanceLevelResponse> Levels { get; init; } = [];
}

public class ItemEquipmentEnhanceLevelResponse
{
    public int Level { get; init; }

    public IReadOnlyList<ItemEquipmentEnhanceStatIncreaseResponse> StatIncrease { get; init; } = [];

    public int? DurabilityBonus { get; init; }

    public string? RecipeId { get; init; }

    public IReadOnlyList<ItemEquipmentEnhanceMaterialResponse> RequiredMaterials { get; init; } = [];

    public int? RequiredCurrency { get; init; }

    public float SuccessRate { get; init; } = 1.0f;

    public string FailAction { get; init; } = "NONE";
}

public class ItemEquipmentEnhanceStatIncreaseResponse
{
    public required string Status { get; init; }

    public required string Type { get; init; }

    public required string Value { get; init; }
}

public class ItemEquipmentEnhanceMaterialResponse
{
    public required string ItemId { get; init; }

    public int Amount { get; init; }
}

public class ItemEquipmentStatResponse
{
    public string? Status { get; init; }

    public string? Type { get; init; }

    public ItemEquipmentStatValueResponse? Value { get; init; }
}

public class ItemEquipmentStatValueResponse
{
    public string Min { get; init; } = string.Empty;

    public string Max { get; init; } = string.Empty;
}

public class ItemEquipmentDurabilityResponse
{
    public int? Max { get; init; }

    public int Consume { get; init; } = 1;
}

public class ItemEquipmentEnchantResponse
{
    public int MaxSlots { get; init; } = 1;

    public IReadOnlyList<ItemEquipmentEnchantPoolResponse> Pools { get; init; } = [];
}

public class ItemEquipmentEnchantPoolResponse
{
    public int PoolIndex { get; init; }

    public string? Id { get; init; }

    public string? RecipeId { get; init; }

    public ItemEquipmentEnchantPoolMaterialResponse? RequiredMaterial { get; init; }

    public int RequiredCurrency { get; init; }

    public IReadOnlyList<ItemEquipmentEnchantEntryResponse> Entries { get; init; } = [];
}

public class ItemEquipmentEnchantPoolMaterialResponse
{
    public required string ItemId { get; init; }

    public int Amount { get; init; } = 1;
}

public class ItemEquipmentEnchantEntryResponse
{
    public string? Status { get; init; }

    public string? Type { get; init; }

    public string? Value { get; init; }

    public int Weight { get; init; } = 1;
}

public class ItemEquipmentRuneResponse
{
    public string MaxSlots { get; init; } = "0";

    public IReadOnlyList<string> AllowedRuneIds { get; init; } = [];
}

public class ItemEquipmentTranscendenceResponse
{
    public string? Name { get; init; }

    public int Rank { get; init; }

    public string? RecipeId { get; init; }

    public IReadOnlyList<ItemEquipmentEnhanceMaterialResponse> RequiredMaterials { get; init; } = [];

    public int RequiredCurrency { get; init; }

    public ItemEquipmentTranscendenceOverridesResponse? Overrides { get; init; }
}

public class ItemEquipmentTranscendenceOverridesResponse
{
    public string? Name { get; init; }

    public ItemEquipmentTranscendenceOverridesEnhanceResponse? Enhance { get; init; }

    public ItemEquipmentTranscendenceOverridesEnchantResponse? Enchant { get; init; }

    public ItemEquipmentTranscendenceOverridesRuneResponse? Rune { get; init; }
}

public class ItemEquipmentTranscendenceOverridesEnhanceResponse
{
    public int MaxLevel { get; init; }
}

public class ItemEquipmentTranscendenceOverridesEnchantResponse
{
    public int MaxSlots { get; init; }
}

public class ItemEquipmentTranscendenceOverridesRuneResponse
{
    public string MaxSlots { get; init; } = "0";
}

public class ItemRuneResponse
{
    public IReadOnlyList<string> TargetSlots { get; init; } = [];

    public int RequiredEnhanceLevel { get; init; }

    public IReadOnlyList<ItemRuneStatResponse> Stats { get; init; } = [];

    public IReadOnlyList<string> Skills { get; init; } = [];
}

public class ItemRuneStatResponse
{
    public string? Status { get; init; }

    public string? Type { get; init; }

    public string? Value { get; init; }

    public string? Random { get; init; }
}

public class ItemEquipmentOnUseResponse
{
    public int? LeftClickCooldownTicks { get; init; }

    public string? LeftClickSkillId { get; init; }

    public int? RightClickCooldownTicks { get; init; }

    public string? RightClickSkillId { get; init; }
}

public class ItemCurrencyResponse
{
    public string? Type { get; init; }

    public string? Group { get; init; }

    public string? ExpiresAt { get; init; }
}

public class ItemBundleResponse
{
    public string? LootTableId { get; init; }

    public IReadOnlyList<ItemBundleItemResponse> Items { get; init; } = [];

    public ItemBundleOnUseResponse? OnUse { get; init; }
}

public class ItemBundleItemResponse
{
    public required string ItemId { get; init; }

    public string Amount { get; init; } = "1";

    public double Rate { get; init; } = 100.0;

    public bool LuckAffected { get; init; }

    public bool Hidden { get; init; }
}

public class ItemBundleOnUseResponse
{
    public string? Sound { get; init; }

    public string? Particle { get; init; }
}