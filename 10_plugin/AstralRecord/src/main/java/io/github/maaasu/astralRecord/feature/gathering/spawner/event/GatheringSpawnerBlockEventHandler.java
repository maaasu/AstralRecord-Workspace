package io.github.maaasu.astralRecord.feature.gathering.spawner.event;

import io.github.maaasu.astralRecord.feature.gathering.spawner.service.GatheringSpawnerService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.shared.interaction.InputClaimPolicy;
import io.github.maaasu.astralRecord.shared.interaction.InputFamily;
import io.github.maaasu.astralRecord.shared.interaction.InputSource;
import io.github.maaasu.astralRecord.shared.interaction.InteractionCandidateOrder;
import io.github.maaasu.astralRecord.shared.interaction.InteractionTier;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInputCandidate;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInputContext;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInputResolver;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInteractionSnapshot;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * Gathering spawner の設置・破壊・視線左クリックを共通入力候補として提供します。
 */
public final class GatheringSpawnerBlockEventHandler
    implements PlayerInputResolver<PlayerInteractionSnapshot> {
    private static final double TARGET_DISTANCE = 6.0D;
    private static final double TARGET_RADIUS = 0.9D;

    private final GatheringSpawnerService spawnerService;

    public GatheringSpawnerBlockEventHandler(@NotNull GatheringSpawnerService spawnerService) {
        this.spawnerService = spawnerService;
    }

    @Override
    public @NotNull Collection<PlayerInputCandidate> resolve(
        @NotNull PlayerInputContext<PlayerInteractionSnapshot> context
    ) {
        if (context.family() == InputFamily.BLOCK_MUTATION) {
            return resolveBlockMutation(context);
        }
        if (context.family() != InputFamily.LEFT_CLICK || !context.inputSnapshot().isMainHandInput()) {
            return List.of();
        }
        PlayerInteractionSnapshot snapshot = context.inputSnapshot();
        AstPlayer astPlayer = AstPlayerCache.get(snapshot.player());
        if (!spawnerService.canViewSpawnerVisual(astPlayer)) {
            return List.of();
        }
        SpawnerHit hit = findTargetedSpawner(snapshot);
        if (hit == null || !snapshot.isVisible(hit.hitDistance())) {
            return List.of();
        }
        return List.of(new PlayerInputCandidate(
            "gathering-spawner-left-interaction",
            InteractionTier.WORLD_INTERACTION,
            hit.hitDistance(),
            InteractionCandidateOrder.GATHERING_SPAWNER,
            locationKey(hit.location()),
            InputClaimPolicy.CLAIM_AND_CANCEL,
            () -> {
                PlayerInteractionSnapshot currentSnapshot = snapshot.refresh();
                SpawnerHit current = findTargetedSpawner(currentSnapshot);
                return current != null
                    && locationKey(current.location()).equals(locationKey(hit.location()))
                    && currentSnapshot.isVisible(current.hitDistance());
            },
            () -> removeTargetedSpawner(snapshot.player(), hit.location())
        ));
    }

    private Collection<PlayerInputCandidate> resolveBlockMutation(
        PlayerInputContext<PlayerInteractionSnapshot> context
    ) {
        PlayerInteractionSnapshot snapshot = context.inputSnapshot();
        if (context.source() == InputSource.BLOCK_PLACE
            && snapshot.event() instanceof BlockPlaceEvent event) {
            String spawnerId = spawnerService.readSpawnerId(event.getItemInHand());
            if (spawnerId == null) {
                return List.of();
            }
            return List.of(new PlayerInputCandidate(
                "gathering-spawner-place",
                InteractionTier.WORLD_INTERACTION,
                0.0D,
                InteractionCandidateOrder.GATHERING_SPAWNER,
                snapshot.directTargetKey(),
                InputClaimPolicy.CLAIM_AND_CANCEL,
                () -> placeSpawner(event, spawnerId)
            ));
        }
        if (context.source() == InputSource.BLOCK_BREAK
            && snapshot.event() instanceof BlockBreakEvent event
            && spawnerService.hasLocation(event.getBlock().getLocation())) {
            return List.of(new PlayerInputCandidate(
                "gathering-spawner-break",
                InteractionTier.WORLD_INTERACTION,
                0.0D,
                InteractionCandidateOrder.GATHERING_SPAWNER,
                snapshot.directTargetKey(),
                InputClaimPolicy.CLAIM,
                () -> breakSpawner(event)
            ));
        }
        return List.of();
    }

    private void placeSpawner(BlockPlaceEvent event, String spawnerId) {
        AstPlayer astPlayer = AstPlayerCache.get(event.getPlayer());
        if (!spawnerService.isAdminMode(astPlayer)) {
            PlayerMessageService.getInstance().send(event.getPlayer(), PlayerMsgId.P_5719);
            return;
        }
        if (!spawnerService.registerLocation(spawnerId, event.getBlockPlaced().getLocation())) {
            PlayerMessageService.getInstance().send(event.getPlayer(), PlayerMsgId.P_5711, spawnerId);
            return;
        }
        consumePlacedItem(event.getPlayer(), event.getHand());
        PlayerMessageService.getInstance().send(event.getPlayer(), PlayerMsgId.P_5709, spawnerId);
    }

    private void breakSpawner(BlockBreakEvent event) {
        AstPlayer astPlayer = AstPlayerCache.get(event.getPlayer());
        if (!spawnerService.isAdminMode(astPlayer)) {
            event.setCancelled(true);
            PlayerMessageService.getInstance().send(event.getPlayer(), PlayerMsgId.P_5719);
            return;
        }
        if (spawnerService.removeLocation(event.getBlock().getLocation())) {
            PlayerMessageService.getInstance().send(event.getPlayer(), PlayerMsgId.P_5710);
        }
    }

    private void removeTargetedSpawner(Player player, Location target) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (!spawnerService.isAdminMode(astPlayer)) {
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5719);
            return;
        }
        if (spawnerService.removeLocation(target)) {
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5710);
        }
    }

    private SpawnerHit findTargetedSpawner(PlayerInteractionSnapshot snapshot) {
        if (snapshot.clickedBlock() != null
            && spawnerService.hasLocation(snapshot.clickedBlock().getLocation())) {
            Double hitDistance = snapshot.hitDistance(snapshot.clickedBlock());
            return hitDistance == null
                ? null
                : new SpawnerHit(snapshot.clickedBlock().getLocation(), hitDistance);
        }
        return spawnerService.getLocations().stream()
            .map(location -> location.toLocation())
            .filter(location -> location != null && location.getWorld() == snapshot.player().getWorld())
            .map(location -> {
                Double hitDistance = snapshot.ray().sphereEntryDistance(
                    location.clone().add(0.0D, 0.75D, 0.0D).toVector(),
                    TARGET_RADIUS
                );
                return hitDistance == null || hitDistance > TARGET_DISTANCE
                    ? null
                    : new SpawnerHit(location, hitDistance);
            })
            .filter(hit -> hit != null)
            .min(Comparator.comparingDouble(SpawnerHit::hitDistance)
                .thenComparing(hit -> locationKey(hit.location())))
            .orElse(null);
    }

    private void consumePlacedItem(Player player, EquipmentSlot hand) {
        if (player.getGameMode() == GameMode.CREATIVE) {
            return;
        }
        ItemStack itemStack = player.getInventory().getItem(hand);
        if (itemStack == null) {
            return;
        }
        if (itemStack.getAmount() <= 1) {
            player.getInventory().setItem(hand, null);
            return;
        }
        itemStack.setAmount(itemStack.getAmount() - 1);
        player.getInventory().setItem(hand, itemStack);
    }

    private String locationKey(Location location) {
        return location.getWorld().getUID() + ":"
            + location.getBlockX() + ":"
            + location.getBlockY() + ":"
            + location.getBlockZ();
    }

    private record SpawnerHit(Location location, double hitDistance) {
    }
}
