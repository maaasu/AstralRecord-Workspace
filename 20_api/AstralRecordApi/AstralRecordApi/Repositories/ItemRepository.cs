using AstralRecordApi.Models;
using AstralRecordApi.Options;
using AstralRecordApi.Utilities;
using Microsoft.Extensions.Options;
using System.Collections;
using System.Text;
using YamlDotNet.Serialization;
using YamlDotNet.Serialization.NamingConventions;

namespace AstralRecordApi.Repositories;

public class ItemRepository : IItemRepository
{
    private static readonly StringComparer KeyComparer = StringComparer.OrdinalIgnoreCase;
    private static readonly HashSet<string> SupportedCategories = new(["material", "consumable", "equipment", "currency", "bundle", "rune"], KeyComparer);

    private readonly IReadOnlyDictionary<string, IReadOnlyDictionary<string, ItemResponse>> _itemsByCategory;
    private readonly IReadOnlyDictionary<string, ItemResponse> _itemsById;
    private readonly IReadOnlyList<ItemSummaryResponse> _itemSummaries;

    public ItemRepository(IOptions<FileDatabaseOptions> options, ILogger<ItemRepository> logger)
    {
        var rootPath = options.Value.RootPath;
        if (string.IsNullOrWhiteSpace(rootPath))
            throw new InvalidOperationException("FileDatabase:RootPath is not configured.");

        logger.LogInformation("アイテムデータの読み込みを開始します (RootPath: {RootPath})", rootPath);
        _itemsByCategory = LoadItems(rootPath, logger);
        _itemsById = _itemsByCategory
            .SelectMany(categoryItems => categoryItems.Value)
            .ToDictionary(kv => kv.Key, kv => kv.Value, KeyComparer);
        _itemSummaries = _itemsByCategory
            .SelectMany(categoryItems => categoryItems.Value.Values)
            .OrderBy(item => item.Category, KeyComparer)
            .ThenBy(item => item.Id, KeyComparer)
            .Select(item => new ItemSummaryResponse
            {
                Id = item.Id,
                Category = item.Category
            })
            .ToArray();
        logger.LogInformation("アイテムデータの読み込みが完了しました (件数: {Count})", _itemSummaries.Count);
    }

    public IReadOnlyList<ItemSummaryResponse> GetAllSummaries() => _itemSummaries;

    public ItemResponse? GetById(string itemId)
        => _itemsById.TryGetValue(itemId, out var item) ? item : null;

    private static IReadOnlyDictionary<string, IReadOnlyDictionary<string, ItemResponse>> LoadItems(string rootPath, ILogger logger)
    {
        var resolver = FileDatabaseConfigResolver.Load(rootPath);
        if (!resolver.TryGetDatabaseDirectory("item", out var itemRootPath))
            return new Dictionary<string, IReadOnlyDictionary<string, ItemResponse>>(KeyComparer);
        if (!Directory.Exists(itemRootPath))
            throw new DirectoryNotFoundException($"Item directory was not found: {itemRootPath}");

        var deserializer = new DeserializerBuilder()
            .WithNamingConvention(CamelCaseNamingConvention.Instance)
            .IgnoreUnmatchedProperties()
            .Build();

        var itemsByCategory = new Dictionary<string, IReadOnlyDictionary<string, ItemResponse>>(KeyComparer);
        var globalIds = new HashSet<string>(KeyComparer);

        foreach (var category in SupportedCategories)
        {
            var categoryPath = Path.Combine(itemRootPath, category);
            if (!Directory.Exists(categoryPath))
                throw new DirectoryNotFoundException($"Category directory was not found: {categoryPath}");

            var items = new Dictionary<string, ItemResponse>(KeyComparer);

            foreach (var filePath in Directory.EnumerateFiles(categoryPath, "*.yml", SearchOption.TopDirectoryOnly))
            {
                try
                {
                    var yaml = ReadYamlWithNormalizedAmpersandScalars(filePath, resolver, logger);
                    var yamlItem = deserializer.Deserialize<ItemYamlDocument>(yaml)
                        ?? throw new InvalidOperationException($"Failed to deserialize item YAML (null result): {filePath}");
                    var item = yamlItem.ToResponse(filePath, category);
                    if (!globalIds.Add(item.Id))
                    {
                        logger.LogWarning("重複するアイテム ID '{ItemId}' (カテゴリ: {Category}) をスキップします。ID はカテゴリを問わず一意である必要があります: {FilePath}", item.Id, category, filePath);
                        continue;
                    }
                    items[item.Id] = item;
                }
                catch (InvalidOperationException ex)
                {
                    logger.LogWarning("必須項目が不足しているためスキップします: {Message}", ex.Message);
                }
                catch (Exception ex)
                {
                    logger.LogWarning(ex, "アイテムファイルの読み込みに失敗しました。スキップします: {FilePath}", filePath);
                }
            }

            itemsByCategory[category] = items;
        }

        return itemsByCategory;
    }

