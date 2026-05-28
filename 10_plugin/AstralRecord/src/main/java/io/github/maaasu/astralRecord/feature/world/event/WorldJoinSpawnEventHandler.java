package io.github.maaasu.astralRecord.feature.world.event;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.world.model.JoinSpawnLocation;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerJoinEvent;
import org.jetbrains.annotations.NotNull;

/**
 * 繧ｵ繝ｼ繝舌・蜿ょ刈譎ゅ↓ config.yml 縺ｮ蜿ょ刈蜈医∈繝励Ξ繧､繝､繝ｼ繧堤ｧｻ蜍輔＠縺ｾ縺吶・ */
public class WorldJoinSpawnEventHandler extends AbstractEventHandler {

    private final AstralRecord plugin;
    private final JoinSpawnLocation joinSpawnLocation;

    /**
     * WorldJoinSpawnEventHandler 繧貞・譛溷喧縺励∪縺吶・     *
     * @param plugin 繝励Λ繧ｰ繧､繝ｳ
     * @param joinSpawnLocation 蜿ょ刈譎ゅせ繝昴・繝ｳ險ｭ螳・     */
    public WorldJoinSpawnEventHandler(
            @NotNull AstralRecord plugin,
            @NotNull JoinSpawnLocation joinSpawnLocation
    ) {
        this.plugin = plugin;
        this.joinSpawnLocation = joinSpawnLocation;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        plugin.getServer().getScheduler().runTask(plugin, () -> runSafely(() -> {
            var world = Bukkit.getWorld(joinSpawnLocation.world());
            if (world == null) {
                Logger.log(LogId.W_5751, joinSpawnLocation.world());
                return;
            }

            var location = new Location(
                    world,
                    joinSpawnLocation.x(),
                    joinSpawnLocation.y(),
                    joinSpawnLocation.z(),
                    joinSpawnLocation.yaw(),
                    joinSpawnLocation.pitch()
            );
            event.getPlayer().teleport(location);
        }, LogId.E_5752, event.getPlayer().getName()));
    }
}
