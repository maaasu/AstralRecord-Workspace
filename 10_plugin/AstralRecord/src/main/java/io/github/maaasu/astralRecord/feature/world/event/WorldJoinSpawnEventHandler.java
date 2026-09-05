package io.github.maaasu.astralRecord.feature.world.event;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.world.model.WorldMasterData;
import io.github.maaasu.astralRecord.feature.world.service.WorldService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerJoinEvent;
import org.jetbrains.annotations.NotNull;

/**
 * config.yml に定義された参加時スポーン先の WorldMasterData へプレイヤーを移動します。
 */
public class WorldJoinSpawnEventHandler extends AbstractEventHandler {

    private static final long JOIN_SPAWN_VERIFICATION_DELAY_TICKS = 40L;
    private static final long JOIN_SPAWN_CONFIRMATION_DELAY_TICKS = 10L;
    private static final int JOIN_SPAWN_MAX_TELEPORT_ATTEMPTS = 2;

    private final AstralRecord plugin;
    private final String joinSpawnWorldId;
    private final WorldService worldService;

    /**
     * ハンドラを初期化します。
     *
     * @param plugin プラグイン
     * @param joinSpawnWorldId 参加時スポーン先の WorldMasterData ID
     * @param worldService WorldMasterData サービス
     */
    public WorldJoinSpawnEventHandler(
            @NotNull AstralRecord plugin,
            @NotNull String joinSpawnWorldId,
            @NotNull WorldService worldService
    ) {
        this.plugin = plugin;
        this.joinSpawnWorldId = joinSpawnWorldId;
        this.worldService = worldService;
    }

    /**
     * プレイヤー参加後に設定されたスポーン先への転送と到達検証を開始します。
     *
     * @param event プレイヤー参加イベント
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player joinedPlayer = event.getPlayer();
        String playerName = joinedPlayer.getName();
        plugin.getServer().getScheduler().runTask(plugin, () -> runSafely(() -> {
            var worldData = worldService.getById(joinSpawnWorldId);
            if (worldData == null) {
                Logger.log(LogId.W_5751, joinSpawnWorldId);
                return;
            }

            // 転送 Future の完了を待たず、ログイン後の検証を独立して予約する。
            // 非同期処理が未完了のままでも、プレイヤーを Bukkit 既定ワールドへ残さない。
            // 検証はこの参加イベントのプレイヤーインスタンスにだけ紐付くため、再接続後の別セッションへ作用しない。
            JoinSpawnSession session = new JoinSpawnSession(joinedPlayer, worldData);
            scheduleJoinSpawnVerification(
                    session,
                    JOIN_SPAWN_VERIFICATION_DELAY_TICKS,
                    1
            );
            startJoinSpawnTeleport(session, 1);
        }, LogId.E_5752, playerName));
    }

    /**
     * 参加先ワールドの到達検証をメインスレッドへ予約します。
     *
     * @param session 参加セッション
     * @param delayTicks 検証までの tick 数
     * @param teleportAttempts 現在までの転送試行回数
     */
    private void scheduleJoinSpawnVerification(
            @NotNull JoinSpawnSession session,
            long delayTicks,
            int teleportAttempts
    ) {
        if (session.verificationScheduled || session.verificationFinished) {
            return;
        }
        session.verificationScheduled = true;
        plugin.getServer().getScheduler().runTaskLater(
                plugin,
                () -> runSafely(
                        () -> {
                            session.verificationScheduled = false;
                            verifyJoinSpawn(session, teleportAttempts);
                        },
                        LogId.E_5752,
                        session.playerName
                ),
                delayTicks
        );
    }

    /**
     * 参加先ワールドへ到達しているかを確認し、必要な場合だけ直列に再転送します。
     *
     * @param session 参加セッション
     * @param teleportAttempts 現在までの転送試行回数
     */
    private void verifyJoinSpawn(
            @NotNull JoinSpawnSession session,
            int teleportAttempts
    ) {
        if (!isCurrentJoinSession(session.player)) {
            session.verificationFinished = true;
            return;
        }

        var targetWorld = worldService.resolveLoadedWorld(session.worldData);
        if (targetWorld != null && session.player.getWorld() == targetWorld) {
            session.verificationFinished = true;
            return;
        }
        if (session.teleportInProgress) {
            return;
        }
        if (teleportAttempts >= JOIN_SPAWN_MAX_TELEPORT_ATTEMPTS) {
            session.verificationFinished = true;
            logJoinSpawnFailure(session.worldData);
            return;
        }

        startJoinSpawnTeleport(session, teleportAttempts + 1);
        // 再試行の完了 callback では Bukkit API に触れず、開始時点で main thread から最終確認を予約する。
        scheduleJoinSpawnVerification(
                session,
                JOIN_SPAWN_CONFIRMATION_DELAY_TICKS,
                teleportAttempts + 1
        );
    }

    /**
     * 参加先スポーンへの転送を開始し、完了状態だけをセッションへ反映します。
     *
     * @param session 参加セッション
     * @param teleportAttempts 開始する転送試行の番号
     */
    private void startJoinSpawnTeleport(@NotNull JoinSpawnSession session, int teleportAttempts) {
        session.teleportInProgress = true;
        try {
            worldService.teleportToSpawnAsync(session.player, session.worldData).whenComplete((success, failure) -> {
                session.teleportInProgress = false;
                plugin.getServer().getScheduler().runTask(plugin, () -> runSafely(() -> {
                    if (session.verificationFinished) {
                        return;
                    }
                    if (failure != null) {
                        Logger.log(LogId.E_5752, failure, session.playerName);
                    } else if (!Boolean.TRUE.equals(success)) {
                        logJoinSpawnFailure(session.worldData);
                    }
                    if (!isCurrentJoinSession(session.player)) {
                        session.verificationFinished = true;
                        return;
                    }
                    scheduleJoinSpawnVerification(session, JOIN_SPAWN_CONFIRMATION_DELAY_TICKS, teleportAttempts);
                }, LogId.E_5752, session.playerName));
            });
        } catch (RuntimeException failure) {
            session.teleportInProgress = false;
            Logger.log(LogId.E_5752, failure, session.playerName);
        }
    }

    private void logJoinSpawnFailure(@NotNull WorldMasterData worldData) {
        var targetWorld = worldService.resolveLoadedWorld(worldData);
        if (targetWorld == null) {
            Logger.log(LogId.W_5751, worldData.id());
            return;
        }
        Logger.log(
                LogId.W_5753,
                worldData.id(),
                targetWorld.getName(),
                worldData.spawnLocation().x(),
                worldData.spawnLocation().y(),
                worldData.spawnLocation().z()
        );
    }

    private boolean isCurrentJoinSession(@NotNull Player player) {
        if (!player.isOnline()) {
            return false;
        }
        return plugin.getServer().getPlayer(player.getUniqueId()) == player;
    }

    private static final class JoinSpawnSession {
        private final Player player;
        private final WorldMasterData worldData;
        private final String playerName;
        private volatile boolean teleportInProgress;
        private boolean verificationScheduled;
        private boolean verificationFinished;

        private JoinSpawnSession(@NotNull Player player, @NotNull WorldMasterData worldData) {
            this.player = player;
            this.worldData = worldData;
            this.playerName = player.getName();
        }
    }
}