    private static string ReadYamlWithNormalizedAmpersandScalars(string filePath, FileDatabaseConfigResolver resolver, ILogger logger)
    {
        var rawLines = File.ReadAllLines(filePath);
        var lines = NormalizeRefObjects(rawLines, resolver, filePath, logger);
        lines = YamlRangeNormalizer.NormalizeMinMaxObjects(lines);
        lines = YamlRangeNormalizer.NormalizeRandomRangeObjects(lines);
        var builder = new StringBuilder();

        foreach (var line in lines)
        {
            builder.AppendLine(NormalizeAmpersandScalar(line));
        }

        return builder.ToString();
    }

    /// <summary>
    /// YAML の参照オブジェクト形式（ref: type:id）をフラットなスカラーに正規化する。
    /// 例:
    ///   buffId:          buffId: cure_poison
    ///     ref: buff:cure_poison
    /// </summary>
    private static string[] NormalizeRefObjects(string[] lines, FileDatabaseConfigResolver resolver, string filePath, ILogger logger)
    {
        var result = new List<string>(lines.Length);
        for (int i = 0; i < lines.Length; i++)
        {
            var line = lines[i];
            var trimmed = line.TrimStart();

            // 値なしのキー行（例: "      buffId:"）を検出
            if (trimmed.EndsWith(':') && !trimmed.StartsWith('-') && !trimmed.StartsWith('#'))
            {
                // 次の行が ref パターン（例: "        ref: buff:cure_poison"）か確認
                if (i + 1 < lines.Length)
                {
                    var nextTrimmed = lines[i + 1].TrimStart();
                    if (nextTrimmed.StartsWith("ref: ", StringComparison.OrdinalIgnoreCase))
                    {
                        var refValue = nextTrimmed["ref: ".Length..].Trim();
                        if (!resolver.TryResolveReferenceId(refValue, out var id))
                        {
                            logger.LogWarning("ref の解決に失敗しました: ref={RefValue} (ファイル: {FilePath})", refValue, filePath);
                            result.Add(line);
                            continue;
                        }

                        var indent = line[..(line.Length - trimmed.Length)];
                        var key = trimmed.TrimEnd(':');
                        result.Add($"{indent}{key}: {id}");
                        i++; // ref 行をスキップ
                        continue;
                    }
                }
            }

            result.Add(line);
        }
        return result.ToArray();
    }

