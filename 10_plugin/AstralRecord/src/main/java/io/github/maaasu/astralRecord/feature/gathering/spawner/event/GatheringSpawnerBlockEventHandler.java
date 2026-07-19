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
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;

/**
 * Gathering spawner の block mutation 候補を提供します。
 */
public final class GatheringSpawnerBlockEventHandler
    implements PlayerInputResolver<PlayerInteractionSnapshot> {
    private final GatheringSpawnerService spawnerService;

    public GatheringSpawnerBlockEventHandler(@NotNull GatheringSpawnerService spawnerService) {
        this.spawnerService = spawnerService;
    }

    @Override
    public @NotNull Collection<PlayerInputCandidate> resolve(
        @NotNull PlayerInputContext<PlayerInteractionSnapshot> context
    ) {
        if (context.family() != InputFamily.BLOCK_MUTATION) {
            return List.of();
        }
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
}
