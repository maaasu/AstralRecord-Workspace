package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.feature.buff.model.BuffType;
import io.github.maaasu.astralRecord.feature.buff.repository.BuffRepository;
import io.github.maaasu.astralRecord.feature.inventory.model.AccessorySlotType;
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
import io.github.maaasu.astralRecord.feature.item.model.ItemRune;
import io.github.maaasu.astralRecord.feature.item.model.ItemSigil;
import io.github.maaasu.astralRecord.feature.item.model.ItemSigilModifier;
import io.github.maaasu.astralRecord.feature.item.model.SetEffect;
import io.github.maaasu.astralRecord.feature.item.model.SetEffectPiece;
import io.github.maaasu.astralRecord.feature.item.model.SetEffectStat;
import io.github.maaasu.astralRecord.feature.loot.model.LootEntry;
import io.github.maaasu.astralRecord.feature.loot.model.LootModel;
import io.github.maaasu.astralRecord.feature.loot.service.LootService;
import io.github.maaasu.astralRecord.feature.playerclass.PlayerClassService;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import io.github.maaasu.astralRecord.infrastructure.util.CustomModelDataComponentUtil;
import io.github.maaasu.astralRecord.infrastructure.util.MaterialNameResolver;
import io.github.maaasu.astralRecord.shared.display.DisplaySeparators;
import io.github.maaasu.astralRecord.shared.masterdata.tag.MasterTagIds;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
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
import java.math.RoundingMode;
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

    /** PDC キー: 装備スロット種別 */
    private static final NamespacedKey KEY_EQUIPMENT_SLOT =
            new NamespacedKey("astralrecord", "equipment_slot");

    private static final NamespacedKey KEY_DURABILITY_MAX =
            new NamespacedKey("astralrecord", "durability_max");

    private static final NamespacedKey KEY_DURABILITY_VALUE =
            new NamespacedKey("astralrecord", "durability_value");

    /** PDC キー: フックショットのフック装填済み表示状態 */
    private static final NamespacedKey KEY_HOOKSHOT_LOADED =
            new NamespacedKey("astralrecord", "hookshot_loaded");

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

    private static final String STATUS_VALUE_COLOR = ColorCodeUtil.WHITE + ColorCodeUtil.BOLD;
    private static final int DURABILITY_BAR_LENGTH = 20;
    private static final String DURABILITY_BAR_CHAR = "|";
    private static final double DURABILITY_DARK_GREEN_THRESHOLD = 0.75D;
    private static final double DURABILITY_GREEN_THRESHOLD = 0.50D;
    private static final double DURABILITY_YELLOW_THRESHOLD = 0.25D;

    /** ルートテーブル参照用（nullable: 未初期化時は Lore に含めない） */
    private final LootService lootService;

    /** ルート内アイテム名の日本語表示解決に使用します。 */
    private final ItemService itemService;
    /** 必要クラスの表示名解決に使用します。 */
    private @Nullable PlayerClassService playerClassService;
    private final BuffRepository buffRepository = new BuffRepository();
    private final Map<String, String> buffDisplayNameCache = new ConcurrentHashMap<>();

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
     * 必要クラスの表示名解決に使用するサービスを設定します。
     *
     * @param playerClassService クラス表示名サービス。null の場合は未登録表示を使用します
     */
    public void setPlayerClassService(@Nullable PlayerClassService playerClassService) {
        this.playerClassService = playerClassService;
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
     * @param amount 個数（1～maxStack）
     * @return 生成された ItemStack（サーバ側は PAPER）
     */
    public @NotNull ItemStack create(@NotNull ItemModel model, int amount) {
        String key = cacheKey(model);
        ItemStack template = templateCache.computeIfAbsent(key, k -> buildTemplate(model));
        ItemStack item = template.clone();
        item.setAmount(Math.clamp(amount, 1, Math.max(1, model.getMaxStack())));
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
            ItemStack cloned = item.clone();
            hideBundleContentsTooltip(cloned);
            return cloned;
        }

        Material iconMaterial = resolveIconMaterial(iconName);
        ItemStack replaced = iconMaterial == null || iconMaterial == item.getType()
                ? item.clone()
                : applyDisplayIcon(item, iconMaterial);
        hideBundleContentsTooltip(replaced);
        applyAppearance(replaced);
        applyDurabilityVisual(replaced);
        return replaced;
    }

    /**
     * 表示用 ItemStack へ icon の見た目を適用します。
     * 鍛冶型は Material 自体を変更するとクライアントが固有説明を無条件で追加するため、
     * 元 Material を維持して item model component だけを差し替えます。
     *
     * @param item 変換元 ItemStack
     * @param iconMaterial 表示する icon Material
     * @return icon の見た目を適用した clone
     */
    public static @NotNull ItemStack applyDisplayIcon(
            @NotNull ItemStack item,
            @NotNull Material iconMaterial
    ) {
        if (!isSmithingTemplate(iconMaterial)) {
            return item.withType(iconMaterial);
        }

        ItemStack replaced = item.clone();
        replaced.setData(DataComponentTypes.ITEM_MODEL, iconMaterial.getKey());
        return replaced;
    }

    /**
     * Bundle のバニラ内容量表示を非表示にします。
     *
     * @param item 表示対象の ItemStack
     * @return 内容量表示の非表示設定を追加した場合は {@code true}
     */
    public static boolean hideBundleContentsTooltip(@NotNull ItemStack item) {
        if (item.getType() != Material.BUNDLE) {
            return false;
        }

        TooltipDisplay current = item.getData(DataComponentTypes.TOOLTIP_DISPLAY);
        if (current != null && current.hiddenComponents().contains(DataComponentTypes.BUNDLE_CONTENTS)) {
            return false;
        }

        TooltipDisplay.Builder builder = TooltipDisplay.tooltipDisplay();
        if (current != null) {
            builder.hideTooltip(current.hideTooltip())
                    .hiddenComponents(new HashSet<>(current.hiddenComponents()));
        }
        builder.addHiddenComponents(DataComponentTypes.BUNDLE_CONTENTS);
        item.setData(DataComponentTypes.TOOLTIP_DISPLAY, builder);
        return true;
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
        return create(model, instance, amount, null);
    }

    /**
     * {@link ItemModel} と {@link EquipmentInstance}、inventory entry metadata から ItemStack を生成します。
     * インスタンス固有のステータスロール値とフックショットの装填状態を Lore / PDC に反映します。
     *
     * @param model        アイテムマスタ定義
     * @param instance     装備インスタンス
     * @param amount       個数
     * @param metadataJson inventory entry の metadata。フックショット以外では無視します
     * @return 生成された ItemStack
     */
    public @NotNull ItemStack create(
            @NotNull ItemModel model,
            @NotNull EquipmentInstance instance,
            int amount,
            @Nullable String metadataJson
    ) {
        return create(model, instance, amount, metadataJson, null);
    }

    /**
     * 装備インスタンスを生成し、装備中表示時のセット効果状態を Lore に反映します。
     *
     * @param model アイテムマスタ定義
     * @param instance 装備インスタンス
     * @param amount 個数
     * @param metadataJson inventory entry の metadata
     * @param equippedSetCounts 装備中セット数。null は従来の静的表示
     * @return 生成された ItemStack
     */
    public @NotNull ItemStack create(
            @NotNull ItemModel model,
            @NotNull EquipmentInstance instance,
            int amount,
            @Nullable String metadataJson,
            @Nullable Map<String, Integer> equippedSetCounts
    ) {
        var item = new ItemStack(BASE_MATERIAL, 1);
        var meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }

        boolean hookshotLoaded = isHookshotEquipment(model) && HookshotLoadState.isLoaded(metadataJson);

        var rarityColor = rarityToColor(model.getRarity());

        // --- アイテム名: transcendence オーバーライド → enhance +N サフィックス ---
        String baseName = resolveEquipmentDisplayName(model, instance);
        var decoratedName = ColorCodeUtil.toLegacyText(baseName, model.getId());
        boolean broken = isBroken(instance);
        String visibleName = broken
                ? ColorCodeUtil.DARK_RED + "[破損] " + rarityColor + "◆ " + decoratedName
                : rarityColor + "◆ " + decoratedName;
        String enhanceSuffix = instance.getEnhanceLevel() > 0
                ? " §7[ §e+ §f§l" + instance.getEnhanceLevel() + "§r§7 ]"
                : "";
        meta.displayName(LEGACY_SERIALIZER.deserialize(
                visibleName + enhanceSuffix + ColorCodeUtil.RESET));

        var loreStrings = buildLoreForEquipmentInstance(
                model, instance, hookshotLoaded, equippedSetCounts);
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
        if (hookshotLoaded) {
            pdc.set(KEY_HOOKSHOT_LOADED, PersistentDataType.BYTE, (byte) 1);
        }

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
     * ItemStack が装填済みフックショットの表示状態を持つか判定します。
     *
     * @param item 判定対象
     * @return 装填済み表示を適用する場合は {@code true}
     */
    public static boolean isHookshotLoaded(@NotNull ItemStack item) {
        if (!item.hasItemMeta()) {
            return false;
        }
        Byte loaded = item.getItemMeta().getPersistentDataContainer()
                .get(KEY_HOOKSHOT_LOADED, PersistentDataType.BYTE);
        return loaded != null && loaded != 0;
    }

    /**
     * ItemStack に埋め込まれた装備スロット種別を取得します。
     *
     * @param item 判定対象
     * @return 装備スロット種別。装備でなければ {@code null}
     */
    public static @Nullable String getEquipmentSlot(@NotNull ItemStack item) {
        if (!item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer()
                .get(KEY_EQUIPMENT_SLOT, PersistentDataType.STRING);
    }

    /**
     * ItemStack が主武器として定義された装備かを返します。
     *
     * @param item 判定対象
     * @return 主武器の場合は {@code true}
     */
    public static boolean isWeapon(@NotNull ItemStack item) {
        return ItemEquipmentSlot.WEAPON.name().equals(getEquipmentSlot(item));
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
                rarityColor + "◆ " + decoratedName + ColorCodeUtil.RESET));

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

        // ヘッダー
        lore.add(ColorCodeUtil.DARK_GRAY + DisplaySeparators.SECTION);
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

        // equipment / rune のカテゴリ固有情報
        if (model.getEquipment() != null) {
            appendEquipmentLore(lore, model.getEquipment());
        }
        if (model.getRune() != null) {
            appendRuneLore(lore, model.getRune());
        }
        if (model.getConsumable() != null) {
            appendConsumableLore(lore, model.getConsumable());
        }
        if (model.getSigil() != null) {
            appendSigilLore(lore, model.getSigil());
            lore.add(ColorCodeUtil.LIGHT_PURPLE + "スキルマネージャーで合成");
            lore.add(ColorCodeUtil.RED + "装着後は取り外せません");
            lore.add("");
        }

        appendSaleValueLore(lore, model);

        // フッター
        lore.add(ColorCodeUtil.DARK_GRAY + DisplaySeparators.SECTION);
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

    /** シジル自体を手に取った時点で、装着時に得る能力を数値と日本語名で示します。 */
    private void appendSigilLore(@NotNull List<String> lore, @NotNull ItemSigil sigil) {
        lore.add(ColorCodeUtil.LIGHT_PURPLE + "❖ シジル効果");
        if (sigil.getModifiers().isEmpty()) {
            lore.add(ColorCodeUtil.GRAY + " ▸ 固有効果は説明欄を確認");
            return;
        }
        for (ItemSigilModifier modifier : sigil.getModifiers()) {
            StatusType statusType = resolveStatusTypeOrNull(modifier.getStatus());
            String statusName = resolveStatusDisplayName(modifier.getStatus(), statusType);
            String suffix = statusType == null ? "" : statusType.getSuffix();
            String value = formatStatValue(modifier.getValue());
            if (modifier.getValue() > 0.0D) {
                value = "+" + value;
            }
            lore.add(ColorCodeUtil.GRAY + " ▸ " + ColorCodeUtil.AQUA
                + statusName + ColorCodeUtil.DARK_GRAY + " : " + ColorCodeUtil.WHITE + value + suffix);
        }
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
        String accessorySlotLabel = toAccessorySlotLabel(equipment);
        if (accessorySlotLabel != null) {
            lore.add(ColorCodeUtil.GRAY + " ▸ アクセサリ枠: " + ColorCodeUtil.WHITE
                    + accessorySlotLabel);
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
                    + formatRequiredClasses(equipment));
        }

        // ステータス
        if (!equipment.getStats().isEmpty()) {
            lore.add("");
            lore.add(ColorCodeUtil.YELLOW + " ▸ ステータス補正");
            for (ItemEquipmentStat stat : equipment.getStats()) {
                StatusType statusType = resolveStatusTypeOrNull(stat.getStatus());
                String statColor = statusCategoryColor(stat.getStatus(), statusType);
                String displayName = resolveStatusDisplayName(stat.getStatus(), statusType, stat.getType());
                appendStatLore(lore,
                        statColor,
                        displayName,
                        formatStatValueWithType(stat.getType(), statusType, stat.displayValue()),
                        formatRandomRangeLabel(stat, stat.getMin(), stat.getMax()),
                        null);
            }
        }

        // 耐久値
        if (equipment.getDurability() != null) {
            int durabilityMax = equipment.getDurability().getMax();
            lore.add(formatDurabilityBarLore(durabilityMax, durabilityMax));
        }

        appendSetEffectLore(lore, equipment);
        lore.add("");
    }

    /**
     * ルーンの装着条件と固定ステータスを Lore に追加します。
     * ステータスの表示名・補正方式・数値書式は装備と共通化し、内部スロット ID は表示しません。
     *
     * @param lore 追加先の Lore 行
     * @param rune ルーン定義
     */
    private void appendRuneLore(@NotNull List<String> lore, @NotNull ItemRune rune) {
        lore.add(ColorCodeUtil.GOLD + "❖ ルーン効果");
        lore.add(ColorCodeUtil.GRAY + " ▸ 対象スロット: " + ColorCodeUtil.WHITE
                + formatRuneTargetSlots(rune));
        if (!rune.getTargetTags().isEmpty()) {
            lore.add(ColorCodeUtil.GRAY + " ▸ 対象種別: " + ColorCodeUtil.WHITE
                    + formatRuneTargetTags(rune));
        }
        if (rune.getRequiredEnhanceLevel() > 0) {
            lore.add(ColorCodeUtil.GRAY + " ▸ 必要強化: " + ColorCodeUtil.YELLOW
                    + "+" + rune.getRequiredEnhanceLevel());
        }

        if (!rune.getStats().isEmpty()) {
            lore.add("");
            lore.add(ColorCodeUtil.YELLOW + " ▸ ステータス補正");
            for (ItemEquipmentStat stat : rune.getStats()) {
                StatusType statusType = resolveStatusTypeOrNull(stat.getStatus());
                String statColor = statusCategoryColor(stat.getStatus(), statusType);
                String displayName = resolveStatusDisplayName(stat.getStatus(), statusType, stat.getType());
                appendStatLore(
                        lore,
                        statColor,
                        displayName,
                        formatStatValueWithType(stat.getType(), statusType, stat.displayValue()),
                        null,
                        null);
            }
        }
        lore.add("");
    }

    /**
     * ルーンの対象スロットをプレイヤー向け名称へ変換します。
     *
     * @param rune ルーン定義
     * @return 対象スロットの表示文字列
     */
    private @NotNull String formatRuneTargetSlots(@NotNull ItemRune rune) {
        if (rune.getTargetSlots().isEmpty()) {
            return "不明な装備枠";
        }
        if (rune.getTargetSlots().stream()
                .anyMatch(slot -> slot != null && "ANY".equalsIgnoreCase(slot.trim()))) {
            return "全スロット";
        }
        return rune.getTargetSlots().stream()
                .map(this::toRuneTargetSlotLabel)
                .distinct()
                .collect(java.util.stream.Collectors.joining(" / "));
    }

    /**
     * ルーンの対象装備タグをプレイヤー向け名称へ変換します。
     *
     * @param rune ルーン定義
     * @return 対象装備タグの表示文字列
     */
    private @NotNull String formatRuneTargetTags(@NotNull ItemRune rune) {
        return rune.getTargetTags().stream()
                .map(this::toRuneTargetTagLabel)
                .distinct()
                .collect(java.util.stream.Collectors.joining(" / "));
    }

    /**
     * ルーンの対象装備タグ1件をプレイヤー向け名称へ変換します。
     *
     * @param rawTag filebase/API由来の対象装備タグ値
     * @return 対象装備タグの表示名
     */
    private @NotNull String toRuneTargetTagLabel(@Nullable String rawTag) {
        if (rawTag == null || rawTag.isBlank()) {
            return "不明な装備種別";
        }
        String normalizedTag = rawTag.trim();
        MasterTagIds.Definition definition = MasterTagIds.find(normalizedTag);
        if (definition == null) {
            definition = MasterTagIds.find(normalizedTag.toUpperCase(Locale.ROOT));
        }
        return definition == null ? "不明な装備種別" : definition.displayName();
    }

    /**
     * ルーンの対象スロット1件をプレイヤー向け名称へ変換します。
     *
     * @param rawSlot filebase/API由来の対象スロット値
     * @return 対象スロットの表示名
     */
    private @NotNull String toRuneTargetSlotLabel(@Nullable String rawSlot) {
        if (rawSlot == null || rawSlot.isBlank()) {
            return "不明な装備枠";
        }
        if ("ANY".equalsIgnoreCase(rawSlot.trim())) {
            return "全スロット";
        }
        ItemEquipmentSlot slot = ItemEquipmentSlot.fromApiValue(rawSlot);
        return slot == ItemEquipmentSlot.UNKNOWN ? "不明な装備枠" : slot.getDisplayName();
    }

    /**
     * 装備に紐づくセット効果の発動条件と効果値を Lore に追加します。
     * セット効果を解決できない場合は、内部 ID をプレイヤーへ表示しません。
     */
    private void appendSetEffectLore(@NotNull List<String> lore, @NotNull ItemEquipment equipment) {
        appendSetEffectLore(lore, equipment, null);
    }

    private void appendSetEffectLore(
            @NotNull List<String> lore,
            @NotNull ItemEquipment equipment,
            @Nullable Map<String, Integer> equippedSetCounts
    ) {
        String setId = equipment.getSetId();
        if (setId == null || setId.isBlank()) {
            return;
        }
        SetEffect setEffect = itemService.findSetEffectById(setId.trim());
        if (setEffect == null || setEffect.getPieces().isEmpty()) {
            return;
        }

        List<SetEffectPiece> pieces = setEffect.getPieces().stream()
                .filter(piece -> piece.getCount() > 0)
                .toList();
        if (pieces.isEmpty()) {
            return;
        }

        String rawName = setEffect.getName();
        String displayName = rawName == null || rawName.isBlank()
                ? "セット効果"
                : ColorCodeUtil.translateAlternateColorCodes(rawName);
        lore.add("");
        lore.add(ColorCodeUtil.LIGHT_PURPLE + "❖ セット効果: "
                + ColorCodeUtil.WHITE + displayName);
        for (SetEffectPiece piece : pieces) {
            boolean dynamicDisplay = equippedSetCounts != null;
            int equippedCount = dynamicDisplay
                    ? equippedSetCounts.getOrDefault(setId.trim(), 0)
                    : 0;
            boolean active = !dynamicDisplay || equippedCount >= piece.getCount();
            if (!dynamicDisplay) {
                lore.add(ColorCodeUtil.GRAY + " ▸ " + ColorCodeUtil.YELLOW
                        + piece.getCount() + "セット効果");
            } else if (active) {
                lore.add(ColorCodeUtil.GRAY + " ▸ " + ColorCodeUtil.YELLOW
                        + piece.getCount() + "セット効果 " + ColorCodeUtil.GREEN + "+");
            } else {
                lore.add(ColorCodeUtil.GRAY + " ▸ " + piece.getCount() + "セット効果 -");
            }
            if (piece.getStats().isEmpty()) {
                lore.add((active ? ColorCodeUtil.DARK_GRAY : ColorCodeUtil.GRAY) + "   ─ 効果なし");
                continue;
            }
            for (SetEffectStat stat : piece.getStats()) {
                StatusType statusType = resolveStatusTypeOrNull(stat.getStatus());
                String statColor = statusCategoryColor(stat.getStatus(), statusType);
                String statusName = resolveStatusDisplayName(stat.getStatus(), statusType, stat.getType());
                if (!dynamicDisplay || active) {
                    lore.add(ColorCodeUtil.DARK_GRAY + "   ▹ "
                            + statColor + statusName
                            + ColorCodeUtil.DARK_GRAY + " : "
                            + formatStatValueWithType(stat.getType(), statusType, stat.getValue()));
                } else {
                    lore.add(ColorCodeUtil.GRAY + "   ▹ "
                            + ColorCodeUtil.toPlainText(statusName, "効果")
                            + ColorCodeUtil.GRAY + " : "
                            + ColorCodeUtil.toPlainText(
                                    formatStatValueWithType(stat.getType(), statusType, stat.getValue()), "-"));
                }
            }
        }
    }

    private void appendSaleValueLore(@NotNull List<String> lore, @NotNull ItemModel model) {
        if (model.getUnSellable() || isCurrencyItem(model)) {
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
            case "EN", "ENERGY", "MAX_ENERGY" -> "ENG";
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
     * LootService のキャッシュを優先し、未登録時は単体 API 取得を試みます。
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
        return buildLoreForEquipmentInstance(model, instance, false);
    }

    private @NotNull List<String> buildLoreForEquipmentInstance(
            @NotNull ItemModel model,
            @NotNull EquipmentInstance instance,
            boolean hookshotLoaded
    ) {
        return buildLoreForEquipmentInstance(model, instance, hookshotLoaded, null);
    }

    private @NotNull List<String> buildLoreForEquipmentInstance(
            @NotNull ItemModel model,
            @NotNull EquipmentInstance instance,
            boolean hookshotLoaded,
            @Nullable Map<String, Integer> equippedSetCounts
    ) {
        List<String> lore = new ArrayList<>();

        lore.add(ColorCodeUtil.DARK_GRAY + DisplaySeparators.SECTION);
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
            String accessorySlotLabel = toAccessorySlotLabel(eq);
            if (accessorySlotLabel != null) {
                lore.add(ColorCodeUtil.GRAY + " ▸ アクセサリ枠: " + ColorCodeUtil.WHITE
                        + accessorySlotLabel);
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
                        + formatRequiredClasses(eq));
            }
            if (hookshotLoaded && isHookshotEquipment(model)) {
                lore.add(ColorCodeUtil.GREEN + " ▸ フック装填済み");
            }

            // --- transcendence 状態変化表示 ---
            if (instance.getTranscendenceRank() > 0) {
                ItemEquipmentTranscendence currentTrans = eq.getTranscendence().stream()
                        .filter(t -> t.getRank() == instance.getTranscendenceRank())
                        .findFirst().orElse(null);
                boolean hasTranscendenceName = currentTrans != null
                        && currentTrans.getName() != null
                        && !currentTrans.getName().isBlank();
                String transName = hasTranscendenceName
                        ? currentTrans.getName() : "ランク " + instance.getTranscendenceRank();
                String rankNote = hasTranscendenceName
                        ? ColorCodeUtil.GRAY + " (ランク" + instance.getTranscendenceRank() + ")"
                        : "";
                lore.add(ColorCodeUtil.LIGHT_PURPLE + " ▸ 状態変化: " + ColorCodeUtil.WHITE
                        + "【" + transName + ColorCodeUtil.RESET + ColorCodeUtil.YELLOW + "】" + rankNote);
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

                // enhance 累積計算 (status#type → [minAccum, maxAccum])
                Map<String, double[]> enhanceAccum = calculateEnhanceStats(eq, instance.getEnhanceLevel());

                for (var roll : instance.getStatRolls()) {
                    ItemEquipmentStat statDefinition = eq.findStatDefinition(roll);
                    ItemEquipmentStatType rollType = statDefinition == null
                            ? ItemEquipmentStatType.FLAT
                            : statDefinition.getType();
                    double baseMin = parseStatDouble(roll.getMin());
                    double baseMax = parseStatDouble(roll.getMax());

                    String enhanceKey = roll.getStatus() + "#" + rollType.name();
                    double[] enhAdd = enhanceAccum.getOrDefault(enhanceKey, new double[]{0.0, 0.0});

                    double totalMin = baseMin + enhAdd[0];
                    double totalMax = baseMax + enhAdd[1];

                    StatusType statusType = resolveStatusTypeOrNull(roll.getStatus());
                    String statColor = statusCategoryColor(roll.getStatus(), statusType);
                    String displayValue = totalMin == totalMax
                            ? formatStatValueWithType(rollType, statusType, totalMin)
                            : formatStatRange(rollType, statusType, totalMin, totalMax);
                    String randomRangeLabel = formatRandomRangeLabel(statDefinition, baseMin, baseMax);

                    // enhance 加算分の表示注釈
                    String enhanceLabel = (enhAdd[0] != 0.0 || enhAdd[1] != 0.0)
                            ? formatStatValueWithType(rollType, statusType, enhAdd[0])
                                    + (enhAdd[0] != enhAdd[1] ? "～" + formatStatValueWithType(rollType, statusType, enhAdd[1]) : "")
                            : null;

                    String displayName = resolveStatusDisplayName(roll.getStatus(), statusType, rollType);
                    appendStatLore(lore, statColor, displayName, displayValue, randomRangeLabel, enhanceLabel);
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
                    StatusType statusType = resolveStatusTypeOrNull(status);
                    String statColor = statusCategoryColor(status, statusType);
                    String displayValue = enhAdd[0] == enhAdd[1]
                            ? formatStatValueWithType(type, statusType, enhAdd[0])
                            : formatStatRange(type, statusType, enhAdd[0], enhAdd[1]);
                    String displayName = resolveStatusDisplayName(status, statusType, type);
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
                        ItemEquipmentStatType enchantType = "SCALAR".equals(enchant.getType())
                                ? ItemEquipmentStatType.SCALAR : ItemEquipmentStatType.FLAT;
                        StatusType statusType = resolveStatusTypeOrNull(enchant.getStatus());
                        String statColor = statusCategoryColor(enchant.getStatus(), statusType);
                        String displayName = resolveStatusDisplayName(enchant.getStatus(), statusType, enchantType);
                        lore.add(ColorCodeUtil.DARK_GRAY + " [" + (enchant.getSlotIndex() + 1) + "] "
                                + statColor + displayName
                                + ColorCodeUtil.DARK_GRAY + " : "
                                + formatStatValueWithType(enchantType, statusType, enchant.getValue()));
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
                        ItemModel runeModel = itemService.findLoadedById(rune.getItemId());
                        String runeName = runeModel == null || runeModel.getName() == null || runeModel.getName().isBlank()
                            ? "不明なルーン" : runeModel.getName();
                        lore.add(ColorCodeUtil.GREEN + " ● " + ColorCodeUtil.WHITE + runeName);
                        if (runeModel != null && runeModel.getRune() != null) {
                            for (ItemEquipmentStat stat : runeModel.getRune().getStats()) {
                                ItemEquipmentStatType statType = stat.getType();
                                StatusType statusType = resolveStatusTypeOrNull(stat.getStatus());
                                lore.add(ColorCodeUtil.DARK_GRAY + "    ▹ "
                                    + statusCategoryColor(stat.getStatus(), statusType)
                                    + resolveStatusDisplayName(stat.getStatus(), statusType, statType)
                                    + ColorCodeUtil.DARK_GRAY + " : "
                                    + formatStatValueWithType(statType, statusType, stat.displayValue()));
                            }
                        }
                    } else {
                        lore.add(ColorCodeUtil.DARK_GRAY + " ○ 空きスロット");
                    }
                }
            }

            // --- 耐久値 ---
            if (instance.getDurabilityMax() > 0) {
                lore.add("");
                lore.add(formatDurabilityLore(instance));
            }
            appendSetEffectLore(lore, eq, equippedSetCounts);
            lore.add("");
        }

        appendSaleValueLore(lore, model);
        lore.add(ColorCodeUtil.DARK_GRAY + DisplaySeparators.SECTION);
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
     * 小数点以下2桁で切り捨て、末尾のゼロを除去した十進数表現を返します。
     */
    private @NotNull String formatStatValue(double value) {
        return BigDecimal.valueOf(value)
                .setScale(2, RoundingMode.DOWN)
                .stripTrailingZeros()
                .toPlainString();
    }

    private @NotNull String formatRequiredClasses(@NotNull ItemEquipment equipment) {
        return String.join(", ", equipment.getRequiredClasses().stream()
            .map(requirement -> resolveRequiredClassDisplayName(requirement.getClassId())
                    + ColorCodeUtil.GRAY + " Lv." + ColorCodeUtil.YELLOW
                    + Math.max(1, requirement.getLevel()))
            .toList());
    }

    private @NotNull String resolveRequiredClassDisplayName(@Nullable String classId) {
        if (classId == null || classId.isBlank() || playerClassService == null) {
            return "未登録のクラス";
        }
        String displayName = playerClassService.getDisplayName(classId);
        if (displayName == null || displayName.isBlank()) {
            return "未登録のクラス";
        }
        String plainName = ColorCodeUtil.toPlainText(displayName, "").trim();
        return plainName.isBlank() || plainName.equalsIgnoreCase(classId.trim())
                ? "未登録のクラス"
                : displayName;
    }

    private @NotNull String formatStatValueWithType(
            @NotNull ItemEquipmentStatType type, @Nullable StatusType statusType, double value) {
        if (type == ItemEquipmentStatType.SCALAR) {
            return STATUS_VALUE_COLOR + "×" + formatStatValue(value * 100.0D) + "%";
        }
        String suffix = statusType != null && statusType.isPercentage() ? "%" : "";
        return STATUS_VALUE_COLOR + "+" + formatStatValue(value) + suffix;
    }

    private @NotNull String formatStatValueWithType(
            @NotNull ItemEquipmentStatType type, @Nullable StatusType statusType, @NotNull String value) {
        String normalized = value.trim();
        if (normalized.contains("~") || normalized.contains("～")) {
            String[] parts = normalized.split("[~～]", 2);
            if (parts.length == 2) {
                return formatStatValueWithType(type, statusType, parseStatDouble(parts[0].trim()))
                        + ColorCodeUtil.DARK_GRAY + "～"
                        + formatStatValueWithType(type, statusType, parseStatDouble(parts[1].trim()));
            }
        }
        return formatStatValueWithType(type, statusType, parseStatDouble(normalized));
    }

    private @NotNull String formatStatRange(
            @NotNull ItemEquipmentStatType type, @Nullable StatusType statusType, double min, double max) {
        return formatStatValueWithType(type, statusType, min)
                + ColorCodeUtil.DARK_GRAY + "～"
                + formatStatValueWithType(type, statusType, max);
    }

    /**
     * 装備マスタに指定された min/max の乱数範囲を Lore 用に整形します。
     * 強化加算値は含めず、乱数指定がない場合は空文字列を返します。
     *
     * @param statDefinition 装備マスタのステータス定義
     * @param baseMin        装備インスタンスに保存された下限値
     * @param baseMax        装備インスタンスに保存された上限値
     * @return 乱数範囲ラベル、または null
     */
    private @Nullable String formatRandomRangeLabel(
            @Nullable ItemEquipmentStat statDefinition, double baseMin, double baseMax) {
        if (statDefinition == null
                || (!isRandomRange(statDefinition.getRawMin()) && !isRandomRange(statDefinition.getRawMax()))) {
            return null;
        }

        String min = formatRandomRangeBound(statDefinition.getRawMin(), baseMin);
        String max = formatRandomRangeBound(statDefinition.getRawMax(), baseMax);
        return min + "～" + max;
    }

    /** ステータスの主値と補足情報を、読みやすい複数行の Lore として追加します。 */
    private void appendStatLore(
            @NotNull List<String> lore,
            @NotNull String statColor,
            @NotNull String displayName,
            @NotNull String displayValue,
            @Nullable String randomRange,
            @Nullable String enhanceValue) {
        lore.add(ColorCodeUtil.DARK_GRAY + "   ▹ " + statColor + displayName
                + ColorCodeUtil.DARK_GRAY + " : " + displayValue
                + (enhanceValue == null ? "" : ColorCodeUtil.YELLOW + " [" + enhanceValue
                        + ColorCodeUtil.RESET + ColorCodeUtil.YELLOW + "]"));
        if (randomRange != null) {
            lore.add(ColorCodeUtil.GRAY + "   └" + randomRange);
        }
    }

    /** 指定値が min~max の乱数範囲表現か判定します。 */
    private boolean isRandomRange(@Nullable String value) {
        return value != null && (value.contains("~") || value.contains("～"));
    }

    /** StatusTypeカテゴリに応じた既存のステータス名称カラーを返します。 */
    private @NotNull String statusCategoryColor(@NotNull String rawStatus, @Nullable StatusType type) {
        if (type == null) {
            if ("MINING_SPEED".equals(normalizeStatusKey(rawStatus))) {
                return ColorCodeUtil.YELLOW;
            }
            return ColorCodeUtil.AQUA;
        }
        return type.legacyColor();
    }

    /** 生値または装備インスタンスの値を、乱数範囲の片側として整形します。 */
    private @NotNull String formatRandomRangeBound(@Nullable String rawValue, double fallback) {
        if (rawValue == null || rawValue.isBlank()) {
            return formatStatValue(fallback);
        }

        String[] parts = rawValue.trim().split("[~～]", 2);
        if (parts.length == 1) {
            return formatRandomRangeNumber(parts[0]);
        }
        return formatRandomRangeNumber(parts[0]) + "-" + formatRandomRangeNumber(parts[1]);
    }

    /** 乱数範囲表示の数値を末尾ゼロなしの表記へ整形します。 */
    private @NotNull String formatRandomRangeNumber(@NotNull String value) {
        String normalized = value.trim();
        try {
            return formatStatValue(Double.parseDouble(normalized));
        } catch (NumberFormatException ignored) {
            return normalized;
        }
    }

    private @NotNull String formatDurabilityLore(@NotNull EquipmentInstance instance) {
        return formatDurabilityBarLore(instance.getDurabilityValue(), instance.getDurabilityMax());
    }

    static @NotNull String formatDurabilityBarLore(int value, int max) {
        double durabilityRate = max <= 0
                ? 0.0D
                : Math.clamp((double) value / max, 0.0D, 1.0D);
        int filledLength = (int) Math.round(durabilityRate * DURABILITY_BAR_LENGTH);
        StringBuilder bar = new StringBuilder(DURABILITY_BAR_LENGTH + 16);
        bar.append(durabilityBarColor(durabilityRate, max));
        bar.repeat(DURABILITY_BAR_CHAR, filledLength);
        bar.append(ColorCodeUtil.GRAY);
        bar.repeat(DURABILITY_BAR_CHAR, DURABILITY_BAR_LENGTH - filledLength);
        return ColorCodeUtil.GRAY + " ▸ 耐久値: " + bar;
    }

    private boolean isHookshotEquipment(@NotNull ItemModel model) {
        ItemEquipment equipment = model.getEquipment();
        return equipment != null
                && equipment.getSlot() == ItemEquipmentSlot.TOOL
                && MasterTagIds.Equipment.HOOKSHOT.equalsIgnoreCase(equipment.getTag());
    }

    private static @NotNull String durabilityBarColor(double durabilityRate, int max) {
        if (max <= 0) {
            return ColorCodeUtil.GRAY;
        }
        if (durabilityRate >= DURABILITY_DARK_GREEN_THRESHOLD) {
            return ColorCodeUtil.DARK_GREEN;
        }
        if (durabilityRate >= DURABILITY_GREEN_THRESHOLD) {
            return ColorCodeUtil.GREEN;
        }
        if (durabilityRate >= DURABILITY_YELLOW_THRESHOLD) {
            return ColorCodeUtil.YELLOW;
        }
        return ColorCodeUtil.RED;
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
    private @NotNull String resolveStatusDisplayName(
            @NotNull String rawStatus,
            @Nullable StatusType type,
            @NotNull ItemEquipmentStatType statType) {
        String displayName = resolveStatusDisplayName(rawStatus, type);
        return statType == ItemEquipmentStatType.SCALAR ? "最終" + displayName + "乗数" : displayName;
    }

    private @NotNull String resolveStatusDisplayName(@NotNull String rawStatus, @Nullable StatusType type) {
        if (type != null) {
            return type.getDisplayName();
        }

        String normalized = normalizeStatusKey(rawStatus);
        if (normalized.isEmpty()) {
            return "未登録のステータス";
        }
        if ("MINING_SPEED".equals(normalized)) {
            return "採集速度";
        }
        return "未登録のステータス";
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
     * アクセサリの equipment tag を種類別スロット名へ変換します。
     *
     * @param equipment 装備定義
     * @return 共有タグの表示名。アクセサリ以外または未登録タグの場合は {@code null}
     */
    private @Nullable String toAccessorySlotLabel(@NotNull ItemEquipment equipment) {
        if (equipment.getSlot() != ItemEquipmentSlot.ACCESSORY) {
            return null;
        }

        AccessorySlotType slotType = AccessorySlotType.fromEquipmentTag(equipment.getTag());
        if (slotType == null || slotType.getEquipmentTag() == null) {
            return null;
        }

        MasterTagIds.Definition definition = MasterTagIds.find(slotType.getEquipmentTag());
        return definition == null ? null : definition.displayName();
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
        Material material = MaterialNameResolver.match(iconName);
        if (material == null || material == Material.AIR || !material.isItem()) {
            Logger.log(LogId.W_5210, iconName);
            return null;
        }
        return material;
    }

    private static boolean isSmithingTemplate(@NotNull Material material) {
        return material.name().endsWith("_SMITHING_TEMPLATE");
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
        if (model.getEquipment() != null && model.getEquipment().getSlot() != null) {
            pdc.set(KEY_EQUIPMENT_SLOT, PersistentDataType.STRING, model.getEquipment().getSlot().name());
        }
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
