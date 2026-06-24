package io.github.maaasu.astralRecord.feature.teleporter.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.teleporter.model.WaystoneDefinition;
import io.github.maaasu.astralRecord.feature.teleporter.service.TeleporterService;
import io.github.maaasu.astralRecord.feature.teleporter.service.WaystoneHitBoxResolver;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ワールド上のウェイストーン左/右クリックを処理します。
 */
public final class TeleporterInteractEventHandler extends AbstractEventHandler {
    private static final long CLICK_DEBOUNCE_NANOS = 120_000_000L;

    private final TeleporterService teleporterService;
    private final WaystoneHitBoxResolver hitBoxResolver;
    private final Map<UUID, Long> lastHandledClickNanosByPlayer = new ConcurrentHashMap<>();

    public TeleporterInteractEventHandler(
            @NotNull TeleporterService teleporterService,
            @NotNull WaystoneHitBoxResolver hitBoxResolver
    ) {
        this.teleporterService = teleporterService;
        this.hitBoxResolver = hitBoxResolver;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlayerInteract(@NotNull PlayerInteractEvent event) {
        runSafely(() -> {
            if (event.getHand() != EquipmentSlot.HAND) {
                return;
            }
            Action action = event.getAction();
            boolean rightClick = action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK;
            boolean leftClick = action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK;
            if (!rightClick && !leftClick) {
                return;
            }
            if (handleClick(event.getPlayer(), rightClick)) {
                event.setCancelled(true);
            }
        }, LogId.E_5950, event.getPlayer().getName(), "-");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlayerAnimation(@NotNull PlayerAnimationEvent event) {
        runSafely(() -> {
            if (event.getAnimationType() != PlayerAnimationType.ARM_SWING) {
                return;
            }
            if (handleClick(event.getPlayer(), false)) {
                event.setCancelled(true);
            }
        }, LogId.E_5950, event.getPlayer().getName(), "animation");
    }

    private boolean handleClick(@NotNull Player player, boolean rightClick) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null || !astPlayer.getAccount().getMode().shouldProcessGameplay()) {
            return false;
        }
        WaystoneDefinition definition = hitBoxResolver.resolve(player);
        if (definition == null) {
            return false;
        }
        if (isDuplicateClick(player.getUniqueId())) {
            return true;
        }
        teleporterService.handleWaystoneClick(player, astPlayer, definition, rightClick);
        return true;
    }

    private boolean isDuplicateClick(@NotNull UUID playerId) {
        long now = System.nanoTime();
        Long lastHandledAt = lastHandledClickNanosByPlayer.put(playerId, now);
        return lastHandledAt != null && now - lastHandledAt < CLICK_DEBOUNCE_NANOS;
    }
}
