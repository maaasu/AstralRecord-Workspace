package io.github.maaasu.astralRecord.feature.menu.view.screen;

import io.github.maaasu.astralRecord.feature.menu.model.MenuShortcutAction;
import io.github.maaasu.astralRecord.feature.menu.model.MenuShortcutSettings;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.inventory.Inventory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public final class ShortcutSettingsScreenView extends BaseMenuScreenView {
    private static final int[] SHORTCUT_SLOT_SLOTS = {20, 21, 23, 24};
    private static final int[] SHORTCUT_ACTION_SLOTS = {19, 20, 21, 23, 24, 25};

    public void renderSlotSelector(@NotNull Inventory inventory, @NotNull MenuShortcutSettings settings) {
        fill(inventory);
        for (int slot = 0; slot < MenuShortcutSettings.SLOT_COUNT; slot++) {
            MenuShortcutAction action = settings.getAction(slot);
            inventory.setItem(SHORTCUT_SLOT_SLOTS[slot], createItem(
                action.getMaterial(),
                Component.text("ショートカット " + (slot + 1) + ": " + action.getDisplayNameJa(), action.getColor()),
                List.of(Component.text("クリックして変更する", NamedTextColor.GRAY))
            ));
        }
        inventory.setItem(BACK_SLOT, backItem());
    }

    public void renderActionSelector(@NotNull Inventory inventory, @NotNull MenuShortcutAction currentAction) {
        fill(inventory);
        MenuShortcutAction[] actions = selectableShortcutActions();
        for (int i = 0; i < SHORTCUT_ACTION_SLOTS.length && i < actions.length; i++) {
            MenuShortcutAction action = actions[i];
            Component current = action == currentAction
                ? Component.text("現在の設定", NamedTextColor.GREEN)
                : Component.text("クリックして割り当てる", NamedTextColor.GRAY);
            inventory.setItem(SHORTCUT_ACTION_SLOTS[i], createItem(
                action.getMaterial(),
                Component.text(action.getDisplayNameJa(), action.getColor()),
                List.of(current)
            ));
        }
        inventory.setItem(BACK_SLOT, backItem());
    }

    public int getShortcutSettingSlotAtSlot(int rawSlot) {
        for (int i = 0; i < SHORTCUT_SLOT_SLOTS.length; i++) {
            if (SHORTCUT_SLOT_SLOTS[i] == rawSlot) {
                return i;
            }
        }
        return -1;
    }

    public @Nullable MenuShortcutAction getShortcutActionAtSlot(int rawSlot) {
        MenuShortcutAction[] actions = selectableShortcutActions();
        for (int i = 0; i < SHORTCUT_ACTION_SLOTS.length && i < actions.length; i++) {
            if (SHORTCUT_ACTION_SLOTS[i] == rawSlot) {
                return actions[i];
            }
        }
        return null;
    }

    private @NotNull MenuShortcutAction[] selectableShortcutActions() {
        return new MenuShortcutAction[] {
            MenuShortcutAction.NONE,
            MenuShortcutAction.MAIN_MENU,
            MenuShortcutAction.INVENTORY_NORMAL,
            MenuShortcutAction.INVENTORY_EQUIPMENT,
            MenuShortcutAction.INVENTORY_RUNE,
            MenuShortcutAction.INVENTORY_CURRENCY
        };
    }
}