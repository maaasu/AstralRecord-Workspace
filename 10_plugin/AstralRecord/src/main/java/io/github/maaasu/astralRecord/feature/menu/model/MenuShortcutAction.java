package io.github.maaasu.astralRecord.feature.menu.model;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Craft slot actions shown in the player's crafting grid.
 */
public enum MenuShortcutAction {
    NONE("NONE", "未設定", Material.GRAY_DYE, NamedTextColor.GRAY, false),
    MAIN_MENU("MAIN_MENU", "メニュー", Material.NETHER_STAR, NamedTextColor.AQUA, false),
    STATUS("STATUS", "ステータス", Material.PLAYER_HEAD, NamedTextColor.GREEN, false),
    INVENTORY_CURRENCY("INVENTORY_CURRENCY", MenuIconDefinition.CURRENCY.getDisplayNameJa(), MenuIconDefinition.CURRENCY.getMaterial(), MenuIconDefinition.CURRENCY.getColor(), true),
    EQUIPMENT_GUI("EQUIPMENT_GUI", MenuIconDefinition.EQUIPMENT.getDisplayNameJa(), MenuIconDefinition.EQUIPMENT.getMaterial(), MenuIconDefinition.EQUIPMENT.getColor(), false);

    private final String code;
    private final String displayNameJa;
    private final Material material;
    private final NamedTextColor color;
    private final boolean currencyAction;

    MenuShortcutAction(
        @NotNull String code,
        @NotNull String displayNameJa,
        @NotNull Material material,
        @NotNull NamedTextColor color,
        boolean currencyAction
    ) {
        this.code = code;
        this.displayNameJa = displayNameJa;
        this.material = material;
        this.color = color;
        this.currencyAction = currencyAction;
    }

    public @NotNull String getCode() {
        return code;
    }

    public @NotNull String getDisplayNameJa() {
        return displayNameJa;
    }

    public @NotNull Material getMaterial() {
        return material;
    }

    public @NotNull NamedTextColor getColor() {
        return color;
    }

    public boolean isCurrencyAction() {
        return currencyAction;
    }

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

    public static @NotNull MenuShortcutAction defaultForSlot(int slotIndex) {
        return switch (slotIndex) {
            case 0 -> STATUS;
            case 1 -> NONE;
            case 2 -> INVENTORY_CURRENCY;
            case 3 -> EQUIPMENT_GUI;
            default -> NONE;
        };
    }
}
