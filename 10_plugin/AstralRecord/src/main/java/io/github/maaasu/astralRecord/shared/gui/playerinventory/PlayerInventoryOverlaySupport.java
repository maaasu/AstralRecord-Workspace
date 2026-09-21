package io.github.maaasu.astralRecord.shared.gui.playerinventory;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

/** 下段専用表示の再描画と通常画面への復元を共通化します。 */
public final class PlayerInventoryOverlaySupport {
    private PlayerInventoryOverlaySupport() { }

    /** @param inventory 判定する上段GUI @return 下段専用表示を持つ場合true */
    public static boolean isActive(Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof PlayerInventoryOverlayGuiHolder holder
            && holder.hasPlayerInventoryOverlay();
    }

    /** @param player 表示対象 @return 専用表示を描画した場合true */
    public static boolean renderIfActive(Player player) {
        if (player == null || player.getOpenInventory() == null) return false;
        Inventory inventory = player.getOpenInventory().getTopInventory();
        if (!isActive(inventory)) return false;
        ((PlayerInventoryOverlayGuiHolder) inventory.getHolder()).renderPlayerInventory(player);
        return true;
    }

    /**
     * GUIの表示成功後、専用表示を描画するか、離脱元の所持品表示を復元します。
     * @param player 表示対象
     * @param source 遷移元GUI
     */
    public static void afterOpen(Player player, Inventory source) {
        if (renderIfActive(player)) return;
        if (!isActive(source)) return;
        var astPlayer = AstPlayerCache.get(player);
        var plugin = AstralRecord.getInstance();
        if (astPlayer != null && plugin != null && plugin.getInventoryService() != null) {
            plugin.getInventoryService().applyInventoriesToGui(astPlayer);
            player.updateInventory();
        }
    }
}
