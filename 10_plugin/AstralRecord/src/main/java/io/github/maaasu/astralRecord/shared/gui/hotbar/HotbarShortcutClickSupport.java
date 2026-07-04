package io.github.maaasu.astralRecord.shared.gui.hotbar;

import io.github.maaasu.astralRecord.feature.inventory.service.InventoryClickGuard;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.shared.gui.sound.GuiSound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.NotNull;

/**
 * Plugin GUI 上でプレイヤーインベントリ側ホットバーショートカットのクリック処理を共通化します。
 */
public final class HotbarShortcutClickSupport {
    private static final int HOTBAR_MIN_SLOT = 0;
    private static final int HOTBAR_MAX_SLOT = 8;
    private static final int HOTBAR_CLOSE_SLOT = 4;

    private HotbarShortcutClickSupport() {
        // utility class
    }

    public static boolean handle(
        @NotNull InventoryClickEvent event,
        @NotNull Player player,
        @NotNull InventoryService inventoryService
    ) {
        if (!(event.getClickedInventory() instanceof PlayerInventory)) {
            return false;
        }
        int slot = event.getSlot();
        if (slot < HOTBAR_MIN_SLOT || slot > HOTBAR_MAX_SLOT) {
            return false;
        }

        event.setCancelled(true);
        var astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null || !inventoryService.isHotbarShortcutMode(astPlayer)) {
            GuiSound.DENY.play(player);
            return true;
        }
        if (!inventoryService.getClickGuard().tryAcquire(
            astPlayer.getAccount().getUuid(),
            InventoryClickGuard.ClickAction.HOTBAR_SHORTCUT
        )) {
            return true;
        }

        boolean handled = inventoryService.handleHotbarShortcutClick(astPlayer, slot);
        if (!handled) {
            GuiSound.DENY.play(player);
            return true;
        }
        if (slot != HOTBAR_CLOSE_SLOT) {
            GuiSound.SELECT.play(player);
        }
        return true;
    }
}