    private static string NormalizeAmpersandScalar(string line)
    {
        var commentIndex = line.IndexOf(" #", StringComparison.Ordinal);
        var content = commentIndex >= 0 ? line[..commentIndex] : line;
        var comment = commentIndex >= 0 ? line[commentIndex..] : string.Empty;

        var colonIndex = content.IndexOf(':');
        if (colonIndex >= 0)
        {
            var valuePart = content[(colonIndex + 1)..];
            var trimmedValue = valuePart.TrimStart();
            if (trimmedValue.StartsWith('&') && !trimmedValue.StartsWith("\"") && !trimmedValue.StartsWith("'"))
            {
                var leadingWhitespaceLength = valuePart.Length - trimmedValue.Length;
                var leadingWhitespace = valuePart[..leadingWhitespaceLength];
                var escaped = trimmedValue.Replace("\\", "\\\\").Replace("\"", "\\\"");
                return $"{content[..(colonIndex + 1)]}{leadingWhitespace}\"{escaped}\"{comment}";
            }
        }

        var trimmedContent = content.TrimStart();
        if (trimmedContent.StartsWith("- "))
        {
            var itemValue = trimmedContent[2..].TrimStart();
            if (itemValue.StartsWith('&') && !itemValue.StartsWith("\"") && !itemValue.StartsWith("'"))
            {
                var indentLength = content.Length - trimmedContent.Length;
                var indent = content[..indentLength];
                var afterDash = trimmedContent[2..];
                var leadingWhitespaceLength = afterDash.Length - itemValue.Length;
                var leadingWhitespace = afterDash[..leadingWhitespaceLength];
                var escaped = itemValue.Replace("\\", "\\\\").Replace("\"", "\\\"");
                return $"{indent}- {leadingWhitespace}\"{escaped}\"{comment}";
            }
        }

        return line;
    }

    private sealed class ItemYamlDocument
    {
        public int? SchemaVersion { get; init; }

        public string? Id { get; init; }

        public string? Category { get; init; }

        public string? Name { get; init; }

        public string? Icon { get; init; }

        public string? Rarity { get; init; }

        public int? SaleValue { get; init; }

        public int? CustomModelData { get; init; }

        public int? MaxStack { get; init; }

        public List<string>? Lore { get; init; }

        public bool? UnTradeable { get; init; }

        public bool? UnSellable { get; init; }

        public ConsumableYamlDocument? Consumable { get; init; }

        public EquipmentYamlDocument? Equipment { get; init; }

        public CurrencyYamlDocument? Currency { get; init; }

        public BundleYamlDocument? Bundle { get; init; }

        public RuneItemYamlDocument? Rune { get; init; }

        public ItemResponse ToResponse(string filePath, string expectedCategory)
        {
            if (SchemaVersion is null)
                throw new InvalidOperationException($"schemaVersion is required: {filePath}");

            return SchemaVersion.Value switch
            {
                1 => ToResponseV1(filePath, expectedCategory),
                _ => throw new InvalidOperationException($"Unsupported schemaVersion '{SchemaVersion.Value}': {filePath}")
            };
        }

        private ItemResponse ToResponseV1(string filePath, string expectedCategory)
        {
            if (string.IsNullOrWhiteSpace(Id))
                throw new InvalidOperationException($"id is required: {filePath}");
            if (!string.IsNullOrWhiteSpace(Category)
                && !string.Equals(Category, expectedCategory, StringComparison.OrdinalIgnoreCase))
                throw new InvalidOperationException($"category mismatch in {filePath}. expected '{expectedCategory}', actual '{Category}'.");
            if (string.IsNullOrWhiteSpace(Name))
                throw new InvalidOperationException($"name is required: {filePath}");
            if (string.IsNullOrWhiteSpace(Icon))
                throw new InvalidOperationException($"icon is required: {filePath}");
            if (string.IsNullOrWhiteSpace(Rarity))
                throw new InvalidOperationException($"rarity is required: {filePath}");

            return new ItemResponse
            {
                SchemaVersion = SchemaVersion!.Value,
                Id = Id,
                Category = Category ?? expectedCategory,
                Name = Name,
                Icon = Icon,
                Rarity = Rarity,
                SaleValue = SaleValue ?? 0,
                CustomModelData = CustomModelData,
                MaxStack = MaxStack ?? 64,
                Lore = Lore ?? [],
                UnTradeable = UnTradeable ?? false,
                UnSellable = UnSellable ?? false,
                Consumable = Consumable?.ToResponse(filePath, expectedCategory),
                Equipment = Equipment?.ToResponse(filePath, expectedCategory),
                Currency = Currency?.ToResponse(),
                Bundle = Bundle?.ToResponse(filePath, expectedCategory),
                Rune = Rune?.ToResponse(filePath, expectedCategory)
            };
        }
    }

    private sealed class ConsumableYamlDocument
    {
        public ConsumableOnUseYamlDocument? OnUse { get; init; }

