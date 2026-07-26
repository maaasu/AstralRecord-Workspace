package io.github.maaasu.astralRecord.shared.gui;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.shared.gui.sound.GuiCloseSoundPolicy;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * プラグイン管理 GUI 間の画面遷移を安全な tick 境界で実行します。
 */
public final class GuiOpenSupport {
    private static final long TRANSITION_DELAY_TICKS = 2L;
    private static final String PLUGIN_PACKAGE_PREFIX = "io.github.maaasu.astralRecord.";
    private static final Map<UUID, BukkitTask> PENDING_TRANSITIONS = new ConcurrentHashMap<>();

    private GuiOpenSupport() {
    }

    /**
     * GUI を開きます。プラグイン GUI からの遷移はクリック同期ずれを避けるため 2 tick 後に実行します。
     *
     * @param player 対象プレイヤー
     * @param inventory 開く GUI
     */
    public static void open(@NotNull Player player, @NotNull Inventory inventory) {
        Inventory source = player.getOpenInventory().getTopInventory();
        if (!isPluginGui(source)) {
            player.openInventory(inventory);
            return;
        }

        UUID playerId = player.getUniqueId();
        BukkitTask previousTask = PENDING_TRANSITIONS.remove(playerId);
        if (previousTask != null) {
            previousTask.cancel();
        }
        AstralRecord plugin = AstralRecord.getPlugin(AstralRecord.class);
        BukkitTask task = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            PENDING_TRANSITIONS.remove(playerId);
            openIfSourceIsCurrent(player, source, inventory);
        }, TRANSITION_DELAY_TICKS);
        PENDING_TRANSITIONS.put(playerId, task);
    }

    /**
     * 遷移元 GUI が引き続き開かれている場合だけ内部画面遷移を実行します。
     *
     * @param player 対象プレイヤー
     * @param source 遷移元 GUI
     * @param target 遷移先 GUI
     */
    static void openIfSourceIsCurrent(
        @NotNull Player player,
        @NotNull Inventory source,
        @NotNull Inventory target
    ) {
        if (!player.isOnline() || player.getOpenInventory().getTopInventory() != source) {
            return;
        }
        GuiCloseSoundPolicy.suppressNextCloseSound(player, source);
        player.openInventory(target);
    }

    private static boolean isPluginGui(@Nullable Inventory inventory) {
        if (inventory == null) {
            return false;
        }
        InventoryHolder holder = inventory.getHolder();
        return holder != null && holder.getClass().getName().startsWith(PLUGIN_PACKAGE_PREFIX);
    }
}
