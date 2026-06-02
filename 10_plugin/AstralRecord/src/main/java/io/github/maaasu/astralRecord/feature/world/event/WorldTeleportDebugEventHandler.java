package io.github.maaasu.astralRecord.feature.world.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.jetbrains.annotations.NotNull;

/**
 * プレイヤーテレポート結果の切り分け用デバッグログを出力します。
 */
public class WorldTeleportDebugEventHandler extends AbstractEventHandler {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onPlayerTeleport(@NotNull PlayerTeleportEvent event) {
        runSafely(() -> Logger.log(
                LogId.I_5752,
                event.getPlayer().getName(),
                event.getCause().name(),
                event.isCancelled(),
                describe(event.getFrom()),
                describe(event.getTo())
        ), LogId.E_5752, event.getPlayer().getName());
    }

    @NotNull
    private static String describe(@NotNull Location location) {
        String worldName = location.getWorld() == null ? "null" : location.getWorld().getName();
        return worldName + "(" + location.getX() + ", " + location.getY() + ", " + location.getZ() + ")";
    }
}
