package io.github.maaasu.astralRecord.feature.skilltree.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreeNodeDefinition;
import io.github.maaasu.astralRecord.feature.skilltree.service.SkillTreeService;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import io.github.maaasu.astralRecord.shared.interaction.InputClaimPolicy;
import io.github.maaasu.astralRecord.shared.interaction.InputFamily;
import io.github.maaasu.astralRecord.shared.interaction.InteractionCandidateOrder;
import io.github.maaasu.astralRecord.shared.interaction.InteractionTier;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInputCandidate;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInputContext;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInputResolver;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInteractionSnapshot;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** スキルツリーの通常プレイヤー操作と表示ライフサイクルを扱います。 */
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
        if ((context.family() != InputFamily.LEFT_CLICK && context.family() != InputFamily.RIGHT_CLICK)
                || !context.inputSnapshot().isMainHandInput()) {
            return List.of();
        }

        PlayerInteractionSnapshot snapshot = context.inputSnapshot();
        if (!service.isPlayerModeSkillTree(snapshot.player())) {
            return List.of();
        }
        SkillTreeService.SkillTreePositionHit hit = service.findTargetedPositionHit(snapshot).orElse(null);
        if (hit == null) {
            return List.of();
        }
        SkillTreeNodeDefinition node = service.getNode(hit.position().nodeId());
        if (node == null) {
            return List.of();
        }
        return List.of(new PlayerInputCandidate(
                "skill-tree-player-control",
                InteractionTier.EXCLUSIVE_CONTEXT,
                hit.hitDistance(),
                InteractionCandidateOrder.SKILL_TREE,
                hit.position().nodeId(),
                InputClaimPolicy.CLAIM_AND_CANCEL,
                () -> isSameTarget(snapshot, hit),
                () -> handlePlayerModeInteraction(snapshot.player(), context.family(), node)
        ));
    }

    private boolean isSameTarget(
            PlayerInteractionSnapshot snapshot,
            SkillTreeService.SkillTreePositionHit expected
    ) {
        PlayerInteractionSnapshot currentSnapshot = snapshot.refresh();
        SkillTreeService.SkillTreePositionHit current = service.findTargetedPositionHit(currentSnapshot).orElse(null);
        return current != null && current.position().nodeId().equals(expected.position().nodeId());
    }

    private void handlePlayerModeInteraction(
            Player player,
            InputFamily family,
            SkillTreeNodeDefinition node
    ) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null) {
            return;
        }
        service.preloadState(astPlayer);
        if (!service.isStateReady(astPlayer)) {
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5836);
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
        if (service.requiresCpSourceSelection(node)) {
            openCpSourceSelection(player, astPlayer, node);
            return;
        }
        completeUnlock(player, astPlayer, node, null);
    }

    private void completeUnlock(
            @NotNull Player player,
            @NotNull AstPlayer astPlayer,
            @NotNull SkillTreeNodeDefinition node,
            String consumedClassId
    ) {
        boolean canUnlock = consumedClassId == null
                ? service.canUnlockNode(astPlayer, node)
                : service.canUnlockNode(astPlayer, node, consumedClassId);
        if (!canUnlock) {
            playDenied(player, 0.75F);
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5825);
            return;
        }
        boolean unlocked = consumedClassId == null
                ? service.unlockNode(astPlayer, node)
                : service.unlockNode(astPlayer, node, consumedClassId);
        if (unlocked) {
            playUnlock(player);
            PlayerMessageService.getInstance().send(
                    player,
                    PlayerMsgId.P_5824,
                    ColorCodeUtil.toLegacyText(node.name(), node.nodeId())
            );
        } else {
            playDenied(player, 0.75F);
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5825);
        }
    }

    private void openCpSourceSelection(
            @NotNull Player player,
            @NotNull AstPlayer astPlayer,
            @NotNull SkillTreeNodeDefinition node
    ) {
        List<SkillTreeService.CpSourceOption> options = service.cpSourceOptions(astPlayer);
        int inventorySize = Math.max(9, Math.min(54, ((options.size() + 8) / 9) * 9));
        CpSourceSelectionHolder holder = new CpSourceSelectionHolder(
                astPlayer.getAccount().getUuid(),
                node.nodeId()
        );
        Inventory inventory = Bukkit.createInventory(
                holder,
                inventorySize,
                Component.text("CP消費元クラスを選択", NamedTextColor.DARK_AQUA)
        );
        holder.bind(inventory);
        for (int slot = 0; slot < options.size() && slot < inventorySize; slot++) {
            SkillTreeService.CpSourceOption option = options.get(slot);
            boolean affordable = option.availablePoints() >= node.pointCost();
            ItemStack item = new ItemStack(affordable ? Material.EXPERIENCE_BOTTLE : Material.GLASS_BOTTLE);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.displayName(Component.text(
                        ColorCodeUtil.toPlainText(option.displayName(), option.classId()),
                        affordable ? NamedTextColor.AQUA : NamedTextColor.DARK_GRAY
                ).decoration(TextDecoration.ITALIC, false));
                meta.lore(List.of(
                        Component.text("クラスLv. " + option.classLevel(), NamedTextColor.GRAY)
                                .decoration(TextDecoration.ITALIC, false),
                        Component.text("残りCP: " + option.availablePoints(), NamedTextColor.YELLOW)
                                .decoration(TextDecoration.ITALIC, false),
                        Component.text("消費CP: " + node.pointCost(), NamedTextColor.GOLD)
                                .decoration(TextDecoration.ITALIC, false),
                        Component.text(
                                affordable ? "クリックしてこのクラスのCPを消費" : "CPが不足しています",
                                affordable ? NamedTextColor.GREEN : NamedTextColor.RED
                        ).decoration(TextDecoration.ITALIC, false)
                ));
                item.setItemMeta(meta);
            }
            inventory.setItem(slot, item);
            holder.classIdsBySlot.put(slot, option.classId());
        }
        player.openInventory(inventory);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCpSourceSelectionClick(@NotNull InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof CpSourceSelectionHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
                || event.getRawSlot() < 0
                || event.getRawSlot() >= event.getView().getTopInventory().getSize()) {
            return;
        }
        String classId = holder.classIdsBySlot.get(event.getRawSlot());
        if (classId == null) {
            return;
        }
        AstPlayer astPlayer = AstPlayerCache.get(player);
        SkillTreeNodeDefinition node = service.getNode(holder.nodeId);
        if (astPlayer == null
                || !holder.accountId.equals(astPlayer.getAccount().getUuid())
                || node == null
                || !service.canUnlockNode(astPlayer, node, classId)) {
            playDenied(player, 0.75F);
            return;
        }
        player.closeInventory();
        completeUnlock(player, astPlayer, node, classId);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCpSourceSelectionDrag(@NotNull InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof CpSourceSelectionHolder)) {
            return;
        }
        int topSize = event.getView().getTopInventory().getSize();
        if (event.getRawSlots().stream().anyMatch(slot -> slot < topSize)) {
            event.setCancelled(true);
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
                    ColorCodeUtil.toLegacyText(node.name(), node.nodeId())
            );
        } else {
            playDenied(player, 0.75F);
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5827);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeldSlotChange(@NotNull PlayerItemHeldEvent event) {
        if (service.isPlayerModeSkillTree(event.getPlayer())) {
            service.markViewerContextDirty(event.getPlayer());
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

    private void playUnlock(@NotNull Player player) {
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, SoundCategory.PLAYERS, 0.45F, 1.45F);
        player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 0.35F, 1.8F);
    }

    private void playRelock(@NotNull Player player) {
        player.playSound(player.getLocation(), Sound.ITEM_ARMOR_EQUIP_CHAIN, SoundCategory.PLAYERS, 0.55F, 1.2F);
    }

    private void playDenied(@NotNull Player player, float pitch) {
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, SoundCategory.PLAYERS, 0.45F, pitch);
    }

    private boolean shouldRefreshSkillTreeVisuals(@NotNull Player player) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        return service.isAdminMode(astPlayer) || service.isPlayerModeSkillTree(player);
    }

    private static final class CpSourceSelectionHolder implements InventoryHolder {
        private final UUID accountId;
        private final String nodeId;
        private final Map<Integer, String> classIdsBySlot = new LinkedHashMap<>();
        private Inventory inventory;

        private CpSourceSelectionHolder(@NotNull UUID accountId, @NotNull String nodeId) {
            this.accountId = accountId;
            this.nodeId = nodeId;
        }

        private void bind(@NotNull Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }
}