        public List<ConsumableEffectYamlDocument>? Effects { get; init; }

        public ItemConsumableResponse ToResponse(string filePath, string expectedCategory)
        {
            if (!string.Equals(expectedCategory, "consumable", StringComparison.OrdinalIgnoreCase))
                throw new InvalidOperationException($"consumable section is only valid for category 'consumable': {filePath}");

            if (Effects is null || Effects.Count == 0)
                throw new InvalidOperationException($"consumable.effects is required: {filePath}");

            return new ItemConsumableResponse
            {
                OnUse = OnUse?.ToResponse(),
                Effects = Effects.Select(effect => effect.ToResponse(filePath)).ToArray()
            };
        }
    }

    private sealed class ConsumableOnUseYamlDocument
    {
        public string? Sound { get; init; }

        public string? Effect { get; init; }

        public int? Amount { get; init; }

        public ItemConsumableOnUseResponse ToResponse()
        {
            return new ItemConsumableOnUseResponse
            {
                Sound = Sound,
                Effect = Effect,
                Amount = Amount ?? 1
            };
        }
    }

    private sealed class ConsumableEffectYamlDocument
    {
        public string? Type { get; init; }

        public double? Rate { get; init; }

        public double? Value { get; init; }

        public string? Status { get; init; }

        public bool? IsPercent { get; init; }

        public string? BuffId { get; init; }

        public ItemConsumableEffectResponse ToResponse(string filePath)
        {
            if (string.IsNullOrWhiteSpace(Type))
                throw new InvalidOperationException($"consumable.effects[].type is required: {filePath}");

            return new ItemConsumableEffectResponse
            {
                Type = Type,
                Rate = Rate ?? 100,
                Value = Value,
                Status = Status,
                IsPercent = IsPercent ?? false,
                BuffId = BuffId
            };
        }
    }

    private sealed class EquipmentYamlDocument
    {
        public string? Slot { get; init; }

        public string? HandType { get; init; }

        public string? Tag { get; init; }

        public int? RequiredLevel { get; init; }

        public List<string>? RequiredClasses { get; init; }

        public string? SetId { get; init; }

        public List<EquipmentStatYamlDocument>? Stats { get; init; }

        public EquipmentDurabilityYamlDocument? Durability { get; init; }

        public EquipmentOnUseYamlDocument? OnUse { get; init; }

        public List<string>? Skills { get; init; }

        public EquipmentEnhanceYamlDocument? Enhance { get; init; }

        public EquipmentEnchantYamlDocument? Enchant { get; init; }

        public EquipmentRuneYamlDocument? Rune { get; init; }

        public List<EquipmentTranscendenceYamlDocument>? Transcendence { get; init; }

        public ItemEquipmentResponse ToResponse(string filePath, string expectedCategory)
        {
            if (string.IsNullOrWhiteSpace(expectedCategory)
                || !string.Equals(expectedCategory, "equipment", StringComparison.OrdinalIgnoreCase))
                throw new InvalidOperationException($"equipment section is only valid for category 'equipment': {filePath}");

            if (string.IsNullOrWhiteSpace(Slot))
                throw new InvalidOperationException($"equipment.slot is required: {filePath}");

            return new ItemEquipmentResponse
            {
                Slot = Slot,
                HandType = HandType ?? "ONE",
                Tag = Tag,
                RequiredLevel = RequiredLevel ?? 0,
                RequiredClasses = RequiredClasses ?? [],
                SetId = SetId,
                Stats = Stats?.Select(stat => stat.ToResponse(filePath)).ToList().AsReadOnly() ?? [],
                Durability = Durability?.ToResponse(),
                OnUse = OnUse?.ToResponse(),
                Skills = Skills ?? [],
                Enhance = Enhance?.ToResponse(filePath),
                Enchant = Enchant?.ToResponse(),
                Rune = Rune?.ToResponse(),
                Transcendence = Transcendence?.Select(t => t.ToResponse(filePath)).ToArray() ?? []
            };
        }
    }

