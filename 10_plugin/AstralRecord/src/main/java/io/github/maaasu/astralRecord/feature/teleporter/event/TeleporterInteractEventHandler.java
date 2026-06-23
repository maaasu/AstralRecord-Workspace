package io.github.maaasu.astralRecord.feature.teleporter.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.teleporter.model.WaystoneDefinition;
import io.github.maaasu.astralRecord.feature.teleporter.service.TeleporterService;
import io.github.maaasu.astralRecord.feature.teleporter.service.WaystoneHitBoxResolver;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.jetbrains.annotations.NotNull;

/**
 * ワールド上のウェイストーン左/右クリックを処理します。
 */
public final class TeleporterInteractEventHandler extends AbstractEventHandler {
    private final TeleporterService teleporterService;
    private final WaystoneHitBoxResolver hitBoxResolver;

    public TeleporterInteractEventHandler(
            @NotNull TeleporterService teleporterService,
            @NotNull WaystoneHitBoxResolver hitBoxResolver
    ) {
        this.teleporterService = teleporterService;
        this.hitBoxResolver = hitBoxResolver;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerInteract(@NotNull PlayerInteractEvent event) {
        runSafely(() -> {
            Action action = event.getAction();
            boolean rightClick = action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK;
            boolean leftClick = action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK;
            if (!rightClick && !leftClick) {
                return;
            }
            AstPlayer astPlayer = AstPlayerCache.get(event.getPlayer());
            if (astPlayer == null || !astPlayer.getAccount().getMode().shouldProcessGameplay()) {
                return;
            }
            WaystoneDefinition definition = hitBoxResolver.resolve(event.getPlayer());
            if (definition == null) {
                return;
            }
            event.setCancelled(true);
            teleporterService.handleWaystoneClick(event.getPlayer(), astPlayer, definition, rightClick);
        }, LogId.E_5950, event.getPlayer().getName(), "-");
    }
}
