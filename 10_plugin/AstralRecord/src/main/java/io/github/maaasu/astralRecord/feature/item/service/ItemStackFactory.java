package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.feature.buff.model.BuffType;
import io.github.maaasu.astralRecord.feature.buff.repository.BuffRepository;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentEnchant;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentRune;
import io.github.maaasu.astralRecord.feature.item.model.ItemConsumable;
import io.github.maaasu.astralRecord.feature.item.model.ItemConsumableEffect;
import io.github.maaasu.astralRecord.feature.item.model.ItemConsumableEffectType;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipment;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentEnhanceStatIncrease;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentHandType;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentSlot;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentStat;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentStatType;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentTranscendence;
import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.model.ItemRarity;
import io.github.maaasu.astralRecord.feature.item.model.RuneInstance;
import io.github.maaasu.astralRecord.feature.loot.model.LootEntry;
import io.github.maaasu.astralRecord.feature.loot.model.LootModel;
import io.github.maaasu.astralRecord.feature.loot.service.LootService;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.service.SkillPresentationUtil;
import io.github.maaasu.astralRecord.feature.skill.service.SkillService;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import io.github.maaasu.astralRecord.infrastructure.util.CustomModelDataComponentUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link ItemModel} からサーバ側 {@link ItemStack} を生成するファクトリ。
 * <p>
 * サーバ側の base Material は常に {@link Material#PAPER} とし、
 * プレイヤーへの見た目（icon）は {@link PersistentDataContainer} に埋め込んで
 * パケットアダプタ（ItemStackPacketAdapter）がパケット書き換えで適用します。
 * <p>
 * 同一 {@link ItemModel} から何度も ItemStack を生成するケースを想定し、
 * テンプレート（プロトタイプ）を {@link ConcurrentHashMap} でキャッシュします。
 * {@link #create} は clone + 個数セットのみで済むため軽量です。
 */
public class ItemStackFactory {

    /** サーバ側ベースマテリアル */
    private static final Material BASE_MATERIAL = Material.PAPER;

    /** レガシーカラーコード（§）→ Adventure Component 変換用シリアライザ */
    private static final LegacyComponentSerializer LEGACY_SERIALIZER =
            LegacyComponentSerializer.legacySection();

    /** PDC キー: AstralRecord アイテムID */
    private static final NamespacedKey KEY_ITEM_ID =
            new NamespacedKey("astralrecord", "item_id");

    /** PDC キー: プレイヤーへ表示する icon Material 名 */
    private static final NamespacedKey KEY_ICON =
            new NamespacedKey("astralrecord", "icon");

    /** PDC キー: カスタムモデルデータ */
    private static final NamespacedKey KEY_CUSTOM_MODEL_DATA =
            new NamespacedKey("astralrecord", "custom_model_data");

    /** PDC キー: バニラ外見色 */
    private static final NamespacedKey KEY_APPEARANCE_COLOR =
            new NamespacedKey("astralrecord", "appearance_color");

    /** PDC キー: ポーション種別 */
    private static final NamespacedKey KEY_POTION_TYPE =
            new NamespacedKey("astralrecord", "potion_type");

    /** PDC キー: カテゴリ */
    private static final NamespacedKey KEY_CATEGORY =
            new NamespacedKey("astralrecord", "category");

    /** PDC キー: レアリティ */
    private static final NamespacedKey KEY_RARITY =
            new NamespacedKey("astralrecord", "rarity");

    /** PDC キー: 装備インスタンス ID */
    private static final NamespacedKey KEY_EQUIPMENT_INSTANCE_ID =
            new NamespacedKey("astralrecord", "equipment_instance_id");

    private static final NamespacedKey KEY_DURABILITY_MAX =
            new NamespacedKey("astralrecord", "durability_max");

    private static final NamespacedKey KEY_DURABILITY_VALUE =
            new NamespacedKey("astralrecord", "durability_value");

    /** PDC キー: ルーンインスタンス ID */
    private static final NamespacedKey KEY_RUNE_INSTANCE_ID =
            new NamespacedKey("astralrecord", "rune_instance_id");

    /** テンプレートキャッシュ (category:id → プロトタイプ ItemStack) */
    private final Map<String, ItemStack> templateCache = new ConcurrentHashMap<>();

    /** 表示名/Loreは維持しつつ、バニラ表示の抑制対象から除外する ItemFlag 名 */
    private static final Set<String> ITEM_FLAG_EXCLUSIONS = Set.of(
            "HIDE_CUSTOM_NAME",
            "HIDE_ITEM_NAME",
            "HIDE_LORE"
    );

    /** バニラ由来表示を抑制するために適用する ItemFlag 一式（起動時解決） */
    private static final ItemFlag[] VANILLA_HIDE_FLAGS = resolveVanillaHideFlags();

    private static final Set<String> HIDDEN_EQUIPMENT_SKILL_IDS = Set.of(
            BuiltInWeaponAttackDefinitions.NORMAL_ATTACK_MELEE,
            BuiltInWeaponAttackDefinitions.NORMAL_ATTACK_BOW,
            BuiltInWeaponAttackDefinitions.NORMAL_ATTACK_MAGIC,
            BuiltInWeaponAttackDefinitions.SPECIAL_ATTACK_MELEE,
            BuiltInWeaponAttackDefinitions.SPECIAL_ATTACK_BOW,
            BuiltInWeaponAttackDefinitions.SPECIAL_ATTACK_MAGIC
    );

    private static final String STATUS_VALUE_COLOR = ColorCodeUtil.WHITE + ColorCodeUtil.BOLD;
    private static final int DURABILITY_BAR_LENGTH = 20;
    private static final String DURABILITY_BAR_CHAR = "|";

    /** ルートテーブル参照用（nullable: 未初期化時は Lore に含めない） */
    private final LootService lootService;

    /** ルート内アイテム名の日本語表示解決に使用します。 */
    private final ItemService itemService;
    private final BuffRepository buffRepository = new BuffRepository();
    private final Map<String, String> buffDisplayNameCache = new ConcurrentHashMap<>();
    private SkillService skillService;

    /**
     * ItemStackFactory を初期化します。
     *
     * @param lootService ルートテーブルサービス（bundle の lootTableId 解決に使用）
     * @param itemService アイテムサービス（bundle のルート表示名解決に使用）
     */
    public ItemStackFactory(@NotNull LootService lootService, @NotNull ItemService itemService) {
        this.lootService = lootService;
        this.itemService = itemService;
    }

    /**
     * 装備 lore へスキル定義情報を表示するための参照を設定します。
     *
     * @param skillService 起動後に初期化されたスキルサービス
     */
    public void setSkillService(@NotNull SkillService skillService) {
        this.skillService = skillService;
    }

    // region --- public API ---

    /**
     * {@link ItemModel} から ItemStack を 1 個生成します。
     *
     * @param model アイテム定義
     * @return 生成された ItemStack（サーバ側は PAPER）
     */
    public @NotNull ItemStack create(@NotNull ItemModel model) {
        return create(model, 1);
    }

    /**
     * {@link ItemModel} から指定個数の ItemStack を生成します。
     *
     * @param model  アイテム定義
     * @param amount 個数（1 ～ maxStack）
     * @return 生成された ItemStack（サーバ側は PAPER）
     */
    public @NotNull ItemStack create(@NotNull ItemModel model, int amount) {
        String key = cacheKey(model);
        ItemStack template = templateCache.computeIfAbsent(key, k -> buildTemplate(model));
        ItemStack item = template.clone();
        item.setAmount(Math.clamp(amount, 1, model.getMaxStack()));
        return item;
    }

    /**
     * {@link ItemModel} から ItemDisplay やドロップ実体の表示に使う ItemStack を生成します。
     * サーバ内部用の PDC は維持しつつ、Material は icon に差し替えます。
     *
     * @param model  アイテム定義
     * @param amount 表示個数
     * @return icon Material を反映した表示専用 ItemStack
     */
    public @NotNull ItemStack createDisplay(@NotNull ItemModel model, int amount) {
        return asDisplayStack(create(model, amount));
    }

    public @NotNull ItemStack createShopDisplay(@NotNull ItemModel model, int amount) {
        return createDisplay(model, amount);
    }

    /**
     * AstralRecord ItemStack を表示専用に icon Material へ差し替えます。
     * icon が未設定または解決不能な場合は、元スタックの clone を返します。
     *
     * @param item 変換元 ItemStack
     * @return icon Material を反映した ItemStack
     */
    public @NotNull ItemStack asDisplayStack(@NotNull ItemStack item) {
        String iconName = getIconName(item);
        if (iconName == null || iconName.isBlank()) {
            return item.clone();
        }

        Material iconMaterial = resolveIconMaterial(iconName);
        ItemStack replaced = iconMaterial == null || iconMaterial == item.getType()
                ? item.clone()
                : item.withType(iconMaterial);
        applyAppearance(replaced);
        applyDurabilityVisual(replaced);
        return replaced;
    }

    public static boolean applyDurabilityVisual(@NotNull ItemStack item) {
        if (!item.hasItemMeta() || item.getType().getMaxDurability() <= 0) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (!(meta instanceof Damageable damageable)) {
            return false;
        }
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        Integer max = pdc.get(KEY_DURABILITY_MAX, PersistentDataType.INTEGER);
        Integer value = pdc.get(KEY_DURABILITY_VALUE, PersistentDataType.INTEGER);
        if (max == null || value == null || max <= 0) {
            return false;
        }
        double remainingRate = Math.clamp((double) value / max, 0.0D, 1.0D);
        int visualMax = Math.max(1, item.getType().getMaxDurability());
        int visualDamage = (int) Math.round(visualMax * (1.0D - remainingRate));
        damageable.setDamage(Math.clamp(visualDamage, 0, Math.max(0, visualMax - 1)));
        meta.setUnbreakable(true);
        meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE);
        item.setItemMeta(meta);
        return true;
    }

    /**
     * {@link ItemModel} と {@link EquipmentInstance} から ItemStack を生成します。
     * インスタンス固有のステータスロール値を Lore に反映します。キャッシュは使用しません。
     *
     * @param model    アイテムマスタ定義
     * @param instance 装備インスタンス
     * @param amount   個数
     * @return 生成された ItemStack
     */
    public @NotNull ItemStack create(@NotNull ItemModel model, @NotNull EquipmentInstance instance, int amount) {
        var item = new ItemStack(BASE_MATERIAL, 1);
        var meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }

        var rarityColor = rarityToColor(model.getRarity());

        // --- アイテム名: transcendence オーバーライド → enhance +N サフィックス ---
        String baseName = resolveEquipmentDisplayName(model, instance);
        var decoratedName = ColorCodeUtil.toLegacyText(baseName, model.getId());
        boolean broken = isBroken(instance);
        String visibleName = broken
                ? ColorCodeUtil.DARK_RED + "[破損] " + rarityColor + decoratedName
                : rarityColor + decoratedName;
        String enhanceSuffix = instance.getEnhanceLevel() > 0
                ? " §f+" + instance.getEnhanceLevel()
                : "";
        meta.displayName(LEGACY_SERIALIZER.deserialize(
                visibleName + enhanceSuffix + ColorCodeUtil.RESET));

        var loreStrings = buildLoreForEquipmentInstance(model, instance);
        meta.lore(loreStrings.stream()
                .map(ColorCodeUtil::translateAlternateColorCodes)
                .map(LEGACY_SERIALIZER::deserialize)
                .map(c -> (Component) c)
                .toList());

        if (model.getCustomModelData() != null) {
            applyCustomModelData(meta, model.getCustomModelData());
        }
        applyVanillaHideFlags(meta);

        // enchant がある場合はバニラエンチャントの輝きを付与（エンチャント名はHIDE_ENCHANTSで非表示）
        if (!instance.getEnchants().isEmpty()) {
            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
        }

        writeCommonPersistentData(meta.getPersistentDataContainer(), model);
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(KEY_EQUIPMENT_INSTANCE_ID, PersistentDataType.STRING, instance.getEquipmentInstanceId());
        pdc.set(KEY_DURABILITY_MAX, PersistentDataType.INTEGER, instance.getDurabilityMax());
        pdc.set(KEY_DURABILITY_VALUE, PersistentDataType.INTEGER, instance.getDurabilityValue());

        item.setItemMeta(meta);
        item.setAmount(Math.clamp(amount, 1, model.getMaxStack()));
        //Logger.log(LogId.D_5211, model.getCategory(), model.getId());
        return item;
    }

    /**
     * {@link ItemModel} と {@link RuneInstance} から ItemStack を生成します。
     * インスタンス固有のステータス確定値を Lore に反映します。キャッシュは使用しません。
     *
     * @param model    アイテムマスタ定義
     * @param instance ルーンインスタンス
     * @param amount   個数
     * @return 生成された ItemStack
     */
    public @NotNull ItemStack create(@NotNull ItemModel model, @NotNull RuneInstance instance, int amount) {
        var item = new ItemStack(BASE_MATERIAL, 1);
        var meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }

        var rarityColor = rarityToColor(model.getRarity());
        var decoratedName = ColorCodeUtil.toLegacyText(model.getName(), model.getId());
        meta.displayName(LEGACY_SERIALIZER.deserialize(rarityColor + decoratedName + ColorCodeUtil.RESET));

        var loreStrings = buildLoreForRuneInstance(model, instance);
        meta.lore(loreStrings.stream()
                .map(ColorCodeUtil::translateAlternateColorCodes)
                .map(LEGACY_SERIALIZER::deserialize)
                .map(c -> (Component) c)
                .toList());

        if (model.getCustomModelData() != null) {
            applyCustomModelData(meta, model.getCustomModelData());
        }
        applyVanillaHideFlags(meta);

        writeCommonPersistentData(meta.getPersistentDataContainer(), model);
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(KEY_RUNE_INSTANCE_ID, PersistentDataType.STRING, instance.getRuneInstanceId());

        item.setItemMeta(meta);
        item.setAmount(Math.clamp(amount, 1, model.getMaxStack()));
        //Logger.log(LogId.D_5211, model.getCategory(), model.getId());
        return item;
    }

    /**
     * テンプレートキャッシュをクリアします。
     * アイテム定義のリロード時に呼び出してください。
     */
    public void clearCache() {
        templateCache.clear();
        Logger.log(LogId.D_5210);
    }

    /**
     * キャッシュ済みテンプレート数を返します。
     *
     * @return キャッシュ数
     */
    public int cacheSize() {
        return templateCache.size();
    }

    // endregion

    // region --- PDC 読み取りユーティリティ (PacketAdapter 用) ---

    /**
     * ItemStack に埋め込まれた AstralRecord アイテムIDを取得します。
     *
     * @param item 判定対象
     * @return アイテムID。AstralRecord アイテムでなければ {@code null}
     */
    public static @Nullable String getAstralItemId(@NotNull ItemStack item) {
        if (!item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer()
                .get(KEY_ITEM_ID, PersistentDataType.STRING);
    }

    /**
     * ItemStack に埋め込まれた icon Material 名を取得します。
     *
     * @param item 判定対象
     * @return icon 名。未設定なら {@code null}
     */
    public static @Nullable String getIconName(@NotNull ItemStack item) {
        if (!item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer()
                .get(KEY_ICON, PersistentDataType.STRING);
    }

    /**
     * ItemStack に埋め込まれた customModelData を取得します。
     *
     * @param item 判定対象
     * @return customModelData。未設定なら {@code null}
     */
    public static @Nullable Integer getCustomModelData(@NotNull ItemStack item) {
        if (!item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer()
                .get(KEY_CUSTOM_MODEL_DATA, PersistentDataType.INTEGER);
    }

    /**
     * ItemStack に埋め込まれた外見色を取得します。
     *
     * @param item 判定対象
     * @return 色指定。未設定なら {@code null}
     */
    public static @Nullable String getAppearanceColor(@NotNull ItemStack item) {
        if (!item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer()
                .get(KEY_APPEARANCE_COLOR, PersistentDataType.STRING);
    }

    /**
     * ItemStack に埋め込まれたポーション種別を取得します。
     *
     * @param item 判定対象
     * @return PotionType 名。未設定なら {@code null}
     */
    public static @Nullable String getPotionType(@NotNull ItemStack item) {
        if (!item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer()
                .get(KEY_POTION_TYPE, PersistentDataType.STRING);
    }

    /**
     * 表示用 ItemStack にバニラ外見差分を適用します。
     *
     * @param item 適用対象 ItemStack
     * @return ItemMeta を更新した場合は {@code true}
     */
    public static boolean applyAppearance(@NotNull ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }

        String colorText = meta.getPersistentDataContainer()
                .get(KEY_APPEARANCE_COLOR, PersistentDataType.STRING);
        String potionTypeText = meta.getPersistentDataContainer()
                .get(KEY_POTION_TYPE, PersistentDataType.STRING);
        boolean modified = false;

        Color color = parseColor(colorText);
        if (color != null && meta instanceof LeatherArmorMeta leatherArmorMeta) {
            leatherArmorMeta.setColor(color);
            modified = true;
        }
        if (color != null && meta instanceof PotionMeta potionMeta) {
            potionMeta.setColor(color);
            modified = true;
        }
        PotionType potionType = parsePotionType(potionTypeText);
        if (potionType != null && meta instanceof PotionMeta potionMeta) {
            potionMeta.setBasePotionType(potionType);
            modified = true;
        }

        if (modified) {
            item.setItemMeta(meta);
        }
        return modified;
    }

    /**
     * ItemStack に埋め込まれた AstralRecord カテゴリを取得します。
     *
     * @param item 判定対象
     * @return カテゴリ。AstralRecord アイテムでなければ {@code null}
     */
    public static @Nullable String getCategory(@NotNull ItemStack item) {
        if (!item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer()
                .get(KEY_CATEGORY, PersistentDataType.STRING);
    }

    /**
     * ItemStack に埋め込まれた装備インスタンス ID を取得します。
     *
     * @param item 判定対象
     * @return 装備インスタンス ID。装備インスタンスでなければ {@code null}
     */
    public static @Nullable String getEquipmentInstanceId(@NotNull ItemStack item) {
        if (!item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer()
                .get(KEY_EQUIPMENT_INSTANCE_ID, PersistentDataType.STRING);
    }

    /**
     * ItemStack に埋め込まれたルーンインスタンス ID を取得します。
     *
     * @param item 判定対象
     * @return ルーンインスタンス ID。ルーンインスタンスでなければ {@code null}
     */
    public static @Nullable String getRuneInstanceId(@NotNull ItemStack item) {
        if (!item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer()
                .get(KEY_RUNE_INSTANCE_ID, PersistentDataType.STRING);
    }

    // endregion

    // region --- テンプレート構築 ---

    /**
     * ItemModel からプロトタイプ ItemStack を構築します（キャッシュ用）。
     */
    private @NotNull ItemStack buildTemplate(@NotNull ItemModel model) {
        var item = new ItemStack(BASE_MATERIAL, 1);
        var meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }

        // --- 表示名 ---
        var rarityColor = rarityToColor(model.getRarity());
        var decoratedName = ColorCodeUtil.toLegacyText(model.getName(), model.getId());
        meta.displayName(LEGACY_SERIALIZER.deserialize(
                rarityColor + decoratedName + ColorCodeUtil.RESET));

        // --- Lore 構築 ---
        var loreStrings = buildLore(model);
        meta.lore(loreStrings.stream()
                .map(ColorCodeUtil::translateAlternateColorCodes)
                .map(LEGACY_SERIALIZER::deserialize)
                .map(c -> (Component) c)
                .toList());

        // --- custom_model_data（リソースパック側のモデル切り替え用） ---
        if (model.getCustomModelData() != null) {
            applyCustomModelData(meta, model.getCustomModelData());
        }

        // 表示名/Loreは維持しつつ、可能な限りバニラ要素を非表示化
        applyVanillaHideFlags(meta);

        // --- PDC にメタ情報を格納 ---
        writeCommonPersistentData(meta.getPersistentDataContainer(), model);

        item.setItemMeta(meta);

        //Logger.log(LogId.D_5211, model.getCategory(), model.getId());
        return item;
    }

    // endregion

    // region --- Lore 構築 ---

    /**
     * ItemModel の情報を元に Lore 行リストを構築します。
     */
    private @NotNull List<String> buildLore(@NotNull ItemModel model) {
        List<String> lore = new ArrayList<>();
        String rarityColor = rarityToColor(model.getRarity());
        String decoratedName = ColorCodeUtil.toLegacyText(model.getName(), model.getId());

        // ヘッダー
        lore.add(ColorCodeUtil.DARK_GRAY + "◈───────────◈");
        lore.add(rarityColor + "◆ " + decoratedName);
        lore.add(rarityStars(model.getRarity())
                + ColorCodeUtil.DARK_GRAY + "  " + rarityDisplayName(model.getRarity())
                + ColorCodeUtil.DARK_GRAY + " │ " + ColorCodeUtil.GRAY + displayCategoryName(model.getCategory()));
        lore.add("");

        // ユーザー定義 lore（フレーバーテキスト: イタリック）
        if (!model.getLore().isEmpty()) {
            for (String line : model.getLore()) {
                lore.add(ColorCodeUtil.GRAY + ColorCodeUtil.ITALIC
                        + ColorCodeUtil.translateAlternateColorCodes(line));
            }
            lore.add("");
        }

        // equipment ステータス（APIデータをそのまま表示）
        if (model.getEquipment() != null) {
            appendEquipmentLore(lore, model.getEquipment());
        }
        if (model.getConsumable() != null) {
            appendConsumableLore(lore, model.getConsumable());
        }

        appendSaleValueLore(lore, model);

        // フッター
        lore.add(ColorCodeUtil.DARK_GRAY + "◈───────────◈");
        if (model.getBundle() != null
                && model.getBundle().getLootTableId() != null
                && !model.getBundle().getLootTableId().isBlank()) {
            appendBundleLootLore(lore, model.getBundle().getLootTableId());
        }
        if (model.getUnTradeable()) {
            lore.add(ColorCodeUtil.RED + "✖ 取引不可");
        }
        if (shouldShowUnSellable(model)) {
            lore.add(ColorCodeUtil.RED + "✖ 売却不可");
        }

        return lore;
    }

    /**
     * Equipment 情報を Lore に追加します。
     * 現時点では API から取得した親データの表示のみ。
     */
    private void appendEquipmentLore(@NotNull List<String> lore, @NotNull ItemEquipment equipment) {
        lore.add(ColorCodeUtil.GOLD + "❖ 装備情報");

        // スロット / ハンドタイプ
        if (equipment.getSlot() != null) {
            lore.add(ColorCodeUtil.GRAY + " ▸ スロット: " + ColorCodeUtil.WHITE
                    + toEquipmentSlotLabel(equipment.getSlot()));
        }
        if (shouldShowHandType(equipment.getSlot())) {
            lore.add(ColorCodeUtil.GRAY + " ▸ ハンド: " + ColorCodeUtil.WHITE
                    + toHandTypeLabel(equipment.getHandType()));
        }

        // 装備条件
        if (equipment.getRequiredLevel() > 0) {
            lore.add(ColorCodeUtil.GRAY + " ▸ 必要Lv: " + ColorCodeUtil.YELLOW + equipment.getRequiredLevel());
        }
        if (!equipment.getRequiredClasses().isEmpty()) {
            lore.add(ColorCodeUtil.GRAY + " ▸ 必要クラス: " + ColorCodeUtil.WHITE
                    + String.join(", ", equipment.getRequiredClasses()));
        }

        // ステータス
        if (!equipment.getStats().isEmpty()) {
            lore.add("");
            lore.add(ColorCodeUtil.YELLOW + " ▸ ステータス補正");
            for (ItemEquipmentStat stat : equipment.getStats()) {
                String prefix = stat.getType().name().equals("SCALAR") ? "×" : "+";
                StatusType statusType = resolveStatusTypeOrNull(stat.getStatus());
                String statColor = statusCategoryColor(stat.getStatus(), statusType);
                String displayName = resolveStatusDisplayName(stat.getStatus(), statusType);
                lore.add(ColorCodeUtil.DARK_GRAY + "   ▹ "
                        + statColor + displayName
                        + ColorCodeUtil.DARK_GRAY + " : "
                        + formatStatValueWithPrefix(prefix, stat.displayValue()));
            }
        }

        // 耐久値
        if (equipment.getDurability() != null) {
            int durabilityMax = equipment.getDurability().getMax();
            lore.add(formatDurabilityBarLore(durabilityMax, durabilityMax));
        }

        appendEquipmentSkillLore(lore, equipment);
        lore.add("");
    }

    private void appendSaleValueLore(@NotNull List<String> lore, @NotNull ItemModel model) {
        if (isCurrencyItem(model)) {
            return;
        }
        lore.add(ColorCodeUtil.GRAY + " ▸ 売値: "
                + ColorCodeUtil.YELLOW + model.getSaleValue()
                + ColorCodeUtil.GOLD + " ゴールド");
    }

    private boolean shouldShowUnSellable(@NotNull ItemModel model) {
        return model.getUnSellable() && !isCurrencyItem(model);
    }

    private boolean isCurrencyItem(@NotNull ItemModel model) {
        return ItemCategory.fromApiValue(model.getCategory()) == ItemCategory.CURRENCY;
    }

    private void appendConsumableLore(@NotNull List<String> lore, @NotNull ItemConsumable consumable) {
        lore.add(ColorCodeUtil.AQUA + "❖ 使用情報");
        lore.add(ColorCodeUtil.GRAY + " ▸ 待機時間: "
                + ColorCodeUtil.WHITE + formatTicksAsSeconds(resolveConsumableUseTimeTicks(consumable)));
        lore.add(ColorCodeUtil.GRAY + " ▸ クールタイム: "
                + ColorCodeUtil.WHITE + formatTicksAsSeconds(resolveConsumableCooldownTicks(consumable)));
        int consumeAmount = consumable.getOnUse() == null ? 1 : Math.max(1, consumable.getOnUse().getAmount());
        lore.add(ColorCodeUtil.GRAY + " ▸ 消費数: " + ColorCodeUtil.WHITE + consumeAmount);

        if (!consumable.getEffects().isEmpty()) {
            lore.add("");
            lore.add(ColorCodeUtil.GREEN + " ▸ 効果");
            for (ItemConsumableEffect effect : consumable.getEffects()) {
                appendConsumableEffectLore(lore, effect);
            }
        }
        lore.add("");
    }

    private void appendConsumableEffectLore(
            @NotNull List<String> lore,
            @NotNull ItemConsumableEffect effect) {
        if (effect.getType() == ItemConsumableEffectType.RECOVER) {
            lore.add(ColorCodeUtil.DARK_GRAY + "   ▹ "
                    + ColorCodeUtil.GREEN + resolveConsumableStatusDisplayName(effect.getStatus())
                    + ColorCodeUtil.DARK_GRAY + " : "
                    + ColorCodeUtil.WHITE + formatConsumableEffectValue(effect));
            return;
        }
        if (effect.getType() == ItemConsumableEffectType.BUFF) {
            lore.add(ColorCodeUtil.DARK_GRAY + "   ▹ "
                    + ColorCodeUtil.YELLOW + resolveBuffDisplayName(effect.getBuffId())
                    + ColorCodeUtil.DARK_GRAY + " : "
                    + ColorCodeUtil.WHITE + formatRate(effect.getRate()));
            return;
        }
        lore.add(ColorCodeUtil.DARK_GRAY + "   ▹ "
                + ColorCodeUtil.GRAY + "未対応効果");
    }

    private @NotNull String resolveConsumableStatusDisplayName(@Nullable String rawStatus) {
        if (rawStatus == null || rawStatus.isBlank()) {
            return "回復";
        }
        String normalized = normalizeStatusKey(rawStatus);
        return switch (normalized) {
            case "HP", "HEALTH", "MAX_HEALTH" -> "HP";
            case "MP", "MANA", "MAX_MANA" -> "MP";
            case "EN", "ENERGY", "MAX_ENERGY" -> "EN";
            default -> {
                StatusType statusType = resolveStatusTypeOrNull(rawStatus);
                yield statusType == null ? "回復" : statusType.getDisplayName();
            }
        };
    }

    private @NotNull String formatConsumableEffectValue(@NotNull ItemConsumableEffect effect) {
        double value = effect.getValue() == null ? 0.0D : effect.getValue();
        if (effect.isPercent()) {
            return formatSkillDecimal(value * 100.0D) + "%回復";
        }
        return formatSkillDecimal(value) + "回復";
    }

    private @NotNull String formatRate(double rate) {
        if (rate >= 100.0D) {
            return "100%";
        }
        return formatSkillDecimal(Math.max(0.0D, rate)) + "%";
    }

    private long resolveConsumableUseTimeTicks(@NotNull ItemConsumable consumable) {
        return consumable.getOnUse() == null ? 40L : Math.max(1L, consumable.getOnUse().getUseTimeTicks());
    }

    private long resolveConsumableCooldownTicks(@NotNull ItemConsumable consumable) {
        return consumable.getOnUse() == null ? 40L : Math.max(0L, consumable.getOnUse().getCooldownTicks());
    }

    private @NotNull String formatTicksAsSeconds(long ticks) {
        double seconds = Math.max(0L, ticks) / 20.0D;
        if (seconds == Math.rint(seconds)) {
            return String.format(Locale.ROOT, "%.0f秒", seconds);
        }
        return String.format(Locale.ROOT, "%.1f秒", seconds);
    }

    private @NotNull String resolveBuffDisplayName(@Nullable String buffId) {
        if (buffId == null || buffId.isBlank()) {
            return "バフ";
        }
        return buffDisplayNameCache.computeIfAbsent(buffId, id -> {
            BuffType buffType = buffRepository.findById(id);
            if (buffType == null || buffType.getDisplayName() == null || buffType.getDisplayName().isBlank()) {
                return "バフ";
            }
            return ColorCodeUtil.toLegacyText(buffType.getDisplayName(), id);
        });
    }

    /**
     * Bundle に紐付く Loot テーブルの内容を Lore に追加します。
     * LootService にキャッシュ済みのデータのみを参照し、API リクエストは発行しません。
     */
    private void appendBundleLootLore(@NotNull List<String> lore, @NotNull String lootTableId) {
        LootModel lootModel = lootService.getLoadedOrFetch(lootTableId);
        if (lootModel == null) {
            lore.add(ColorCodeUtil.DARK_GRAY + "◆ ルート情報: " + lootTableId + " (未取得)");
            return;
        }

        lore.add(ColorCodeUtil.GOLD + "❖ 取得候補");
        for (LootEntry entry : lootModel.flattenedEntries()) {
            String amountText = entry.getMinAmount() == entry.getMaxAmount()
                    ? String.valueOf(entry.getMinAmount())
                    : entry.getMinAmount() + "～" + entry.getMaxAmount();
            String weightText = entry.getWeight() >= 100.0
                    ? ""
                    : ColorCodeUtil.DARK_GRAY + " / " + String.format("%.1f%%", entry.getWeight());
            lore.add(ColorCodeUtil.DARK_GRAY + " ▹ "
                    + ColorCodeUtil.WHITE + resolveBundleLootDisplayName(entry.getItemId())
                    + ColorCodeUtil.GRAY + " ×" + amountText
                    + weightText);
        }
        lore.add("");
    }

    private void appendEquipmentSkillLore(@NotNull List<String> lore, @NotNull ItemEquipment equipment) {
        List<String> skillIds = new ArrayList<>();
        for (String skillId : equipment.getSkills()) {
            addSkillId(skillIds, skillId);
        }
        if (skillIds.isEmpty()) {
            return;
        }

        lore.add("");
        lore.add(ColorCodeUtil.LIGHT_PURPLE + "◆ スキル");
        for (String skillId : skillIds) {
            appendSkillDefinitionLore(lore, skillId);
        }
    }

    private void appendSkillDefinitionLore(@NotNull List<String> lore, @NotNull String skillId) {
        SkillDefinition definition = skillService == null ? null : skillService.registry().getDefinition(skillId);
        if (definition == null) {
            lore.add(ColorCodeUtil.DARK_GRAY + "  - " + ColorCodeUtil.GRAY + "未読込スキル");
            return;
        }

        String displayName = SkillPresentationUtil.legacyName(definition, "未定義スキル");
        lore.add(ColorCodeUtil.DARK_GRAY + "  - " + ColorCodeUtil.WHITE + displayName);
    }

    private void addSkillId(@NotNull List<String> skillIds, @Nullable String skillId) {
        if (skillId == null) {
            return;
        }
        String normalizedSkillId = skillId.trim();
        if (normalizedSkillId.isBlank()
                || HIDDEN_EQUIPMENT_SKILL_IDS.contains(normalizedSkillId)
                || skillIds.contains(normalizedSkillId)) {
            return;
        }
        skillIds.add(normalizedSkillId);
    }

    private @NotNull String formatSkillTicks(long ticks) {
        if (ticks <= 0L) {
            return "-";
        }
        return ticks + "t";
    }

    private @NotNull String formatSkillDecimal(double value) {
        if (value == Math.rint(value)) {
            return String.format(Locale.ROOT, "%.0f", value);
        }
        return String.format(Locale.ROOT, "%.1f", value);
    }

    /**
     * bundle Lore 用にルート報酬の表示名を解決します。
     *
     * @param itemId ルート定義上のアイテムID
     * @return 日本語名優先の表示文字列
     */
    private @NotNull String resolveBundleLootDisplayName(@NotNull String itemId) {
        ItemModel rewardModel = itemService.findLoadedById(itemId);
        if (rewardModel == null) {
            rewardModel = itemService.loadItem(itemId);
        }

        if (rewardModel == null || rewardModel.getName() == null || rewardModel.getName().isBlank()) {
            return itemId;
        }
        return ColorCodeUtil.toLegacyText(rewardModel.getName(), rewardModel.getId());
    }

    /**
     * 装備インスタンス向けの Lore 行リストを構築します。
     * enhance 累積ステータス・enchant 情報・rune スロット・transcendence 状態を表示します。
     */
    private @NotNull List<String> buildLoreForEquipmentInstance(
            @NotNull ItemModel model, @NotNull EquipmentInstance instance) {
        List<String> lore = new ArrayList<>();
        String rarityColor = rarityToColor(model.getRarity());
        String decoratedName = ColorCodeUtil.toLegacyText(resolveEquipmentDisplayName(model, instance), model.getId());
        if (isBroken(instance)) {
            decoratedName = ColorCodeUtil.DARK_RED + "[破損] " + rarityColor + decoratedName;
        }

        lore.add(ColorCodeUtil.DARK_GRAY + "◈───────────◈");
        lore.add(rarityColor + "◆ " + decoratedName);
        lore.add(rarityStars(model.getRarity())
                + ColorCodeUtil.DARK_GRAY + "  " + rarityDisplayName(model.getRarity())
                + ColorCodeUtil.DARK_GRAY + " │ " + ColorCodeUtil.GRAY + displayCategoryName(model.getCategory()));
        lore.add("");

        if (!model.getLore().isEmpty()) {
            for (String line : model.getLore()) {
                lore.add(ColorCodeUtil.GRAY + ColorCodeUtil.ITALIC
                        + ColorCodeUtil.translateAlternateColorCodes(line));
            }
            lore.add("");
        }

        if (model.getEquipment() != null) {
            var eq = model.getEquipment();

            lore.add(ColorCodeUtil.GOLD + "❖ 装備情報");
            if (eq.getSlot() != null) {
                lore.add(ColorCodeUtil.GRAY + " ▸ スロット: " + ColorCodeUtil.WHITE
                        + toEquipmentSlotLabel(eq.getSlot()));
            }
            if (shouldShowHandType(eq.getSlot())) {
                lore.add(ColorCodeUtil.GRAY + " ▸ ハンド: " + ColorCodeUtil.WHITE
                        + toHandTypeLabel(eq.getHandType()));
            }
            if (eq.getRequiredLevel() > 0) {
                lore.add(ColorCodeUtil.GRAY + " ▸ 必要Lv: " + ColorCodeUtil.YELLOW + eq.getRequiredLevel());
            }
            if (!eq.getRequiredClasses().isEmpty()) {
                lore.add(ColorCodeUtil.GRAY + " ▸ 必要クラス: " + ColorCodeUtil.WHITE
                        + String.join(", ", eq.getRequiredClasses()));
            }

            // --- transcendence 状態変化表示 ---
            if (instance.getTranscendenceRank() > 0) {
                ItemEquipmentTranscendence currentTrans = eq.getTranscendence().stream()
                        .filter(t -> t.getRank() == instance.getTranscendenceRank())
                        .findFirst().orElse(null);
                String transName = currentTrans != null && currentTrans.getName() != null
                        ? currentTrans.getName() : "ランク " + instance.getTranscendenceRank();
                lore.add(ColorCodeUtil.LIGHT_PURPLE + " ▸ 状態変化: " + ColorCodeUtil.WHITE + "【" + transName + "】");
            }

            // --- 強化レベル表示 ---
            if (eq.getEnhance() != null) {
                int effectiveMaxLevel = resolveEffectiveEnhanceMaxLevel(eq, instance);
                String enhanceLabel = instance.getEnhanceLevel() > 0
                        ? ColorCodeUtil.YELLOW + "+" + instance.getEnhanceLevel()
                        + ColorCodeUtil.GRAY + " / 最大 " + ColorCodeUtil.WHITE + "+" + effectiveMaxLevel
                        : ColorCodeUtil.GRAY + "未強化" + ColorCodeUtil.DARK_GRAY + " (最大 +" + effectiveMaxLevel + ")";
                lore.add(ColorCodeUtil.GRAY + " ▸ 強化: " + enhanceLabel);
            }

            // --- ステータス表示（ベース + enhance 累積） ---
            if (!instance.getStatRolls().isEmpty() || !eq.getStats().isEmpty()) {
                lore.add("");
                lore.add(ColorCodeUtil.YELLOW + " ▸ ステータス補正");

                // ItemEquipmentStat の status → type マップ
                Map<String, ItemEquipmentStatType> statTypeMap = new LinkedHashMap<>();
                for (var stat : eq.getStats()) {
                    statTypeMap.put(stat.getStatus(), stat.getType());
                }

                // enhance 累積計算 (status#type → [minAccum, maxAccum])
                Map<String, double[]> enhanceAccum = calculateEnhanceStats(eq, instance.getEnhanceLevel());

                for (var roll : instance.getStatRolls()) {
                    ItemEquipmentStatType rollType = statTypeMap.getOrDefault(
                            roll.getStatus(), ItemEquipmentStatType.FLAT);
                    double baseMin = parseStatDouble(roll.getMin());
                    double baseMax = parseStatDouble(roll.getMax());

                    String enhanceKey = roll.getStatus() + "#" + rollType.name();
                    double[] enhAdd = enhanceAccum.getOrDefault(enhanceKey, new double[]{0.0, 0.0});

                    double totalMin = baseMin + enhAdd[0];
                    double totalMax = baseMax + enhAdd[1];

                    String prefix = rollType == ItemEquipmentStatType.SCALAR ? "×" : "+";
                    String displayValue = totalMin == totalMax
                            ? formatStatValueWithPrefix(prefix, totalMin)
                            : formatStatRange(prefix, totalMin, totalMax);

                    // enhance 加算分の表示注釈
                    String enhanceNote = (enhAdd[0] != 0.0 || enhAdd[1] != 0.0)
                            ? ColorCodeUtil.YELLOW + " [強化+" + formatStatValue(enhAdd[0])
                                    + (enhAdd[0] != enhAdd[1] ? " ～ " + formatStatValue(enhAdd[1]) : "") + "]"
                            : "";

                    StatusType statusType = resolveStatusTypeOrNull(roll.getStatus());
                    String statColor = statusCategoryColor(roll.getStatus(), statusType);
                    String displayName = resolveStatusDisplayName(roll.getStatus(), statusType);
                    lore.add(ColorCodeUtil.DARK_GRAY + "   ▹ "
                            + statColor + displayName
                            + ColorCodeUtil.DARK_GRAY + " : "
                            + displayValue
                            + enhanceNote);
                }

                // enhance の statIncrease に含まれるが statRolls にないステータス（SCALAR など追加分）を別表示
                for (var entry : enhanceAccum.entrySet()) {
                    String[] parts = entry.getKey().split("#", 2);
                    if (parts.length < 2) continue;
                    String status = parts[0];
                    ItemEquipmentStatType type = ItemEquipmentStatType.FLAT;
                    try { type = ItemEquipmentStatType.valueOf(parts[1]); } catch (IllegalArgumentException ignored) { }

                    boolean alreadyShown = instance.getStatRolls().stream()
                            .anyMatch(r -> r.getStatus().equals(status));
                    if (alreadyShown) continue;

                    double[] enhAdd = entry.getValue();
                    String prefix = type == ItemEquipmentStatType.SCALAR ? "×" : "+";
                    String displayValue = enhAdd[0] == enhAdd[1]
                            ? formatStatValueWithPrefix(prefix, enhAdd[0])
                            : formatStatRange(prefix, enhAdd[0], enhAdd[1]);

                    StatusType statusType = resolveStatusTypeOrNull(status);
                    String statColor = statusCategoryColor(status, statusType);
                    String displayName = resolveStatusDisplayName(status, statusType);
                    lore.add(ColorCodeUtil.DARK_GRAY + "   ▹ "
                            + statColor + displayName
                            + ColorCodeUtil.DARK_GRAY + " : "
                            + displayValue
                            + ColorCodeUtil.YELLOW + " [強化]");
                }
            }

            // --- enchant（エンチャント）情報 ---
            if (eq.getEnchant() != null) {
                int effectiveMaxSlots = resolveEffectiveEnchantMaxSlots(eq, instance);
                lore.add("");
                lore.add(ColorCodeUtil.AQUA + "✦ エンチャント"
                        + ColorCodeUtil.DARK_GRAY + " (" + instance.getEnchants().size()
                        + "/" + effectiveMaxSlots + ")");
                if (instance.getEnchants().isEmpty()) {
                    lore.add(ColorCodeUtil.DARK_GRAY + "   ─ 未付与");
                } else {
                    for (EquipmentEnchant enchant : instance.getEnchants()) {
                        String prefix = "SCALAR".equals(enchant.getType()) ? "×" : "+";
                        StatusType statusType = resolveStatusTypeOrNull(enchant.getStatus());
                        String statColor = statusCategoryColor(enchant.getStatus(), statusType);
                        String displayName = resolveStatusDisplayName(enchant.getStatus(), statusType);
                        String valueStr = formatStatValue(enchant.getValue());
                        lore.add(ColorCodeUtil.DARK_GRAY + " [" + (enchant.getSlotIndex() + 1) + "] "
                                + statColor + displayName
                                + ColorCodeUtil.DARK_GRAY + " : "
                                + formatStatValueWithPrefix(prefix, valueStr));
                    }
                }
            }

            // --- rune（ルーン）スロット情報 ---
            if (instance.getRuneMaxSlots() > 0) {
                lore.add("");
                lore.add(ColorCodeUtil.GREEN + "◆ ルーンスロット"
                        + ColorCodeUtil.DARK_GRAY + " (" + instance.getRunes().size()
                        + "/" + instance.getRuneMaxSlots() + ")");
                // 装着済みルーン
                Map<Integer, EquipmentRune> runeBySlot = new LinkedHashMap<>();
                for (EquipmentRune rune : instance.getRunes()) {
                    runeBySlot.put(rune.getSlotIndex(), rune);
                }
                for (int slot = 0; slot < instance.getRuneMaxSlots(); slot++) {
                    EquipmentRune rune = runeBySlot.get(slot);
                    if (rune != null) {
                        lore.add(ColorCodeUtil.GREEN + " ● " + ColorCodeUtil.WHITE + rune.getItemId());
                    } else {
                        lore.add(ColorCodeUtil.DARK_GRAY + " ○ 空きスロット");
                    }
                }
            }

            // --- 耐久値 ---
            if (instance.getDurabilityMax() > 0) {
                lore.add(formatDurabilityLore(instance));
            }
            appendEquipmentSkillLore(lore, eq);
            lore.add("");
        }

        appendSaleValueLore(lore, model);
        lore.add(ColorCodeUtil.DARK_GRAY + "◈───────────◈");
        if (model.getUnTradeable()) lore.add(ColorCodeUtil.RED + "✖ 取引不可");
        if (shouldShowUnSellable(model)) lore.add(ColorCodeUtil.RED + "✖ 売却不可");
        return lore;
    }

    /**
     * ルーンインスタンス向けの Lore 行リストを構築します。
     * ステータスロールはインスタンスの確定値（value）を使用します。
     */
    private @NotNull List<String> buildLoreForRuneInstance(
            @NotNull ItemModel model, @NotNull RuneInstance instance) {
        List<String> lore = new ArrayList<>();
        String rarityColor = rarityToColor(model.getRarity());
        String decoratedName = ColorCodeUtil.toLegacyText(model.getName(), model.getId());

        lore.add(ColorCodeUtil.DARK_GRAY + "◈───────────◈");
        lore.add(rarityColor + "◆ " + decoratedName);
        lore.add(rarityStars(model.getRarity())
                + ColorCodeUtil.DARK_GRAY + "  " + rarityDisplayName(model.getRarity())
                + ColorCodeUtil.DARK_GRAY + " │ " + ColorCodeUtil.GRAY + displayCategoryName(model.getCategory()));
        lore.add("");

        if (!model.getLore().isEmpty()) {
            for (String line : model.getLore()) {
                lore.add(ColorCodeUtil.GRAY + ColorCodeUtil.ITALIC
                        + ColorCodeUtil.translateAlternateColorCodes(line));
            }
            lore.add("");
        }

        if (!instance.getStatRolls().isEmpty()) {
            lore.add(ColorCodeUtil.GOLD + "❖ ルーン効果");
            lore.add(ColorCodeUtil.YELLOW + " ▸ ステータス補正");
            for (var roll : instance.getStatRolls()) {
                String prefix = "SCALAR".equals(roll.getType()) ? "×" : "+";
                StatusType statusType = resolveStatusTypeOrNull(roll.getStatus());
                String statColor = statusCategoryColor(roll.getStatus(), statusType);
                String displayName = resolveStatusDisplayName(roll.getStatus(), statusType);
                lore.add(ColorCodeUtil.DARK_GRAY + "   ▹ "
                        + statColor + displayName
                        + ColorCodeUtil.DARK_GRAY + " : "
                        + formatStatValueWithPrefix(prefix, roll.getValue()));
            }
            lore.add("");
        }

        appendSaleValueLore(lore, model);
        lore.add(ColorCodeUtil.DARK_GRAY + "◈───────────◈");
        if (model.getUnTradeable()) lore.add(ColorCodeUtil.RED + "✖ 取引不可");
        if (shouldShowUnSellable(model)) lore.add(ColorCodeUtil.RED + "✖ 売却不可");
        return lore;
    }

    /**
     * 装備の表示名を決定します。
     * transcendence の overrides.name → 元の name の優先順で解決します。
     */
    private @NotNull String resolveEquipmentDisplayName(@NotNull ItemModel model,
            @NotNull EquipmentInstance instance) {
        if (instance.getTranscendenceRank() > 0 && model.getEquipment() != null) {
            for (ItemEquipmentTranscendence t : model.getEquipment().getTranscendence()) {
                if (t.getRank() == instance.getTranscendenceRank()
                        && t.getOverridesName() != null) {
                    return t.getOverridesName();
                }
            }
        }
        return model.getName();
    }

    /**
     * 現在の transcendence ランクを考慮して有効な強化最大レベルを返します。
     */
    private int resolveEffectiveEnhanceMaxLevel(@NotNull ItemEquipment eq,
            @NotNull EquipmentInstance instance) {
        if (instance.getTranscendenceRank() > 0) {
            for (ItemEquipmentTranscendence t : eq.getTranscendence()) {
                if (t.getRank() == instance.getTranscendenceRank()
                        && t.getOverridesEnhanceMaxLevel() != null) {
                    return t.getOverridesEnhanceMaxLevel();
                }
            }
        }
        return eq.getEnhance() != null ? eq.getEnhance().getMaxLevel() : 0;
    }

    /**
     * 現在の transcendence ランクを考慮して有効なエンチャント最大スロット数を返します。
     */
    private int resolveEffectiveEnchantMaxSlots(@NotNull ItemEquipment eq,
            @NotNull EquipmentInstance instance) {
        if (instance.getTranscendenceRank() > 0) {
            for (ItemEquipmentTranscendence t : eq.getTranscendence()) {
                if (t.getRank() == instance.getTranscendenceRank()
                        && t.getOverridesEnchantMaxSlots() != null) {
                    return t.getOverridesEnchantMaxSlots();
                }
            }
        }
        return eq.getEnchant() != null ? eq.getEnchant().getMaxSlots() : 0;
    }

    /**
     * enhance.levels の statIncrease を Lv1〜enhanceLevel まで累積します。
     * キーは "status#TYPE"（例: "ATTACK#FLAT"）、値は [minAccum, maxAccum]。
     */
    private @NotNull Map<String, double[]> calculateEnhanceStats(@NotNull ItemEquipment eq, int enhanceLevel) {
        Map<String, double[]> accum = new LinkedHashMap<>();
        if (eq.getEnhance() == null || enhanceLevel <= 0) {
            return accum;
        }
        for (var level : eq.getEnhance().getLevels()) {
            if (level.getLevel() > enhanceLevel) {
                continue;
            }
            for (ItemEquipmentEnhanceStatIncrease inc : level.getStatIncrease()) {
                String key = inc.getStatus() + "#" + inc.getType().name();
                double[] cur = accum.computeIfAbsent(key, k -> new double[]{0.0, 0.0});
                cur[0] += inc.getMin();
                cur[1] += inc.getMax();
            }
        }
        return accum;
    }

    /**
     * ステータス値文字列を double にパースします。パース失敗時は 0.0 を返します。
     */
    private double parseStatDouble(@NotNull String value) {
        try {
            return Double.parseDouble(value.trim().split("[~～]")[0].trim());
        } catch (NumberFormatException ignored) {
            return 0.0;
        }
    }

    /**
     * double をステータス表示用の文字列にフォーマットします。
     * 末尾のゼロを除去した十進数表現を返します。
     */
    private @NotNull String formatStatValue(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private @NotNull String formatStatValueWithPrefix(@NotNull String prefix, double value) {
        return formatStatValueWithPrefix(prefix, formatStatValue(value));
    }

    private @NotNull String formatStatValueWithPrefix(@NotNull String prefix, @NotNull String value) {
        String normalized = value.trim();
        if (normalized.contains("~") || normalized.contains("～")) {
            String[] parts = normalized.split("[~～]", 2);
            if (parts.length == 2) {
                return STATUS_VALUE_COLOR + prefix + parts[0].trim()
                        + ColorCodeUtil.DARK_GRAY + " ～ "
                        + STATUS_VALUE_COLOR + prefix + parts[1].trim();
            }
        }
        return STATUS_VALUE_COLOR + prefix + normalized;
    }

    private @NotNull String formatStatRange(@NotNull String prefix, double min, double max) {
        return formatStatValueWithPrefix(prefix, min)
                + ColorCodeUtil.DARK_GRAY + " ～ "
                + formatStatValueWithPrefix(prefix, max);
    }

    private @NotNull String formatDurabilityLore(@NotNull EquipmentInstance instance) {
        return formatDurabilityBarLore(instance.getDurabilityValue(), instance.getDurabilityMax());
    }

    private @NotNull String formatDurabilityBarLore(int value, int max) {
        int filledLength = max <= 0
                ? 0
                : (int) Math.round(Math.clamp((double) value / max, 0.0D, 1.0D) * DURABILITY_BAR_LENGTH);
        StringBuilder bar = new StringBuilder(DURABILITY_BAR_LENGTH + 16);
        bar.append(ColorCodeUtil.WHITE);
        bar.repeat(DURABILITY_BAR_CHAR, filledLength);
        bar.append(ColorCodeUtil.GRAY);
        bar.repeat(DURABILITY_BAR_CHAR, DURABILITY_BAR_LENGTH - filledLength);
        return ColorCodeUtil.GRAY + " ▸ 耐久値: " + bar;
    }

    private boolean isBroken(@NotNull EquipmentInstance instance) {
        return instance.getDurabilityMax() > 0 && instance.getDurabilityValue() <= 0;
    }

    private @NotNull String displayCategoryName(@NotNull String category) {
        return ItemCategory.displayNameJa(category);
    }

    /**
     * 解決済みStatusTypeがある場合はそれを優先して表示名を返します。
     */
    private @NotNull String resolveStatusDisplayName(@NotNull String rawStatus, @Nullable StatusType type) {
        if (type != null) {
            return type.getDisplayName();
        }

        String normalized = normalizeStatusKey(rawStatus);
        if (normalized.isEmpty()) {
            return rawStatus;
        }
        if ("MINING_SPEED".equals(normalized)) {
            return "採集速度";
        }
        return normalized;
    }

    /**
     * APIから取得したステータス識別子を、可能な限りStatusTypeに解決します。
     */
    private @Nullable StatusType resolveStatusTypeOrNull(@NotNull String rawStatus) {
        String normalized = normalizeStatusKey(rawStatus);
        if (normalized.isEmpty()) {
            return null;
        }
        try {
            return StatusType.valueOf(normalized);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    /**
     * StatusTypeカテゴリに応じてLoreのステータス値カラーを返します。
     */
    private @NotNull String statusCategoryColor(@NotNull String rawStatus, @Nullable StatusType type) {
        if (type == null) {
            if ("MINING_SPEED".equals(normalizeStatusKey(rawStatus))) {
                return ColorCodeUtil.YELLOW;
            }
            return ColorCodeUtil.AQUA;
        }
        return type.legacyColor();
    }

    private @NotNull String normalizeStatusKey(@NotNull String rawStatus) {
        return rawStatus.trim()
                .replace(' ', '_')
                .replace('-', '_')
                .toUpperCase(Locale.ROOT);
    }

    /**
     * 装備スロットをLore表示向けの日本語に変換します。
     */
    private @NotNull String toEquipmentSlotLabel(@Nullable ItemEquipmentSlot slot) {
        return Objects.requireNonNullElse(slot, ItemEquipmentSlot.UNKNOWN).getDisplayName();
    }

    /**
     * 装備ハンドタイプをLore表示向けの日本語に変換します。
     */
    private @NotNull String toHandTypeLabel(@Nullable ItemEquipmentHandType handType) {
        return Objects.requireNonNullElse(handType, ItemEquipmentHandType.ONE).getDisplayName();
    }

    private boolean shouldShowHandType(@Nullable ItemEquipmentSlot slot) {
        return slot == ItemEquipmentSlot.WEAPON || slot == ItemEquipmentSlot.TOOL;
    }

    // endregion

    // region --- ユーティリティ ---

    /**
     * レアリティを星評価文字列に変換します。
     */
    private @NotNull String rarityStars(@NotNull String rarity) {
        return switch (rarity.toLowerCase(Locale.ROOT)) {
            case "common"    -> ColorCodeUtil.GRAY + "★" + ColorCodeUtil.DARK_GRAY + "☆☆☆☆";
            case "uncommon"  -> ColorCodeUtil.GREEN + "★★" + ColorCodeUtil.DARK_GRAY + "☆☆☆";
            case "rare"      -> ColorCodeUtil.AQUA + "★★★" + ColorCodeUtil.DARK_GRAY + "☆☆";
            case "epic"      -> ColorCodeUtil.LIGHT_PURPLE + "★★★★" + ColorCodeUtil.DARK_GRAY + "☆";
            case "legendary" -> ColorCodeUtil.GOLD + "★★★★★";
            case "mythic"    -> ColorCodeUtil.RED + "✦✦✦✦✦";
            default          -> ColorCodeUtil.GRAY + "─";
        };
    }

    /**
     * レアリティを日本語表示名に変換します。
     */
    private @NotNull String rarityDisplayName(@NotNull String rarity) {
        return ItemRarity.displayNameJa(rarity);
    }

    /**
     * レアリティ文字列から Minecraft カラーコードを返します。
     */
    private @NotNull String rarityToColor(@NotNull String rarity) {
        return switch (rarity.toLowerCase(Locale.ROOT)) {
            case "common"    -> ColorCodeUtil.WHITE;
            case "uncommon"  -> ColorCodeUtil.GREEN;
            case "rare"      -> ColorCodeUtil.AQUA;
            case "epic"      -> ColorCodeUtil.LIGHT_PURPLE;
            case "legendary" -> ColorCodeUtil.GOLD;
            case "mythic"    -> ColorCodeUtil.RED;
            default          -> ColorCodeUtil.GRAY;
        };
    }

    /**
     * テンプレートキャッシュのキーを生成します。
     */
    private @NotNull String cacheKey(@NotNull ItemModel model) {
        return model.getCategory().toLowerCase(Locale.ROOT)
                + ":" + model.getId().toLowerCase(Locale.ROOT);
    }

    private static @Nullable Material resolveIconMaterial(@NotNull String iconName) {
        Material material = Material.matchMaterial(iconName.trim().toUpperCase(Locale.ROOT));
        if (material == null || material == Material.AIR || !material.isItem()) {
            Logger.log(LogId.W_5210, iconName);
            return null;
        }
        return material;
    }

    /**
     * 可能な限りバニラのツールチップ要素を隠す ItemFlag 配列を解決します。
     * 新規 ItemFlag が追加されても {@code HIDE_} 系は自動で追従します。
     */
    private static @NotNull ItemFlag[] resolveVanillaHideFlags() {
        return Arrays.stream(ItemFlag.values())
                .filter(flag -> flag.name().startsWith("HIDE_"))
                .filter(flag -> !ITEM_FLAG_EXCLUSIONS.contains(flag.name()))
                .toArray(ItemFlag[]::new);
    }

    /**
     * 非表示対象の ItemFlag を ItemMeta に一括適用します。
     */
    private static void applyVanillaHideFlags(@NotNull ItemMeta meta) {
        if (VANILLA_HIDE_FLAGS.length == 0) {
            return;
        }
        meta.addItemFlags(VANILLA_HIDE_FLAGS);
    }

    private static void applyCustomModelData(@NotNull ItemMeta meta, int customModelData) {
        CustomModelDataComponentUtil.writeFromInt(meta, customModelData);
    }

    private static void writeCommonPersistentData(
            @NotNull PersistentDataContainer pdc,
            @NotNull ItemModel model
    ) {
        pdc.set(KEY_ITEM_ID, PersistentDataType.STRING, model.getId());
        pdc.set(KEY_ICON, PersistentDataType.STRING, model.getIcon().toUpperCase(Locale.ROOT));
        if (model.getCustomModelData() != null) {
            pdc.set(KEY_CUSTOM_MODEL_DATA, PersistentDataType.INTEGER, model.getCustomModelData());
        }
        if (model.getAppearance() != null) {
            if (model.getAppearance().getColor() != null && !model.getAppearance().getColor().isBlank()) {
                pdc.set(KEY_APPEARANCE_COLOR, PersistentDataType.STRING, model.getAppearance().getColor().trim());
            }
            if (model.getAppearance().getPotionType() != null && !model.getAppearance().getPotionType().isBlank()) {
                pdc.set(KEY_POTION_TYPE, PersistentDataType.STRING, model.getAppearance().getPotionType().trim());
            }
        }
        pdc.set(KEY_CATEGORY, PersistentDataType.STRING, model.getCategory());
        pdc.set(KEY_RARITY, PersistentDataType.STRING, model.getRarity());
    }

    private static @Nullable Color parseColor(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim();
        if (normalized.startsWith("#")) {
            normalized = normalized.substring(1);
        }
        if (normalized.length() == 6) {
            try {
                int rgb = Integer.parseInt(normalized, 16);
                return Color.fromRGB((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }

        String[] parts = normalized.split(",");
        if (parts.length != 3) {
            return null;
        }
        try {
            return Color.fromRGB(
                    clampColor(Integer.parseInt(parts[0].trim())),
                    clampColor(Integer.parseInt(parts[1].trim())),
                    clampColor(Integer.parseInt(parts[2].trim()))
            );
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static int clampColor(int value) {
        return Math.clamp(value, 0, 255);
    }

    private static @Nullable PotionType parsePotionType(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return PotionType.valueOf(raw.trim().replace('-', '_').replace(' ', '_').toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    // endregion
}



