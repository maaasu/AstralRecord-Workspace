package io.github.maaasu.astralRecord.feature.inventory.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.inventory.state.InventoryPersistence;
import io.github.maaasu.astralRecord.feature.inventory.state.PlayerInventoryState;
import io.github.maaasu.astralRecord.feature.inventory.state.PlayerInventoryStateRegistry;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

/**
 * 一定間隔（既定 60 秒）でオンライン全プレイヤーのインベントリ状態を API へ反映するスケジュールタスク。
 * <p>
 * BukkitScheduler の async timer として動作するため API 呼び出しはメインスレッドを止めません。
 * dirty フラグが立っている state のみを保存対象とし、未変更プレイヤーは通信を行いません。
 */
public final class InventoryAutoSaveTask {

    /** 1 tick = 50ms。60 秒は 1200 tick。 */
    public static final long DEFAULT_INTERVAL_TICKS = 60L * 20L;

    private final InventoryPersistence persistence;
    private final PlayerInventoryStateRegistry registry;
    private @org.jetbrains.annotations.Nullable BukkitTask task;

    /**
     * オートセーブタスクを構築します。
     *
     * @param persistence 永続化サービス
     * @param registry プレイヤー state レジストリ
     */
    public InventoryAutoSaveTask(
        @NotNull InventoryPersistence persistence,
        @NotNull PlayerInventoryStateRegistry registry
    ) {
        this.persistence = persistence;
        this.registry = registry;
    }

    /**
     * BukkitScheduler に async timer を登録して起動します。既に起動済みなら何もしません。
     *
     * @param plugin プラグインインスタンス
     * @param intervalTicks 実行間隔（tick）
     */
    public synchronized void start(@NotNull AstralRecord plugin, long intervalTicks) {
        if (task != null) {
            return;
        }
        task = plugin.getServer().getScheduler().runTaskTimerAsynchronously(
            plugin,
            this::runSaveAll,
            intervalTicks,
            intervalTicks
        );
    }

    /**
     * スケジューラを停止します。プラグイン停止時に呼び出してください。
     */
    public synchronized void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /**
     * 全オンラインプレイヤーの state を保存対象として 1 回だけ実行します。
     * <p>
     * 通常はスケジューラから呼び出されますが、テストや手動 flush で利用してもかまいません。
     */
    public void runSaveAll() {
        for (PlayerInventoryState state : registry.all()) {
            try {
                persistence.save(state, InventoryPersistence.SaveTrigger.AUTO);
            } catch (RuntimeException e) {
                Logger.warn(LogId.W_5252, state.getAccountId(), e.getMessage());
            }
        }
    }
}
