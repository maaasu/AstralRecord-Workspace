package io.github.maaasu.astralRecord.feature.world.event;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.world.service.BaseMusicService;
import io.github.maaasu.astralRecord.feature.world.service.WorldService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerJoinEvent;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * config.yml に定義された参加時スポーン先の WorldMasterData へプレイヤーを移動します。
 */
public class WorldJoinSpawnEventHandler extends AbstractEventHandler {

    private final AstralRecord plugin;
    private final String joinSpawnWorldId;
    private final WorldService worldService;
    private final BaseMusicService baseMusicService;

    /**
     * ハンドラを初期化します。
     *
     * @param plugin プラグイン
     * @param joinSpawnWorldId 参加時スポーン先の WorldMasterData ID
     * @param worldService WorldMasterData サービス
     * @param baseMusicService 拠点音楽サービス
     */
    public WorldJoinSpawnEventHandler(
            @NotNull AstralRecord plugin,
            @NotNull String joinSpawnWorldId,
            @NotNull WorldService worldService,
            @NotNull BaseMusicService baseMusicService
    ) {
        this.plugin = plugin;
        this.joinSpawnWorldId = joinSpawnWorldId;
        this.worldService = worldService;
        this.baseMusicService = baseMusicService;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player joinedPlayer = event.getPlayer();
        UUID playerId = joinedPlayer.getUniqueId();
        String playerName = joinedPlayer.getName();
        plugin.getServer().getScheduler().runTask(plugin, () -> runSafely(() -> {
            var worldData = worldService.getById(joinSpawnWorldId);
            if (worldData == null) {
                Logger.log(LogId.W_5751, joinSpawnWorldId);
                return;
            }

            var location = worldService.resolveSpawnLocation(worldData);
            if (location == null) {
                Logger.log(LogId.W_5751, joinSpawnWorldId);
                return;
            }

            worldService.teleportToSpawnAsync(joinedPlayer, worldData).thenAccept(success -> {
                if (!success) {
                    Logger.log(
                            LogId.W_5753,
                            worldData.id(),
                            location.getWorld() == null ? "null" : location.getWorld().getName(),
                            location.getX(),
                            location.getY(),
                            location.getZ()
                    );
                    return;
                }
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    Player player = plugin.getServer().getPlayer(playerId);
                    if (player != null && player.isOnline()) {
                        baseMusicService.refreshPlayer(player);
                    }
                });
            });
        }, LogId.E_5752, playerName));
    }
}
