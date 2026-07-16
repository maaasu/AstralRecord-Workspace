package io.github.maaasu.astralRecord.feature.inventory.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.inventory.state.InventoryPersistence;
import io.github.maaasu.astralRecord.feature.inventory.state.PlayerInventoryState;
import io.github.maaasu.astralRecord.feature.inventory.state.PlayerInventoryStateRegistry;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.playersetting.service.PlayerSettingService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 一定間隔（既定 60 秒）でオンライン全プレイヤーのインベントリ状態を API へ反映するスケジュールタスク。
 * <p>
 * BukkitScheduler の async timer として動作するため API 呼び出しはメインスレッドを止めません。
 * dirty フラグが立っている state のみを保存対象とし、未変更プレイヤーは通信を行いません。
 */
public final class InventoryAutoSaveTask {

    /** 1 tick = 50ms。60 秒は 1200 tick。 */
    public static final long DEFAULT_INTERVAL_TICKS = 60L * 20L;

    private final InventoryService inventoryService;
    private final InventoryPersistence persistence;
    private final PlayerInventoryStateRegistry registry;
    private final PlayerSettingService playerSettingService;
    private final PlayerMessageService playerMessageService;
    private @org.jetbrains.annotations.Nullable BukkitTask task;

    /**
     * オートセーブタスクを構築します。
     *
     * @param persistence 永続化サービス
     * @param registry プレイヤー state レジストリ
     */
    public InventoryAutoSaveTask(
        @NotNull InventoryService inventoryService,
        @NotNull InventoryPersistence persistence,
        @NotNull PlayerInventoryStateRegistry registry,
        @NotNull PlayerSettingService playerSettingService,
        @NotNull PlayerMessageService playerMessageService
    ) {
        this.inventoryService = inventoryService;
        this.persistence = persistence;
        this.registry = registry;
        this.playerSettingService = playerSettingService;
        this.playerMessageService = playerMessageService;
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
        task = plugin.getServer().getScheduler().runTaskTimer(
            plugin,
            () -> {
                List<UUID> notificationTargets = notifySaveStarted();
                captureToolInventorySnapshots();
                plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                    boolean succeeded = runSaveAllWithResult();
                    if (succeeded) {
                        plugin.getServer().getScheduler().runTask(
                            plugin,
                            () -> notifySaveCompleted(plugin, notificationTargets)
                        );
                    }
                });
            },
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
        runSaveAllWithResult();
    }

    private boolean runSaveAllWithResult() {
        boolean succeeded = true;
        for (PlayerInventoryState state : registry.all()) {
            try {
                boolean attempted = persistence.save(state, InventoryPersistence.SaveTrigger.AUTO);
                if (attempted && persistence.hasPendingChanges(state)) {
                    succeeded = false;
                }
            } catch (RuntimeException e) {
                succeeded = false;
                Logger.warn(LogId.W_5252, state.getAccountId(), e.getMessage());
            }
        }
        return succeeded;
    }

    private @NotNull List<UUID> notifySaveStarted() {
        List<UUID> targets = new ArrayList<>();
        for (AstPlayer astPlayer : AstPlayerCache.getAll()) {
            UUID userId = astPlayer.getUser().getUuid();
            if (!playerSettingService.isAutoSaveMessageEnabled(userId)) {
                continue;
            }
            targets.add(userId);
            playerMessageService.send(astPlayer, PlayerMsgId.P_5280);
        }
        return targets;
    }

    private void notifySaveCompleted(@NotNull AstralRecord plugin, @NotNull List<UUID> targets) {
        for (UUID userId : targets) {
            var player = plugin.getServer().getPlayer(userId);
            if (player != null && player.isOnline()) {
                playerMessageService.send(player, PlayerMsgId.P_5281);
            }
        }
    }

    private void captureToolInventorySnapshots() {
        for (AstPlayer astPlayer : AstPlayerCache.getAll()) {
            try {
                if (isToolInventoryMode(astPlayer.getAccount().getMode())) {
                    inventoryService.saveToolInventorySnapshot(astPlayer);
                }
            } catch (RuntimeException e) {
                Logger.warn(LogId.W_5252, astPlayer.getAccount().getUuid(), e.getMessage());
            }
        }
    }

    private boolean isToolInventoryMode(@NotNull AccountMode mode) {
        return mode == AccountMode.BUILDER || mode == AccountMode.ADMIN;
    }
}
