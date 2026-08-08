package io.github.maaasu.astralRecord.shared.gui;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.shared.gui.session.GuiSessionTransitionService;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
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
    private static final Map<UUID, PendingTransition> PENDING_TRANSITIONS = new ConcurrentHashMap<>();

    private GuiOpenSupport() {
    }

    /**
     * GUI を開きます。プラグイン GUI からの遷移はクリック同期ずれを避けるため 2 tick 後に実行します。
     *
     * @param player 対象プレイヤー
     * @param inventory 開く GUI
     */
    public static void open(@NotNull Player player, @NotNull Inventory inventory) {
        open(player, inventory, () -> {
        });
    }

    /**
     * GUI を開き、実際に表示できた場合だけ完了処理を実行します。プラグイン GUI からの遷移はクリック同期ずれを避けるため 2 tick 後に実行します。
     *
     * @param player 対象プレイヤー
     * @param inventory 開く GUI
     * @param onOpened GUI 表示後に実行する処理
     */
    public static void open(@NotNull Player player, @NotNull Inventory inventory, @NotNull Runnable onOpened) {
        open(player, inventory, onOpened, () -> {
        });
    }

    /**
     * GUI を開き、遷移結果を一度だけ通知します。プラグイン GUI からの遷移はクリック同期ずれを避けるため 2 tick 後に実行します。
     *
     * @param player 対象プレイヤー
     * @param inventory 開く GUI
     * @param onOpened GUI 表示後に実行する処理
     * @param onCancelled GUI 表示が取消・失敗した場合に実行する処理
     */
    public static void open(
        @NotNull Player player,
        @NotNull Inventory inventory,
        @NotNull Runnable onOpened,
        @NotNull Runnable onCancelled
    ) {
        UUID playerId = player.getUniqueId();
        PendingTransition previousTransition = PENDING_TRANSITIONS.remove(playerId);
        if (previousTransition != null) {
            previousTransition.cancel();
        }
        Inventory source = player.getOpenInventory().getTopInventory();
        if (!isPluginGui(source)) {
            player.openInventory(inventory);
            if (player.getOpenInventory().getTopInventory() == inventory) {
                onOpened.run();
            } else {
                onCancelled.run();
            }
            return;
        }

        AstralRecord plugin = AstralRecord.getPlugin(AstralRecord.class);
        PendingTransition transition = new PendingTransition(onOpened, onCancelled);
        PENDING_TRANSITIONS.put(playerId, transition);
        BukkitTask task = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!PENDING_TRANSITIONS.remove(playerId, transition)) {
                return;
            }
            if (openIfSourceIsCurrent(player, source, inventory)) {
                transition.opened();
            } else {
                transition.cancelled();
            }
        }, TRANSITION_DELAY_TICKS);
        transition.attach(task);
    }

    /**
     * 遷移元 GUI が引き続き開かれている場合だけ内部画面遷移を実行し、表示後の処理を実行します。
     *
     * @param player 対象プレイヤー
     * @param source 遷移元 GUI
     * @param target 遷移先 GUI
     * @param onOpened GUI 表示後に実行する処理
     */
    static void openIfSourceIsCurrent(
        @NotNull Player player,
        @NotNull Inventory source,
        @NotNull Inventory target,
        @NotNull Runnable onOpened
    ) {
        if (openIfSourceIsCurrent(player, source, target)) {
            onOpened.run();
        }
    }

    /**
     * 遷移元 GUI が引き続き開かれている場合だけ内部画面遷移を実行します。
     *
     * @param player 対象プレイヤー
     * @param source 遷移元 GUI
     * @param target 遷移先 GUI
     * @return 遷移先を実際に表示できた場合は {@code true}
     */
    static boolean openIfSourceIsCurrent(
        @NotNull Player player,
        @NotNull Inventory source,
        @NotNull Inventory target
    ) {
        if (!player.isOnline() || player.getOpenInventory().getTopInventory() != source) {
            return false;
        }
        player.openInventory(target);
        if (player.getOpenInventory().getTopInventory() != target) {
            return false;
        }
        return true;
    }

    private static boolean isPluginGui(@Nullable Inventory inventory) {
        return GuiSessionTransitionService.isPluginManagedGui(inventory);
    }

    /** 遅延 GUI 遷移の完了通知を一回だけ実行します。 */
    private static final class PendingTransition {
        private final Runnable onOpened;
        private final Runnable onCancelled;
        private BukkitTask task;
        private boolean completed;

        private PendingTransition(@NotNull Runnable onOpened, @NotNull Runnable onCancelled) {
            this.onOpened = onOpened;
            this.onCancelled = onCancelled;
        }

        private synchronized void attach(@NotNull BukkitTask task) {
            this.task = task;
            if (completed) {
                task.cancel();
            }
        }

        private void opened() {
            complete(onOpened);
        }

        private void cancelled() {
            complete(onCancelled);
        }

        private void cancel() {
            BukkitTask scheduledTask;
            synchronized (this) {
                scheduledTask = task;
            }
            if (scheduledTask != null) {
                scheduledTask.cancel();
            }
            cancelled();
        }

        private void complete(@NotNull Runnable action) {
            synchronized (this) {
                if (completed) {
                    return;
                }
                completed = true;
            }
            action.run();
        }
    }
}
