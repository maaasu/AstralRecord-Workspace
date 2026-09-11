package io.github.maaasu.astralRecord.feature.skilltree.event;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreeNodeDefinition;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreePointType;
import io.github.maaasu.astralRecord.feature.skilltree.model.SkillTreePosition;
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
import io.github.maaasu.astralRecord.shared.teleport.PlayerTeleportService;
import io.github.maaasu.astralRecord.shared.gui.sound.GuiSound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** スキルツリーの通常プレイヤー操作と表示ライフサイクルを扱います。 */
public class SkillTreeEventHandler extends AbstractEventHandler
        implements PlayerInputResolver<PlayerInteractionSnapshot> {
    private static final double NODE_BEACON_TELEPORT_DISTANCE = 5.0D;
    private static final double NODE_BEACON_TELEPORT_DISTANCE_SQUARED =
            NODE_BEACON_TELEPORT_DISTANCE * NODE_BEACON_TELEPORT_DISTANCE;
    private static final int RELOCK_CONFIRMATION_SIZE = 27;
    private static final int RELOCK_CONFIRM_SLOT = 11;
    private static final int RELOCK_CONFIRMATION_OPTION_SLOT = 13;
    private static final int RELOCK_CANCEL_SLOT = 15;

    private final SkillTreeService service;
    private final Set<UUID> relockConfirmationSuppressed = new HashSet<>();
    private final Set<UUID> pendingNodeMutations = new HashSet<>();

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
        boolean leftClick = context.family() == InputFamily.LEFT_CLICK;
        SkillTreeService.SkillTreePositionHit beaconHit = leftClick
                ? service.findTargetedBeaconPositionHit(snapshot).orElse(null)
                : null;
        SkillTreeService.SkillTreePositionHit hit = beaconHit != null
                ? beaconHit
                : service.findTargetedPositionHit(snapshot).orElse(null);
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
                () -> isSameTarget(snapshot, hit, beaconHit != null),
                () -> handlePlayerModeInteraction(
                        snapshot.player(),
                        context.family(),
                        node,
                        hit.position(),
                        beaconHit != null
                )
        ));
    }

    private boolean isSameTarget(
            PlayerInteractionSnapshot snapshot,
            SkillTreeService.SkillTreePositionHit expected,
            boolean beaconTarget
    ) {
        PlayerInteractionSnapshot currentSnapshot = snapshot.refresh();
        SkillTreeService.SkillTreePositionHit current = (beaconTarget
                ? service.findTargetedBeaconPositionHit(currentSnapshot)
                : service.findTargetedPositionHit(currentSnapshot))
                .orElse(null);
        return current != null && current.position().nodeId().equals(expected.position().nodeId());
    }

    private void handlePlayerModeInteraction(
            Player player,
            InputFamily family,
            SkillTreeNodeDefinition node,
            SkillTreePosition position,
            boolean beaconTarget
    ) {
        if (family == InputFamily.LEFT_CLICK && beaconTarget && teleportToDistantBeacon(player, position)) {
            return;
        }
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null) {
            return;
        }
        service.preloadState(astPlayer);
        if (!service.isStateReady(astPlayer)) {
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5836);
            return;
        }
        if (pendingNodeMutations.contains(astPlayer.getAccount().getUuid())) {
            playDenied(player, 0.75F);
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5825);
            return;
        }
        if (family == InputFamily.LEFT_CLICK) {
            unlockNode(player, astPlayer, node);
            return;
        }
        relockNode(player, astPlayer, node);
    }

    /**
     * 5m以上離れたノード強調ビームへ、現在の高さと視線方向を維持して移動します。
     *
     * @param player 移動するプレイヤー
     * @param position 移動先となるノード位置
     * @return テレポート要求を実行した場合は {@code true}
     */
    private boolean teleportToDistantBeacon(@NotNull Player player, @NotNull SkillTreePosition position) {
        Location beaconLocation = position.toLocation();
        Location currentLocation = player.getLocation();
        if (beaconLocation == null
                || beaconLocation.getWorld() == null
                || beaconLocation.getWorld() != currentLocation.getWorld()
                || !isAtLeastBeaconTeleportDistance(currentLocation, beaconLocation)) {
            return false;
        }
        Location target = new Location(
                currentLocation.getWorld(),
                beaconLocation.getX(),
                currentLocation.getY(),
                beaconLocation.getZ()
        );
        PlayerTeleportService.teleport(player, target, PlayerTeleportEvent.TeleportCause.PLUGIN);
        return true;
    }

    /**
     * プレイヤーのXZ平面上の距離が、ノードビーム移動の開始距離以上かを判定します。
     *
     * @param playerLocation プレイヤー現在位置
     * @param beaconLocation ノードビーム基準位置
     * @return 水平距離が5m以上なら {@code true}
     */
    private boolean isAtLeastBeaconTeleportDistance(
            @NotNull Location playerLocation,
            @NotNull Location beaconLocation
    ) {
        double deltaX = playerLocation.getX() - beaconLocation.getX();
        double deltaZ = playerLocation.getZ() - beaconLocation.getZ();
        return deltaX * deltaX + deltaZ * deltaZ >= NODE_BEACON_TELEPORT_DISTANCE_SQUARED;
    }

    private void unlockNode(Player player, AstPlayer astPlayer, SkillTreeNodeDefinition node) {
        if (service.isNodeUnlocked(astPlayer, node)) {
            playDenied(player, 1.15F);
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5838);
            return;
        }
        if (node.pointCost() > 0 && !service.hasAvailableUnlockPoint(astPlayer)) {
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
        UUID accountId = astPlayer.getAccount().getUuid();
        if (!pendingNodeMutations.add(accountId)) {
            playDenied(player, 0.75F);
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5825);
            return;
        }
        (consumedClassId == null
                ? service.unlockNodeAsync(astPlayer, node)
                : service.unlockNodeAsync(astPlayer, node, consumedClassId))
                .whenComplete((mutation, failure) -> runOnMainThread(() -> {
                    pendingNodeMutations.remove(accountId);
                    if (failure != null || mutation == null || !mutation.changed()) {
                        playDenied(player, 0.75F);
                        PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5825);
                        return;
                    }
                    service.acknowledgeCommittedNodeMutation(astPlayer, node, mutation, true);
                    if (!player.isOnline()) {
                        return;
                    }
                    playUnlock(player);
                    sendUnlockSuccess(player, astPlayer, node, consumedClassId);
                }));
    }

    /**
     * ノード解放成功通知へ、解放後の実際のポイント残高を付加して送信します。
     * CPノードは実際に消費したクラス、PPノードはプレイヤー全体のPPを残高の対象にします。
     *
     * @param player 通知先プレイヤー
     * @param astPlayer ポイント残高を持つプレイヤー
     * @param node 解放したノード
     * @param consumedClassId CPの消費元クラスID。ノード条件から決まる場合はnull
     */
    private void sendUnlockSuccess(
            @NotNull Player player,
            @NotNull AstPlayer astPlayer,
            @NotNull SkillTreeNodeDefinition node,
            String consumedClassId
    ) {
        String pointLabel;
        int remainingPoints;
        if (node.pointType() == SkillTreePointType.PASSIVE_POINT) {
            pointLabel = node.pointType().displayName();
            remainingPoints = service.availablePassivePoints(astPlayer);
        } else {
            String sourceClassId = consumedClassId != null
                    ? consumedClassId
                    : node.unlockCondition().classId();
            SkillTreeService.CpSourceOption source = sourceClassId == null
                    ? null
                    : service.cpSourceOptions(astPlayer).stream()
                    .filter(option -> option.classId().equalsIgnoreCase(sourceClassId))
                    .findFirst()
                    .orElse(null);
            if (source == null && sourceClassId == null) {
                pointLabel = service.currentClassPointLabel(astPlayer);
                remainingPoints = service.availableClassPoints(astPlayer);
            } else if (source == null) {
                pointLabel = node.pointType().displayName();
                remainingPoints = service.availableClassPoints(astPlayer, sourceClassId);
            } else {
                String className = ColorCodeUtil.toPlainText(source.displayName(), "");
                if (className.isBlank() || className.equalsIgnoreCase(source.classId())) {
                    className = "未登録のクラス";
                }
                pointLabel = node.pointType().displayName() + "[" + className + "]";
                remainingPoints = source.availablePoints();
            }
        }
        PlayerMessageService.getInstance().send(
                player,
                PlayerMsgId.P_5824,
                ColorCodeUtil.toLegacyText(node.name(), node.nodeId()),
                pointLabel,
                remainingPoints
        );
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
        GuiSound.OPEN.play(player);
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
            GuiSound.DENY.play(player);
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
        GuiSound.CONFIRM.play(player);
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
        if (relockConfirmationSuppressed.contains(player.getUniqueId())) {
            completeRelock(player, astPlayer, node);
            return;
        }
        openRelockConfirmation(player, astPlayer, node);
    }

    private void completeRelock(
            @NotNull Player player,
            @NotNull AstPlayer astPlayer,
            @NotNull SkillTreeNodeDefinition node
    ) {
        UUID accountId = astPlayer.getAccount().getUuid();
        if (!pendingNodeMutations.add(accountId)) {
            playDenied(player, 0.75F);
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5827);
            return;
        }
        service.relockNodeAsync(astPlayer, node).whenComplete((mutation, failure) -> runOnMainThread(() -> {
            pendingNodeMutations.remove(accountId);
            if (failure != null || mutation == null || !mutation.changed()) {
                playDenied(player, 0.75F);
                PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5827);
                return;
            }
            service.acknowledgeCommittedNodeMutation(astPlayer, node, mutation, false);
            if (!player.isOnline()) {
                return;
            }
            GuiSound.SKILL_RELOCK.play(player);
            PlayerMessageService.getInstance().send(
                    player,
                    PlayerMsgId.P_5826,
                    ColorCodeUtil.toLegacyText(node.name(), node.nodeId()),
                    SkillTreeService.RELOCK_GOLD_COST
            );
        }));
    }

    private void runOnMainThread(@NotNull Runnable action) {
        if (Bukkit.isPrimaryThread()) {
            action.run();
            return;
        }
        Bukkit.getScheduler().runTask(AstralRecord.getInstance(), action);
    }

    private void openRelockConfirmation(
            @NotNull Player player,
            @NotNull AstPlayer astPlayer,
            @NotNull SkillTreeNodeDefinition node
    ) {
        RelockConfirmationHolder holder = new RelockConfirmationHolder(
                astPlayer.getAccount().getUuid(),
                node.nodeId()
        );
        Inventory inventory = Bukkit.createInventory(
                holder,
                RELOCK_CONFIRMATION_SIZE,
                Component.text("スキルノード解除の確認", NamedTextColor.DARK_RED)
        );
        holder.bind(inventory);
        renderRelockConfirmation(inventory, node, holder.dontAskAgain);
        GuiSound.OPEN.play(player);
        player.openInventory(inventory);
    }

    private void renderRelockConfirmation(
            @NotNull Inventory inventory,
            @NotNull SkillTreeNodeDefinition node,
            boolean dontAskAgain
    ) {
        inventory.setItem(RELOCK_CONFIRM_SLOT, createRelockConfirmationItem(
                Material.GREEN_CONCRETE,
                "解除する",
                NamedTextColor.GREEN,
                List.of(
                        Component.text("100ゴールドを消費して", NamedTextColor.YELLOW),
                        Component.text("このノードを解除します", NamedTextColor.YELLOW),
                        Component.text(node.name(), NamedTextColor.GRAY)
                )
        ));
        inventory.setItem(RELOCK_CONFIRMATION_OPTION_SLOT, createRelockConfirmationItem(
                dontAskAgain ? Material.LIME_DYE : Material.GRAY_DYE,
                dontAskAgain ? "確認GUIを表示しない: ON" : "確認GUIを表示しない: OFF",
                dontAskAgain ? NamedTextColor.GREEN : NamedTextColor.GRAY,
                List.of(Component.text(
                        "このスキルツリーワールドを離れるまで有効",
                        NamedTextColor.DARK_GRAY
                ))
        ));
        inventory.setItem(RELOCK_CANCEL_SLOT, createRelockConfirmationItem(
                Material.RED_CONCRETE,
                "キャンセル",
                NamedTextColor.RED,
                List.of(Component.text("解除せずに戻る", NamedTextColor.GRAY))
        ));
    }

    private ItemStack createRelockConfirmationItem(
            @NotNull Material material,
            @NotNull String name,
            @NotNull NamedTextColor color,
            @NotNull List<Component> lore
    ) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(name, color).decoration(TextDecoration.ITALIC, false));
            meta.lore(lore.stream()
                    .map(line -> line.decoration(TextDecoration.ITALIC, false))
                    .toList());
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * ノード解除確認GUIのクリックを処理します。
     *
     * @param event インベントリクリックイベント
     * @implNote 対象アカウント、ノード、ワールドを再検証してから解除します。
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRelockConfirmationClick(@NotNull InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof RelockConfirmationHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
                || event.getRawSlot() < 0
                || event.getRawSlot() >= event.getView().getTopInventory().getSize()) {
            return;
        }
        AstPlayer astPlayer = AstPlayerCache.get(player);
        SkillTreeNodeDefinition node = service.getNode(holder.nodeId);
        if (astPlayer == null
                || !holder.accountId.equals(astPlayer.getAccount().getUuid())
                || !service.isPlayerModeSkillTree(player)
                || node == null
                || !service.isNodeUnlocked(astPlayer, node)) {
            player.closeInventory();
            playDenied(player, 0.75F);
            return;
        }
        if (event.getRawSlot() == RELOCK_CONFIRMATION_OPTION_SLOT) {
            holder.dontAskAgain = !holder.dontAskAgain;
            renderRelockConfirmation(event.getView().getTopInventory(), node, holder.dontAskAgain);
            GuiSound.TOGGLE.play(player);
            return;
        }
        if (event.getRawSlot() == RELOCK_CANCEL_SLOT) {
            player.closeInventory();
            GuiSound.CLOSE.play(player);
            return;
        }
        if (event.getRawSlot() != RELOCK_CONFIRM_SLOT) {
            return;
        }
        if (holder.dontAskAgain) {
            relockConfirmationSuppressed.add(player.getUniqueId());
        }
        player.closeInventory();
        completeRelock(player, astPlayer, node);
    }

    /**
     * ノード解除確認GUIへのアイテムドラッグを抑止します。
     *
     * @param event インベントリドラッグイベント
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRelockConfirmationDrag(@NotNull InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof RelockConfirmationHolder) {
            int topSize = event.getView().getTopInventory().getSize();
            if (event.getRawSlots().stream().anyMatch(slot -> slot < topSize)) {
                event.setCancelled(true);
            }
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
        if (service.isSkillTreeWorld(event.getFrom()) && !service.isSkillTreeWorld(event.getPlayer().getWorld())) {
            relockConfirmationSuppressed.remove(event.getPlayer().getUniqueId());
        }
        if (service.isPlayerModeSkillTree(event.getPlayer())) {
            service.markViewerContextDirty(event.getPlayer());
            return;
        }
        service.clearPlayerPresentation(event.getPlayer());
        service.markViewerContextDirty(event.getPlayer());
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
        relockConfirmationSuppressed.remove(event.getPlayer().getUniqueId());
        service.restorePlayerVisibility(event.getPlayer());
        service.clearPlayerPresentation(event.getPlayer());
    }

    private void playUnlock(@NotNull Player player) {
        GuiSound.SKILL_LEARN.play(player);
    }

    private void playDenied(@NotNull Player player, float pitch) {
        GuiSound.DENY.play(player, pitch);
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

    private static final class RelockConfirmationHolder implements InventoryHolder {
        private final UUID accountId;
        private final String nodeId;
        private boolean dontAskAgain;
        private Inventory inventory;

        private RelockConfirmationHolder(@NotNull UUID accountId, @NotNull String nodeId) {
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