    private sealed class EquipmentEnhanceYamlDocument
    {
        public int? MaxLevel { get; init; }

        public List<EquipmentEnhanceLevelYamlDocument>? Levels { get; init; }

        public ItemEquipmentEnhanceResponse ToResponse(string filePath)
        {
            if (MaxLevel is null)
                throw new InvalidOperationException($"equipment.enhance.maxLevel is required: {filePath}");

            return new ItemEquipmentEnhanceResponse
            {
                MaxLevel = MaxLevel.Value,
                Levels = Levels?.Select(l => l.ToResponse(filePath)).ToArray() ?? []
            };
        }
    }

    private sealed class EquipmentEnhanceLevelYamlDocument
    {
        public int? Level { get; init; }

        public List<EquipmentEnhanceStatIncreaseYamlDocument>? StatIncrease { get; init; }

        public int? DurabilityBonus { get; init; }

        public string? RecipeId { get; init; }

        public List<EquipmentEnhanceMaterialYamlDocument>? RequiredMaterials { get; init; }

        public int? RequiredCurrency { get; init; }

        public float? SuccessRate { get; init; }

        public string? FailAction { get; init; }

        public ItemEquipmentEnhanceLevelResponse ToResponse(string filePath)
        {
            if (Level is null)
                throw new InvalidOperationException($"equipment.enhance.levels[].level is required: {filePath}");

            return new ItemEquipmentEnhanceLevelResponse
            {
                Level = Level.Value,
                StatIncrease = StatIncrease?.Select(s => s.ToResponse(filePath)).ToArray() ?? [],
                DurabilityBonus = DurabilityBonus,
                RecipeId = RecipeId,
                RequiredMaterials = RequiredMaterials?.Select(m => m.ToResponse(filePath)).ToArray() ?? [],
                RequiredCurrency = RequiredCurrency,
                SuccessRate = SuccessRate ?? 1.0f,
                FailAction = FailAction ?? "NONE"
            };
        }
    }

    private sealed class EquipmentEnhanceStatIncreaseYamlDocument
    {
        public string? Status { get; init; }

        public string? Type { get; init; }

        public string? Value { get; init; }

        public ItemEquipmentEnhanceStatIncreaseResponse ToResponse(string filePath)
        {
            if (string.IsNullOrWhiteSpace(Status))
                throw new InvalidOperationException($"equipment.enhance.levels[].statIncrease[].status is required: {filePath}");
            if (string.IsNullOrWhiteSpace(Type))
                throw new InvalidOperationException($"equipment.enhance.levels[].statIncrease[].type is required: {filePath}");
            if (string.IsNullOrWhiteSpace(Value))
                throw new InvalidOperationException($"equipment.enhance.levels[].statIncrease[].value is required: {filePath}");

            return new ItemEquipmentEnhanceStatIncreaseResponse
            {
                Status = Status,
                Type = Type,
                Value = Value
            };
        }
    }

    private sealed class EquipmentEnhanceMaterialYamlDocument
    {
        public string? ItemId { get; init; }

        public int? Amount { get; init; }

        public ItemEquipmentEnhanceMaterialResponse ToResponse(string filePath)
        {
            if (string.IsNullOrWhiteSpace(ItemId))
                throw new InvalidOperationException($"equipment.enhance.levels[].requiredMaterials[].itemId is required: {filePath}");

            return new ItemEquipmentEnhanceMaterialResponse
            {
                ItemId = ItemId,
                Amount = Amount ?? 1
            };
        }
    }

    private sealed class EquipmentStatYamlDocument
    {
        public string? Status { get; init; }

        public string? Type { get; init; }

        public object? Value { get; init; }

        public ItemEquipmentStatResponse ToResponse(string filePath)
        {
            return new ItemEquipmentStatResponse
            {
                Status = Status,
                Type = Type,
                Value = EquipmentStatValueYamlDocument.Parse(Value, filePath)
            };
        }
    }

