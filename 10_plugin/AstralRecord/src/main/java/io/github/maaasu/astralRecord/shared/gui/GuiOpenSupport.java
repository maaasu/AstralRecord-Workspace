package io.github.maaasu.astralRecord.shared.gui;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.shared.gui.session.GuiSessionTransitionService;
import io.papermc.paper.dialog.Dialog;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * プラグイン管理 GUI 間の画面遷移を安全な tick 境界で実行します。
 */
public final class GuiOpenSupport {
    private static final long TRANSITION_DELAY_TICKS = 2L;
    private static final Map<UUID, PendingTransition> PENDING_TRANSITIONS = new ConcurrentHashMap<>();

    private GuiOpenSupport() {
    }

    /**
     * 対象プレイヤーの保留中の画面遷移を取り消し、取消コールバックを一度だけ通知します。
     * メインスレッドで呼び出し、既に表示済みの画面には影響しません。
     * @param playerId 遷移を取り消すプレイヤー UUID
     */
    public static void cancelPending(@NotNull UUID playerId) {
        PendingTransition transition = PENDING_TRANSITIONS.remove(playerId);
        if (transition != null) {
            transition.cancel();
        }
    }

    /**
     * GUI を開きます。プラグイン GUI からの遷移と統合版の表示は入力処理の後に実行します。
     *
     * @param player 対象プレイヤー
     * @param inventory 開く GUI
     */
    public static void open(@NotNull Player player, @NotNull Inventory inventory) {
        open(player, inventory, () -> {
        });
    }

    /**
     * GUI を開き、実際に表示できた場合だけ完了処理を実行します。
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
     * GUI を開き、遷移結果を一度だけ通知します。
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
        cancelPending(playerId);
        Inventory source = player.getOpenInventory().getTopInventory();
        var astPlayer = AstPlayerCache.get(player);
        boolean bedrock = astPlayer != null && astPlayer.isBedrock();
        if (!isPluginGui(source) && !bedrock) {
            GuiResourcePackTitle.open(player, inventory);
            if (player.getOpenInventory().getTopInventory() == inventory) {
                io.github.maaasu.astralRecord.shared.gui.playerinventory.PlayerInventoryOverlaySupport.afterOpen(player, source);
                onOpened.run();
            } else {
                onCancelled.run();
            }
            return;
        }

        scheduleTransition(playerId, new PendingTransition(onOpened, onCancelled),
            () -> openIfSourceIsCurrent(player, source, inventory));
    }

    /**
     * フォームをインベントリと同じ予約で開き、先行する表示要求を取り消します。
     * @param player フォームを開くプレイヤー
     * @param createDialog 入力処理後にフォームを作る処理。セッションが無効なら null を返す
     */
    public static void showDialog(@NotNull Player player, @NotNull Supplier<@Nullable Dialog> createDialog) {
        UUID playerId = player.getUniqueId();
        cancelPending(playerId);
        Inventory source = player.getOpenInventory().getTopInventory();
        scheduleTransition(playerId, new PendingTransition(() -> { }, () -> { }), () -> {
            if (!isSourceCurrent(player, source)) {
                return false;
            }
            Dialog dialog = createDialog.get();
            if (dialog == null) {
                return false;
            }
            if (source.getType() != InventoryType.CRAFTING) {
                player.closeInventory(InventoryCloseEvent.Reason.OPEN_NEW);
            }
            player.showDialog(dialog);
            return true;
        });
    }

    /**
     * 次の表示処理を予約し、有効な予約の結果だけを通知します。
     * @param playerId 表示するプレイヤー
     * @param transition 表示の完了と取消を通知する予約
     * @param open 遷移元を確認して表示を行う処理
     */
    private static void scheduleTransition(UUID playerId, PendingTransition transition, BooleanSupplier open) {
        AstralRecord plugin = AstralRecord.getPlugin(AstralRecord.class);
        PENDING_TRANSITIONS.put(playerId, transition);
        BukkitTask task = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!PENDING_TRANSITIONS.remove(playerId, transition)) {
                return;
            }
            if (open.getAsBoolean()) {
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
        if (!isSourceCurrent(player, source)) {
            return false;
        }
        GuiResourcePackTitle.open(player, target);
        if (player.getOpenInventory().getTopInventory() != target) {
            return false;
        }
        io.github.maaasu.astralRecord.shared.gui.playerinventory.PlayerInventoryOverlaySupport.afterOpen(player, source);
        return true;
    }

    /**
     * @param player 表示対象プレイヤー
     * @param source 予約時の遷移元
     * @return プレイヤーが接続中で、遷移元が引き続き表示されている場合は true
     */
    private static boolean isSourceCurrent(Player player, Inventory source) {
        return player.isOnline() && player.getOpenInventory().getTopInventory() == source;
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
