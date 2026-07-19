package io.github.maaasu.astralRecord.feature.world.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.world.model.WorldMasterData;
import io.github.maaasu.astralRecord.feature.world.model.WorldType;
import io.github.maaasu.astralRecord.feature.world.service.ReturnToBaseService;
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
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;

/**
 * オーバーワールドスポーン円のスニーク入力を、拠点帰還候補として提供します。
 */
public final class OverworldSpawnReturnEventHandler extends AbstractEventHandler
    implements PlayerInputResolver<PlayerInteractionSnapshot> {
    private static final double RETURN_TRIGGER_RADIUS = 2.0D;
    private static final double RETURN_TRIGGER_RADIUS_SQUARED = RETURN_TRIGGER_RADIUS * RETURN_TRIGGER_RADIUS;

    private final WorldService worldService;
    private final ReturnToBaseService returnToBaseService;

    public OverworldSpawnReturnEventHandler(
        @NotNull WorldService worldService,
        @NotNull ReturnToBaseService returnToBaseService
    ) {
        this.worldService = worldService;
        this.returnToBaseService = returnToBaseService;
    }

    @Override
    public @NotNull Collection<PlayerInputCandidate> resolve(
        @NotNull PlayerInputContext<PlayerInteractionSnapshot> context
    ) {
        PlayerInteractionSnapshot snapshot = context.inputSnapshot();
        if (context.family() != InputFamily.SNEAK
            || !(snapshot.event() instanceof PlayerToggleSneakEvent event)
            || !event.isSneaking()) {
            return List.of();
        }
        AstPlayer astPlayer = AstPlayerCache.get(snapshot.player());
        Double distance = triggerDistance(snapshot.player());
        if (astPlayer == null || distance == null) {
            return List.of();
        }
        return List.of(new PlayerInputCandidate(
            "overworld-spawn-return",
            InteractionTier.WORLD_INTERACTION,
            distance,
            InteractionCandidateOrder.WORLD_SPAWN_ACTION,
            snapshot.player().getWorld().getUID() + ":overworld-spawn",
            InputClaimPolicy.CLAIM,
            () -> runSafely(
                () -> returnToBaseService.beginImmediateReturn(astPlayer),
                LogId.E_5756,
                snapshot.player().getName(),
                "sneak"
            )
        ));
    }

    private @Nullable Double triggerDistance(@NotNull Player player) {
        Location triggerCenter = resolveTriggerCenter(player);
        if (triggerCenter == null || triggerCenter.getWorld() == null) {
            return null;
        }
        double distanceSquared = horizontalDistanceSquared(player.getLocation(), triggerCenter);
        return distanceSquared <= RETURN_TRIGGER_RADIUS_SQUARED
            ? player.getLocation().distance(triggerCenter)
            : null;
    }

    private @Nullable Location resolveTriggerCenter(@NotNull Player player) {
        WorldMasterData worldData = worldService.findByBukkitWorld(player.getWorld());
        if (worldData != null) {
            if (worldData.worldType() != WorldType.OVERWORLD) {
                return null;
            }
            return worldService.resolveSpawnLocation(worldData);
        }

        World world = player.getWorld();
        if (world.getEnvironment() != World.Environment.NORMAL) {
            return null;
        }
        return world.getSpawnLocation();
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
