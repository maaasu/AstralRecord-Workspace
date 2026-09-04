package io.github.maaasu.astralRecord.feature.world.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.world.model.WorldMasterData;
import io.github.maaasu.astralRecord.feature.world.model.WorldType;
import io.github.maaasu.astralRecord.feature.world.service.OverworldTeleportService;
import io.github.maaasu.astralRecord.feature.world.service.WorldService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.shared.interaction.InputClaimPolicy;
import io.github.maaasu.astralRecord.shared.interaction.InputFamily;
import io.github.maaasu.astralRecord.shared.interaction.InteractionCandidateOrder;
import io.github.maaasu.astralRecord.shared.interaction.InteractionTier;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInputCandidate;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInputContext;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInputResolver;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInteractionSnapshot;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;

/**
 * 拠点スポーン円のスニーク入力を、オーバーワールド転送GUI候補として提供します。
 */
public final class BaseWorldSpawnTeleportEventHandler extends AbstractEventHandler
    implements PlayerInputResolver<PlayerInteractionSnapshot> {
    private static final double TELEPORT_TRIGGER_RADIUS = 2.0D;
    private static final double TELEPORT_TRIGGER_RADIUS_SQUARED = TELEPORT_TRIGGER_RADIUS * TELEPORT_TRIGGER_RADIUS;

    private final WorldService worldService;
    private final OverworldTeleportService teleportService;
    private final OverworldTeleportGuiEventHandler guiEventHandler;

    public BaseWorldSpawnTeleportEventHandler(
        @NotNull WorldService worldService,
        @NotNull OverworldTeleportService teleportService,
        @NotNull OverworldTeleportGuiEventHandler guiEventHandler
    ) {
        this.worldService = worldService;
        this.teleportService = teleportService;
        this.guiEventHandler = guiEventHandler;
    }

    @Override
    public @NotNull Collection<PlayerInputCandidate> resolve(
        @NotNull PlayerInputContext<PlayerInteractionSnapshot> context
    ) {
        PlayerInteractionSnapshot snapshot = context.inputSnapshot();
        if (context.family() != InputFamily.SNEAK
            || !(snapshot.event() instanceof PlayerToggleSneakEvent event)
            || !event.isSneaking()
            || guiEventHandler.isOpen(snapshot.player())
            || !teleportService.isBaseWorld(snapshot.player().getWorld())) {
            return List.of();
        }
        Double distance = triggerDistance(snapshot.player());
        if (distance == null) {
            return List.of();
        }
        return List.of(new PlayerInputCandidate(
            "base-world-spawn-teleport",
            InteractionTier.WORLD_INTERACTION,
            distance,
            InteractionCandidateOrder.WORLD_SPAWN_ACTION,
            snapshot.player().getWorld().getUID() + ":base-spawn",
            InputClaimPolicy.CLAIM,
            () -> runSafely(
                () -> guiEventHandler.open(snapshot.player()),
                LogId.E_5755,
                snapshot.player().getName(),
                "base-spawn-sneak"
            )
        ));
    }

    private @Nullable Double triggerDistance(@NotNull Player player) {
        Location triggerCenter = resolveTriggerCenter(player);
        if (triggerCenter == null || triggerCenter.getWorld() == null) {
            return null;
        }
        double distanceSquared = horizontalDistanceSquared(player.getLocation(), triggerCenter);
        return distanceSquared <= TELEPORT_TRIGGER_RADIUS_SQUARED
            ? player.getLocation().distance(triggerCenter)
            : null;
    }

    private @Nullable Location resolveTriggerCenter(@NotNull Player player) {
        WorldMasterData worldData = worldService.findByBukkitWorld(player.getWorld());
        if (worldData == null || worldData.worldType() != WorldType.BASE) {
            return null;
        }
        return worldService.resolveSpawnLocation(worldData);
    }

    private static double horizontalDistanceSquared(@NotNull Location from, @NotNull Location to) {
        if (from.getWorld() == null || to.getWorld() == null || !from.getWorld().getUID().equals(to.getWorld().getUID())) {
            return Double.POSITIVE_INFINITY;
        }
        double deltaX = from.getX() - to.getX();
        double deltaZ = from.getZ() - to.getZ();
        return (deltaX * deltaX) + (deltaZ * deltaZ);
    }
}