    private sealed class EquipmentStatValueYamlDocument
    {
        public static ItemEquipmentStatValueResponse? Parse(object? value, string filePath)
        {
            if (value is null)
                return null;

            if (value is string scalar)
                return FromScalar(scalar, filePath);

            if (value is IDictionary map)
            {
                var min = GetString(map, "min");
                var max = GetString(map, "max");

                if (string.IsNullOrWhiteSpace(min) || string.IsNullOrWhiteSpace(max))
                    throw new InvalidOperationException($"equipment.stats[].value.min and max are required when object syntax is used: {filePath}");

                return new ItemEquipmentStatValueResponse
                {
                    Min = min,
                    Max = max
                };
            }

            throw new InvalidOperationException($"equipment.stats[].value has unsupported format: {filePath}");
        }

        private static ItemEquipmentStatValueResponse FromScalar(string value, string filePath)
        {
            var trimmed = value.Trim();
            if (string.IsNullOrWhiteSpace(trimmed))
                throw new InvalidOperationException($"equipment.stats[].value must not be empty: {filePath}");

            var tildeIndex = trimmed.IndexOf('~');
            return tildeIndex >= 0
                ? new ItemEquipmentStatValueResponse
                {
                    Min = trimmed[..tildeIndex].Trim(),
                    Max = trimmed[(tildeIndex + 1)..].Trim()
                }
                : new ItemEquipmentStatValueResponse
                {
                    Min = trimmed,
                    Max = trimmed
                };
        }

        private static string? GetString(IDictionary map, string key)
        {
            foreach (DictionaryEntry entry in map)
            {
                if (string.Equals(entry.Key?.ToString(), key, StringComparison.OrdinalIgnoreCase))
                    return entry.Value?.ToString()?.Trim();
            }

            return null;
        }
    }

    private sealed class EquipmentDurabilityYamlDocument
    {
        public int? Max { get; init; }

        public int? Consume { get; init; }

        public ItemEquipmentDurabilityResponse ToResponse()
        {
            return new ItemEquipmentDurabilityResponse
            {
                Max = Max,
                Consume = Consume ?? 1
            };
        }
    }

    private sealed class EquipmentOnUseYamlDocument
    {
        public int? LeftClickCooldownTicks { get; init; }

        public string? LeftClickSkillId { get; init; }

        public int? RightClickCooldownTicks { get; init; }

        public string? RightClickSkillId { get; init; }

        public ItemEquipmentOnUseResponse ToResponse()
        {
            return new ItemEquipmentOnUseResponse
            {
                LeftClickCooldownTicks = LeftClickCooldownTicks,
                LeftClickSkillId = LeftClickSkillId,
                RightClickCooldownTicks = RightClickCooldownTicks,
                RightClickSkillId = RightClickSkillId
            };
        }
    }

    private sealed class CurrencyYamlDocument
    {
        public string? Type { get; init; }

        public string? Group { get; init; }

        public string? ExpiresAt { get; init; }

        public ItemCurrencyResponse ToResponse()
        {
            return new ItemCurrencyResponse
            {
                Type = Type,
                Group = Group,
                ExpiresAt = ExpiresAt
            };
        }
    }

    private sealed class BundleYamlDocument
    {
        public string? LootTableId { get; init; }

        public List<BundleItemYamlDocument>? Items { get; init; }

        public BundleOnUseYamlDocument? OnUse { get; init; }

        public ItemBundleResponse ToResponse(string filePath, string expectedCategory)
        {
            if (string.IsNullOrWhiteSpace(expectedCategory)
                || !string.Equals(expectedCategory, "bundle", StringComparison.OrdinalIgnoreCase))
                throw new InvalidOperationException($"bundle section is only valid for category 'bundle': {filePath}");

            return new ItemBundleResponse
            {
                LootTableId = LootTableId,
                Items = Items?.Select(i => i.ToResponse(filePath)).ToArray() ?? [],
                OnUse = OnUse?.ToResponse()
            };
        }
    }

    private sealed class BundleItemYamlDocument
    {
        public string? ItemId { get; init; }

        public string? Amount { get; init; }

        public double? Rate { get; init; }

        public bool? LuckAffected { get; init; }

        public bool? Hidden { get; init; }

