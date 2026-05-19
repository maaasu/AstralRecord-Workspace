package io.github.maaasu.astralRecord.feature.menu.model;

import io.github.maaasu.astralRecord.feature.inventory.model.InventoryType;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * クラフトスロットへ表示できるショートカット項目を表します。
 */
public enum MenuShortcutAction {
    /** 未設定スロット。 */
    NONE("NONE", "未設定", Material.GRAY_DYE, NamedTextColor.GRAY, null, false),
    /** メインメニューを開く。 */
    MAIN_MENU("MAIN_MENU", "メニュー", Material.NETHER_STAR, NamedTextColor.AQUA, null, false),
    /** ノーマルインベントリを表示する。 */
    INVENTORY_NORMAL("INVENTORY_NORMAL", InventoryType.NORMAL.getDisplayNameJa(), Material.CHEST, NamedTextColor.YELLOW, InventoryType.NORMAL, false),
    /** 通貨 GUI を表示する。 */
    INVENTORY_CURRENCY("INVENTORY_CURRENCY", "通貨", Material.EMERALD, NamedTextColor.GREEN, null, true),
    /** 装備インベントリを表示する。 */
    INVENTORY_EQUIPMENT("INVENTORY_EQUIPMENT", InventoryType.EQUIPMENT.getDisplayNameJa(), Material.IRON_CHESTPLATE, NamedTextColor.GOLD, InventoryType.EQUIPMENT, false),
    /** ルーンインベントリを表示する。 */
    INVENTORY_RUNE("INVENTORY_RUNE", InventoryType.RUNE.getDisplayNameJa(), Material.AMETHYST_SHARD, NamedTextColor.LIGHT_PURPLE, InventoryType.RUNE, false);

    private final String code;
    private final String displayNameJa;
    private final Material material;
    private final NamedTextColor color;
    private final InventoryType inventoryType;
    private final boolean currencyAction;

    MenuShortcutAction(
        @NotNull String code,
        @NotNull String displayNameJa,
        @NotNull Material material,
        @NotNull NamedTextColor color,
        @Nullable InventoryType inventoryType,
        boolean currencyAction
    ) {
        this.code = code;
        this.displayNameJa = displayNameJa;
        this.material = material;
        this.color = color;
        this.inventoryType = inventoryType;
        this.currencyAction = currencyAction;
    }

    /**
     * 保存用コードを返します。
     *
     * @return 保存用コード
     */
    public @NotNull String getCode() {
        return code;
    }

    /**
     * 日本語表示名を返します。
     *
     * @return 日本語表示名
     */
    public @NotNull String getDisplayNameJa() {
        return displayNameJa;
    }

    /**
     * 表示アイコンの素材を返します。
     *
     * @return 表示アイコン素材
     */
    public @NotNull Material getMaterial() {
        return material;
    }

    /**
     * 表示名の色を返します。
     *
     * @return 表示名の色
     */
    public @NotNull NamedTextColor getColor() {
        return color;
    }

    /**
     * 対応するインベントリタイプを返します。
     *
     * @return インベントリ表示項目なら対象タイプ、それ以外は null
     */
    public @Nullable InventoryType getInventoryType() {
        return inventoryType;
    }

    /**
     * 通貨 GUI を開くショートカットかどうかを返します。
     *
     * @return 通貨 GUI を開く場合 true
     */
    public boolean isCurrencyAction() {
        return currencyAction;
    }

    /**
     * 指定コードからショートカット項目を復元します。
     *
     * @param code 保存用コード
     * @return 対応する項目。見つからない場合は {@link #NONE}
     */
    public static @NotNull MenuShortcutAction fromCode(@Nullable String code) {
        if (code == null || code.isBlank()) {
            return NONE;
        }
        for (MenuShortcutAction action : values()) {
            if (action.code.equalsIgnoreCase(code) || action.name().equalsIgnoreCase(code)) {
                return action;
            }
        }
        return NONE;
    }

    /**
     * 新規プレイヤー向けの初期割り当てを返します。
     *
     * @param slotIndex 0始まりのショートカットスロット番号
     * @return 初期ショートカット項目
     */
    public static @NotNull MenuShortcutAction defaultForSlot(int slotIndex) {
        return switch (slotIndex) {
            case 0 -> INVENTORY_NORMAL;
            case 1 -> INVENTORY_EQUIPMENT;
            case 2 -> INVENTORY_RUNE;
            case 3 -> INVENTORY_CURRENCY;
            default -> NONE;
        };
    }
}
