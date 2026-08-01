package io.github.maaasu.astralRecord.feature.inventory.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 一定間隔（既定 60 秒）でオンライン全プレイヤーのインベントリ状態を API へ反映するスケジュールタスク。
 * <p>
 * BukkitScheduler の timer でスナップショットを取得し、API 呼び出しは
 * {@link InventorySaveCoordinator} の非同期 executor 上で実行するためメインスレッドを止めません。
 * dirty フラグが立っている state のみを保存対象とし、未変更プレイヤーは通信を行いません。
 */
public final class InventoryAutoSaveTask {

    /** 1 tick = 50ms。60 秒は 1200 tick。 */
    public static final long DEFAULT_INTERVAL_TICKS = 60L * 20L;

    private final InventoryService inventoryService;
    private final InventorySaveCoordinator inventorySaveCoordinator;
    private final PlayerInventoryStateRegistry registry;
    private final PlayerSettingService playerSettingService;
    private final PlayerMessageService playerMessageService;
    private @org.jetbrains.annotations.Nullable BukkitTask task;

    /**
     * オートセーブタスクを構築します。
     *
     * @param inventoryService インベントリサービス
     * @param inventorySaveCoordinator インベントリ保存コーディネーター
     * @param registry プレイヤー state レジストリ
     * @param playerSettingService プレイヤー設定サービス
     * @param playerMessageService プレイヤーメッセージサービス
     */
    public InventoryAutoSaveTask(
        @NotNull InventoryService inventoryService,
        @NotNull InventorySaveCoordinator inventorySaveCoordinator,
        @NotNull PlayerInventoryStateRegistry registry,
        @NotNull PlayerSettingService playerSettingService,
        @NotNull PlayerMessageService playerMessageService
    ) {
        this.inventoryService = inventoryService;
        this.inventorySaveCoordinator = inventorySaveCoordinator;
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
                long saveStartedAtNanos = System.nanoTime();
                captureToolInventorySnapshots();
                runSaveAll().thenAccept(succeeded -> {
                    if (succeeded) {
                        plugin.getServer().getScheduler().runTask(
                            plugin,
                            () -> notifySaveCompleted(
                                plugin,
                                notificationTargets,
                                elapsedMillisSince(saveStartedAtNanos)
                            )
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
     * 保存処理はアカウント別キュー上で非同期に実行され、呼び出しスレッドを待機させません。
     *
     * @return 全 state の保存が未保存変更を残さず完了した場合 {@code true} となる future
     */
    public @NotNull CompletableFuture<Boolean> runSaveAll() {
        List<CompletableFuture<Boolean>> saves = new ArrayList<>();
        for (PlayerInventoryState state : registry.all()) {
            CompletableFuture<Boolean> save = inventorySaveCoordinator.saveAuto(state).handle((succeeded, throwable) -> {
                inventorySaveCoordinator.cleanupAfterRetry(state);
                return throwable == null && Boolean.TRUE.equals(succeeded);
            });
            saves.add(save);
        }
        CompletableFuture<?>[] pending = saves.toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(pending)
            .thenApply(ignored -> saves.stream().allMatch(CompletableFuture::join));
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

    /**
     * 保存完了を通知対象のオンラインプレイヤーへ送信します。
     *
     * @param plugin プラグインインスタンス
     * @param targets 保存開始を通知したユーザー ID 一覧
     * @param elapsedMillis 保存開始から完了までの経過ミリ秒
     */
    private void notifySaveCompleted(
        @NotNull AstralRecord plugin,
        @NotNull List<UUID> targets,
        long elapsedMillis
    ) {
        for (UUID userId : targets) {
            var player = plugin.getServer().getPlayer(userId);
            if (player != null && player.isOnline()) {
                playerMessageService.send(player, PlayerMsgId.P_5281, elapsedMillis);
            }
        }
    }

    /**
     * 指定した単調時計の開始時刻から現在までの経過時間をミリ秒で返します。
     *
     * @param startedAtNanos {@link System#nanoTime()} で取得した開始時刻
     * @return 0 以上の経過ミリ秒
     */
    private long elapsedMillisSince(long startedAtNanos) {
        return Math.max(0L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos));
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
        return mode == AccountMode.ADMIN;
    }
}