        public ItemBundleItemResponse ToResponse(string filePath)
        {
            if (string.IsNullOrWhiteSpace(ItemId))
                throw new InvalidOperationException($"bundle.items[].itemId is required: {filePath}");

            return new ItemBundleItemResponse
            {
                ItemId = ItemId,
                Amount = Amount ?? "1",
                Rate = Rate ?? 100.0,
                LuckAffected = LuckAffected ?? false,
                Hidden = Hidden ?? false
            };
        }
    }

    private sealed class BundleOnUseYamlDocument
    {
        public string? Sound { get; init; }

        public string? Particle { get; init; }

        public ItemBundleOnUseResponse ToResponse()
        {
            return new ItemBundleOnUseResponse
            {
                Sound = Sound,
                Particle = Particle
            };
        }
    }

    private sealed class EquipmentEnchantYamlDocument
    {
        public int? MaxSlots { get; init; }

        public List<EquipmentEnchantPoolYamlDocument>? Pools { get; init; }

        public ItemEquipmentEnchantResponse ToResponse()
        {
            return new ItemEquipmentEnchantResponse
            {
                MaxSlots = MaxSlots ?? 1,
                Pools = Pools?.Select((p, index) => p.ToResponse(index)).ToArray() ?? []
            };
        }
    }

    private sealed class EquipmentEnchantPoolYamlDocument
    {
        public string? Id { get; init; }

        public string? RecipeId { get; init; }

        public EquipmentEnchantPoolMaterialYamlDocument? RequiredMaterial { get; init; }

        public int? RequiredCurrency { get; init; }

        public List<EquipmentEnchantEntryYamlDocument>? Entries { get; init; }

        public ItemEquipmentEnchantPoolResponse ToResponse(int poolIndex)
        {
            return new ItemEquipmentEnchantPoolResponse
            {
                PoolIndex = poolIndex,
                Id = Id,
                RecipeId = RecipeId,
                RequiredMaterial = RequiredMaterial?.ToResponse(),
                RequiredCurrency = RequiredCurrency ?? 0,
                Entries = Entries?.Select(e => e.ToResponse()).ToArray() ?? []
            };
        }
    }

    private sealed class EquipmentEnchantPoolMaterialYamlDocument
    {
        public string? ItemId { get; init; }

        public int? Amount { get; init; }

        public ItemEquipmentEnchantPoolMaterialResponse ToResponse()
        {
            return new ItemEquipmentEnchantPoolMaterialResponse
            {
                ItemId = ItemId ?? string.Empty,
                Amount = Amount ?? 1
            };
        }
    }

    private sealed class EquipmentEnchantEntryYamlDocument
    {
        public string? Status { get; init; }

        public string? Type { get; init; }

        public string? Value { get; init; }

        public int? Weight { get; init; }

        public ItemEquipmentEnchantEntryResponse ToResponse()
        {
            return new ItemEquipmentEnchantEntryResponse
            {
                Status = Status,
                Type = Type,
                Value = Value,
                Weight = Weight ?? 1
            };
        }
    }

    private sealed class EquipmentRuneYamlDocument
    {
        public object? MaxSlots { get; init; }

        public List<string>? AllowedRuneIds { get; init; }

        public ItemEquipmentRuneResponse ToResponse()
        {
            return new ItemEquipmentRuneResponse
            {
                MaxSlots = ParseMaxSlots(MaxSlots),
                AllowedRuneIds = AllowedRuneIds ?? []
            };
        }

        private static string ParseMaxSlots(object? value)
        {
            if (value is null)
                return "0";

            if (value is string scalar)
                return scalar.Trim();

            if (value is int intValue)
                return intValue.ToString();

            if (value is long longValue)
                return longValue.ToString();

            if (value is IDictionary map)
            {
                foreach (DictionaryEntry entry in map)
                {
                    if (string.Equals(entry.Key?.ToString(), "random", StringComparison.OrdinalIgnoreCase))
                        return entry.Value?.ToString()?.Trim() ?? "0";
                }
            }

            return value.ToString()?.Trim() ?? "0";
        }
    }

