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

            // 通常転送が非同期処理の途中で失敗しても、プレイヤーをBukkit既定ワールドへ残さない。
            // 検証はこの参加イベントのプレイヤーインスタンスにだけ紐付くため、再接続後の別セッションへ作用しない。
            try {
                worldService.teleportToSpawnAsync(joinedPlayer, worldData).whenComplete((success, failure) -> {
                    plugin.getServer().getScheduler().runTask(plugin, () -> runSafely(() -> {
                        if (failure != null) {
                            Logger.log(LogId.E_5752, failure, playerName);
                        } else if (!Boolean.TRUE.equals(success)) {
                            logJoinSpawnFailure(worldData);
                        }
                        scheduleJoinSpawnVerification(
                                joinedPlayer,
                                worldData,
                                JOIN_SPAWN_VERIFICATION_DELAY_TICKS,
                                1
                        );
                    }, LogId.E_5752, playerName));
                });
            } catch (RuntimeException failure) {
                Logger.log(LogId.E_5752, failure, playerName);
                scheduleJoinSpawnVerification(
                        joinedPlayer,
                        worldData,
                        JOIN_SPAWN_VERIFICATION_DELAY_TICKS,
                        1
                );
            }
        }, LogId.E_5752, playerName));
    }

    private void scheduleJoinSpawnVerification(
            @NotNull Player player,
            @NotNull WorldMasterData worldData,
            long delayTicks,
            int teleportAttempts
    ) {
        plugin.getServer().getScheduler().runTaskLater(
                plugin,
                () -> runSafely(
                        () -> verifyJoinSpawn(player, worldData, teleportAttempts),
                        LogId.E_5752,
                        player.getName()
                ),
                delayTicks
        );
    }

    private void verifyJoinSpawn(
            @NotNull Player player,
            @NotNull WorldMasterData worldData,
            int teleportAttempts
    ) {
        if (!isCurrentJoinSession(player)) {
            return;
        }

        var targetWorld = worldService.resolveLoadedWorld(worldData);
        if (targetWorld != null && player.getWorld() == targetWorld) {
            return;
        }
        if (teleportAttempts >= JOIN_SPAWN_MAX_TELEPORT_ATTEMPTS) {
            logJoinSpawnFailure(worldData);
            return;
        }

        try {
            worldService.teleportToSpawnAsync(player, worldData);
        } catch (RuntimeException failure) {
            Logger.log(LogId.E_5752, failure, player.getName());
        }
        // 再試行の完了 callback では Bukkit API に触れず、開始時点で main thread から最終確認を予約する。
        scheduleJoinSpawnVerification(
                player,
                worldData,
                JOIN_SPAWN_CONFIRMATION_DELAY_TICKS,
                teleportAttempts + 1
        );
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
}
