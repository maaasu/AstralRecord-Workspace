package io.github.maaasu.astralRecord.feature.skilltree.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreeNodeDefinition;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreePosition;
import io.github.maaasu.astralRecord.feature.skilltree.service.SkillTreeService;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import io.github.maaasu.astralRecord.shared.interaction.InputClaimPolicy;
import io.github.maaasu.astralRecord.shared.interaction.InputFamily;
import io.github.maaasu.astralRecord.shared.interaction.InputSource;
import io.github.maaasu.astralRecord.shared.interaction.InteractionCandidateOrder;
import io.github.maaasu.astralRecord.shared.interaction.InteractionTier;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInputCandidate;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInputContext;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInputResolver;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInteractionSnapshot;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * スキルツリー管理アイテムとプレイヤー操作のイベントを扱います。
 */
public class SkillTreeEventHandler extends AbstractEventHandler
    implements PlayerInputResolver<PlayerInteractionSnapshot> {
    private final SkillTreeService service;

    public SkillTreeEventHandler(@NotNull SkillTreeService service) {
        this.service = service;
    }

    @Override
    public @NotNull Collection<PlayerInputCandidate> resolve(
        @NotNull PlayerInputContext<PlayerInteractionSnapshot> context
    ) {
        if (context.family() == InputFamily.HOTBAR_SLOT) {
            return resolveHotbarSlot(context);
        }
        if (context.family() == InputFamily.BLOCK_MUTATION) {
            return resolveBlockMutation(context);
        }
        if ((context.family() != InputFamily.LEFT_CLICK && context.family() != InputFamily.RIGHT_CLICK)
            || !context.inputSnapshot().isMainHandInput()) {
            return List.of();
        }

        PlayerInteractionSnapshot snapshot = context.inputSnapshot();
        ItemStack held = snapshot.player().getInventory().getItemInMainHand();
        String positionId = service.readPositionItemId(held);
        boolean connector = service.isConnectorItem(held);
        SkillTreeService.SkillTreePositionHit hit = service.findTargetedPositionHit(snapshot.player()).orElse(null);
        double hitDistance = hit == null ? 0.0D : hit.hitDistance();
        String targetKey = hit == null
            ? snapshot.player().getUniqueId().toString()
            : hit.position().positionId();

        if (positionId != null || connector) {
            String candidateTargetKey = positionId == null ? "connector:" + targetKey : "position:" + positionId;
            return List.of(new PlayerInputCandidate(
                "skill-tree-setup-control",
                InteractionTier.EXCLUSIVE_CONTEXT,
                hitDistance,
                InteractionCandidateOrder.SKILL_TREE,
                candidateTargetKey,
                InputClaimPolicy.CLAIM_AND_CANCEL,
                () -> isSameTarget(snapshot, hit),
                () -> handleAdminInteraction(snapshot, context.family(), positionId, connector)
            ));
        }
        if (!service.isPlayerModeSkillTree(snapshot.player())) {
            return List.of();
        }
        if (hit == null) {
            return List.of();
        }
        return List.of(new PlayerInputCandidate(
            "skill-tree-player-control",
            InteractionTier.EXCLUSIVE_CONTEXT,
            hitDistance,
            InteractionCandidateOrder.SKILL_TREE,
            targetKey,
            InputClaimPolicy.CLAIM_AND_CANCEL,
            () -> isSameTarget(snapshot, hit),
            () -> handlePlayerModeInteraction(snapshot.player(), context.family())
        ));
    }

    private boolean isSameTarget(
        PlayerInteractionSnapshot snapshot,
        SkillTreeService.SkillTreePositionHit expected
    ) {
        PlayerInteractionSnapshot currentSnapshot = snapshot.refresh();
        SkillTreeService.SkillTreePositionHit current = service.findTargetedPositionHit(snapshot.player()).orElse(null);
        if (expected == null) {
            return current == null;
        }
        return current != null
            && current.position().positionId().equals(expected.position().positionId())
            && currentSnapshot.isVisible(current.hitDistance());
    }

    private Collection<PlayerInputCandidate> resolveHotbarSlot(
        PlayerInputContext<PlayerInteractionSnapshot> context
    ) {
        PlayerInteractionSnapshot snapshot = context.inputSnapshot();
        if (!(snapshot.event() instanceof PlayerItemHeldEvent event)
            || !service.shouldSuppressSkillTreeSetupControls(snapshot.player())) {
            return List.of();
        }
        return List.of(new PlayerInputCandidate(
            "skill-tree-setup-hotbar-guard",
            InteractionTier.EXCLUSIVE_CONTEXT,
            0.0D,
            InteractionCandidateOrder.SKILL_TREE,
            context.playerId().toString(),
            InputClaimPolicy.CLAIM_AND_CANCEL,
            () -> org.bukkit.Bukkit.getScheduler().runTask(
                io.github.maaasu.astralRecord.AstralRecord.getInstance(),
                () -> event.getPlayer().getInventory().setHeldItemSlot(event.getPreviousSlot())
            )
        ));
    }

    private Collection<PlayerInputCandidate> resolveBlockMutation(
        PlayerInputContext<PlayerInteractionSnapshot> context
    ) {
        PlayerInteractionSnapshot snapshot = context.inputSnapshot();
        boolean setupMutation = context.source() == InputSource.BLOCK_PLACE
            && snapshot.event() instanceof BlockPlaceEvent placeEvent
            && service.isSkillTreeSetupItem(placeEvent.getItemInHand());
        if (!setupMutation && context.source() == InputSource.BLOCK_BREAK) {
            setupMutation = snapshot.event() instanceof BlockBreakEvent
                && service.isSkillTreeSetupItem(snapshot.player().getInventory().getItemInMainHand());
        }
        if (!setupMutation) {
            return List.of();
        }
        return List.of(new PlayerInputCandidate(
            "skill-tree-setup-block-guard",
            InteractionTier.EXCLUSIVE_CONTEXT,
            0.0D,
            InteractionCandidateOrder.SKILL_TREE,
            snapshot.directTargetKey(),
            InputClaimPolicy.CLAIM_AND_CANCEL,
            () -> {
            }
        ));
    }

    private void handleAdminInteraction(
        PlayerInteractionSnapshot snapshot,
        InputFamily family,
        String positionId,
        boolean connector
    ) {
        AstPlayer astPlayer = AstPlayerCache.get(snapshot.player());
        if (!service.isAdminMode(astPlayer)) {
            PlayerMessageService.getInstance().send(snapshot.player(), PlayerMsgId.P_5719);
            return;
        }
        if (positionId != null) {
            handlePositionItem(snapshot, family, positionId);
        } else if (connector) {
            handleConnectorItem(snapshot.player(), family);
        }
    }

    private void handlePlayerModeInteraction(Player player, InputFamily family) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null) {
            return;
        }
        service.preloadState(astPlayer);
        if (!service.isStateReady(astPlayer)) {
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5836);
            return;
        }
        SkillTreeNodeDefinition node = service.findTargetedNode(player).orElse(null);
        if (node == null) {
            playDenied(player, 0.85F);
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5828);
            return;
        }
        if (family == InputFamily.LEFT_CLICK) {
            unlockNode(player, astPlayer, node);
            return;
        }
        relockNode(player, astPlayer, node);
    }

    private void unlockNode(Player player, AstPlayer astPlayer, SkillTreeNodeDefinition node) {
        if (service.isNodeUnlocked(astPlayer, node)) {
            playDenied(player, 1.15F);
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5838);
            return;
        }
        if (!service.hasAvailableUnlockPoint(astPlayer)) {
            playDenied(player, 0.65F);
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5839);
            return;
        }
        if (!service.canUnlockNode(astPlayer, node)) {
            playDenied(player, 0.75F);
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5825);
            return;
        }
        if (service.unlockNode(astPlayer, node)) {
            playUnlock(player);
            PlayerMessageService.getInstance().send(
                player,
                PlayerMsgId.P_5824,
                ColorCodeUtil.toLegacyText(node.name(), node.id())
            );
        } else {
            playDenied(player, 0.75F);
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5825);
        }
    }

    private void relockNode(Player player, AstPlayer astPlayer, SkillTreeNodeDefinition node) {
        if (!service.isNodeUnlocked(astPlayer, node)) {
            playDenied(player, 1.0F);
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5840);
            return;
        }
        if (!service.canAffordRelock(astPlayer)) {
            playDenied(player, 0.6F);
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5841);
            return;
        }
        if (service.relockNode(astPlayer, node)) {
            playRelock(player);
            PlayerMessageService.getInstance().send(
                player,
                PlayerMsgId.P_5826,
                ColorCodeUtil.toLegacyText(node.name(), node.id())
            );
        } else {
            playDenied(player, 0.75F);
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5827);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeldSlotChange(@NotNull PlayerItemHeldEvent event) {
        if (!service.isPlayerModeSkillTree(event.getPlayer())) {
            return;
        }
        service.markViewerContextDirty(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(@NotNull InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof org.bukkit.entity.Player player)) {
            return;
        }
        if (service.shouldSuppressSkillTreeSetupControls(player)
                && event.getClickedInventory() == player.getInventory()
                && event.getSlot() >= 0
                && event.getSlot() <= 8) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerJoin(@NotNull PlayerJoinEvent event) {
        service.refreshPlayerVisibility(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldChange(@NotNull PlayerChangedWorldEvent event) {
        service.refreshPlayerVisibility(event.getPlayer());
        if (service.isPlayerModeSkillTree(event.getPlayer())) {
            service.markViewerContextDirty(event.getPlayer());
            return;
        }
        service.clearPlayerPresentation(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(@NotNull PlayerMoveEvent event) {
        if (!shouldRefreshSkillTreeVisuals(event.getPlayer()) || event.getTo() == null) {
            return;
        }
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getWorld() != to.getWorld()
                || Double.compare(from.getX(), to.getX()) != 0
                || Double.compare(from.getY(), to.getY()) != 0
                || Double.compare(from.getZ(), to.getZ()) != 0) {
            service.markViewerContextDirty(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTeleport(@NotNull PlayerTeleportEvent event) {
        service.refreshPlayerVisibility(event.getPlayer());
        service.markViewerContextDirty(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerQuit(@NotNull PlayerQuitEvent event) {
        service.restorePlayerVisibility(event.getPlayer());
        service.clearPlayerPresentation(event.getPlayer());
    }

    private void handlePositionItem(
        @NotNull PlayerInteractionSnapshot snapshot,
        @NotNull InputFamily family,
        @NotNull String positionId
    ) {
        Player player = snapshot.player();
        if (family == InputFamily.RIGHT_CLICK && snapshot.clickedBlock() != null) {
            BlockFace face = snapshot.blockFace() == null ? BlockFace.UP : snapshot.blockFace();
            Location location = placementLocation(snapshot.clickedBlock(), face);
            service.registerPosition(positionId, location);
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5821, positionId);
            return;
        }
        if (family == InputFamily.LEFT_CLICK) {
            SkillTreePosition target = service.findTargetedPosition(player).orElse(null);
            if (target != null && service.removePosition(target.positionId())) {
                PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5822, target.positionId());
            }
        }
    }

    private void handleConnectorItem(@NotNull Player player, @NotNull InputFamily family) {
        SkillTreePosition target = service.findTargetedPosition(player).orElse(null);
        if (target == null) {
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5823);
            return;
        }
        if (family == InputFamily.LEFT_CLICK) {
            service.selectConnectorLeft(player.getUniqueId(), target.positionId());
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5830, target.positionId());
            return;
        }
        if (family == InputFamily.RIGHT_CLICK) {
            String left = service.consumeConnectorLeft(player.getUniqueId());
            if (left == null) {
                PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5831);
                return;
            }
            if (service.toggleConnection(left, target.positionId())) {
                PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5832, left, target.positionId());
            }
        }
    }

    @NotNull
    private Location placementLocation(@NotNull Block clicked, @NotNull BlockFace face) {
        return clicked.getRelative(face).getLocation();
    }

    private void playUnlock(@NotNull org.bukkit.entity.Player player) {
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, SoundCategory.PLAYERS, 0.45F, 1.45F);
        player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 0.35F, 1.8F);
    }

    private void playRelock(@NotNull org.bukkit.entity.Player player) {
        player.playSound(player.getLocation(), Sound.ITEM_ARMOR_EQUIP_CHAIN, SoundCategory.PLAYERS, 0.55F, 1.2F);
    }

    private void playDenied(@NotNull org.bukkit.entity.Player player, float pitch) {
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, SoundCategory.PLAYERS, 0.45F, pitch);
    }

    private boolean shouldRefreshSkillTreeVisuals(@NotNull Player player) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        return service.isAdminMode(astPlayer) || service.isPlayerModeSkillTree(player);
    }

}