    private sealed class EquipmentTranscendenceYamlDocument
    {
        public string? Name { get; init; }

        public int? Rank { get; init; }

        public string? RecipeId { get; init; }

        public List<EquipmentEnhanceMaterialYamlDocument>? RequiredMaterials { get; init; }

        public int? RequiredCurrency { get; init; }

        public EquipmentTranscendenceOverridesYamlDocument? Overrides { get; init; }

        public ItemEquipmentTranscendenceResponse ToResponse(string filePath)
        {
            if (Rank is null)
                throw new InvalidOperationException($"equipment.transcendence[].rank is required: {filePath}");

            return new ItemEquipmentTranscendenceResponse
            {
                Name = Name,
                Rank = Rank.Value,
                RecipeId = RecipeId,
                RequiredMaterials = RequiredMaterials?.Select(m => m.ToResponse(filePath)).ToArray() ?? [],
                RequiredCurrency = RequiredCurrency ?? 0,
                Overrides = Overrides?.ToResponse()
            };
        }
    }

    private sealed class EquipmentTranscendenceOverridesYamlDocument
    {
        public string? Name { get; init; }

        public EquipmentTranscendenceOverridesEnhanceYamlDocument? Enhance { get; init; }

        public EquipmentTranscendenceOverridesEnchantYamlDocument? Enchant { get; init; }

        public EquipmentTranscendenceOverridesRuneYamlDocument? Rune { get; init; }

        public ItemEquipmentTranscendenceOverridesResponse ToResponse()
        {
            return new ItemEquipmentTranscendenceOverridesResponse
            {
                Name = Name,
                Enhance = Enhance?.ToResponse(),
                Enchant = Enchant?.ToResponse(),
                Rune = Rune?.ToResponse()
            };
        }
    }

    private sealed class EquipmentTranscendenceOverridesEnhanceYamlDocument
    {
        public int? MaxLevel { get; init; }

        public ItemEquipmentTranscendenceOverridesEnhanceResponse ToResponse()
            => new() { MaxLevel = MaxLevel ?? 0 };
    }

    private sealed class EquipmentTranscendenceOverridesEnchantYamlDocument
    {
        public int? MaxSlots { get; init; }

        public ItemEquipmentTranscendenceOverridesEnchantResponse ToResponse()
            => new() { MaxSlots = MaxSlots ?? 0 };
    }

    private sealed class EquipmentTranscendenceOverridesRuneYamlDocument
    {
        public string? MaxSlots { get; init; }

        public ItemEquipmentTranscendenceOverridesRuneResponse ToResponse()
            => new() { MaxSlots = MaxSlots ?? "0" };
    }

    private sealed class RuneItemYamlDocument
    {
        public List<string>? TargetSlots { get; init; }

        public int? RequiredEnhanceLevel { get; init; }

        public List<RuneStatYamlDocument>? Stats { get; init; }

        public List<string>? Skills { get; init; }

        public ItemRuneResponse ToResponse(string filePath, string expectedCategory)
        {
            if (string.IsNullOrWhiteSpace(expectedCategory)
                || !string.Equals(expectedCategory, "rune", StringComparison.OrdinalIgnoreCase))
                throw new InvalidOperationException($"rune section is only valid for category 'rune': {filePath}");

            if (TargetSlots is null || TargetSlots.Count == 0)
                throw new InvalidOperationException($"rune.targetSlots is required: {filePath}");

            return new ItemRuneResponse
            {
                TargetSlots = TargetSlots,
                RequiredEnhanceLevel = RequiredEnhanceLevel ?? 0,
                Stats = Stats?.Select(s => s.ToResponse()).ToArray() ?? [],
                Skills = Skills ?? []
            };
        }
    }

    private sealed class RuneStatYamlDocument
    {
        public string? Status { get; init; }

        public string? Type { get; init; }

        public string? Value { get; init; }

        public string? Random { get; init; }

        public ItemRuneStatResponse ToResponse()
        {
            return new ItemRuneStatResponse
            {
                Status = Status,
                Type = Type,
                Value = Value,
                Random = Random
            };
        }
    }
}