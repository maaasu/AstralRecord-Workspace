package io.github.maaasu.astralRecord.feature.menu.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Craft slot actions shown in the player's crafting grid.
 */
public enum MenuShortcutAction {
    NONE("NONE", MenuIconDefinition.UNSET, false),
    MAIN_MENU("MAIN_MENU", MenuIconDefinition.MAIN_MENU, false),
    STATUS("STATUS", MenuIconDefinition.ACCOUNT_INFO, false),
    RETURN_TO_BASE("RETURN_TO_BASE", MenuIconDefinition.RETURN_TO_BASE, false),
    INVENTORY_CURRENCY("INVENTORY_CURRENCY", MenuIconDefinition.CURRENCY, true),
    EQUIPMENT_GUI("EQUIPMENT_GUI", MenuIconDefinition.EQUIPMENT, false);

    private final String code;
    private final MenuIconDefinition iconDefinition;
    private final boolean currencyAction;

    MenuShortcutAction(
        @NotNull String code,
        @NotNull MenuIconDefinition iconDefinition,
        boolean currencyAction
    ) {
        this.code = code;
        this.iconDefinition = iconDefinition;
        this.currencyAction = currencyAction;
    }

    public @NotNull String getCode() {
        return code;
    }

    public @NotNull MenuIconDefinition getIconDefinition() {
        return iconDefinition;
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
            case 1 -> RETURN_TO_BASE;
            case 2 -> INVENTORY_CURRENCY;
            case 3 -> EQUIPMENT_GUI;
            default -> NONE;
        };
    }
}
