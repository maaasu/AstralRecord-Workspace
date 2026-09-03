package io.github.maaasu.astralRecord.feature.dungeon.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.dungeon.gui.DungeonRewardGui;
import io.github.maaasu.astralRecord.feature.dungeon.gui.DungeonArchiveGui;
import io.github.maaasu.astralRecord.feature.dungeon.gui.DungeonMapGui;
import io.github.maaasu.astralRecord.feature.dungeon.service.DungeonService;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.shared.gui.hotbar.HotbarShortcutClickSupport;
import io.github.maaasu.astralRecord.shared.gui.sound.GuiSound;
import io.github.maaasu.astralRecord.shared.interaction.InputClaimPolicy;
import io.github.maaasu.astralRecord.shared.interaction.InputFamily;
import io.github.maaasu.astralRecord.shared.interaction.InteractionCandidateOrder;
import io.github.maaasu.astralRecord.shared.interaction.InteractionTier;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInputCandidate;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInputContext;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInputResolver;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInteractionSnapshot;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Dungeon の受付・帰還・中止・報酬 GUI 入力を共通 gateway へ接続します。 */
public final class DungeonInteractionEventHandler extends AbstractEventHandler
        implements PlayerInputResolver<PlayerInteractionSnapshot> {
    private final DungeonService service;
    private final InventoryService inventoryService;

    /**
     * Dungeon 固有入力と Plugin GUI 共通インベントリ操作を構成します。
     *
     * @param service Dungeon サービス
     * @param inventoryService 共通インベントリ操作サービス
     */
    public DungeonInteractionEventHandler(
            @NotNull DungeonService service,
            @NotNull InventoryService inventoryService
    ) {
        this.service = service;
        this.inventoryService = inventoryService;
    }

    @Override
    public @NotNull Collection<PlayerInputCandidate> resolve(
            @NotNull PlayerInputContext<PlayerInteractionSnapshot> context
    ) {
        PlayerInteractionSnapshot snapshot = context.inputSnapshot();
        if (context.family() == InputFamily.SNEAK
                && snapshot.event() instanceof PlayerToggleSneakEvent event && event.isSneaking()) {
            UUID returnSessionId = service.findReturnGate(snapshot.player());
            if (returnSessionId != null) {
                return List.of(new PlayerInputCandidate(
                        "dungeon-return-gate", InteractionTier.WORLD_INTERACTION, 0.0D,
                        InteractionCandidateOrder.DUNGEON_CONTROLLER, returnSessionId.toString(),
                        InputClaimPolicy.CLAIM,
                        () -> runSafely(() -> service.handleReturnGateSneak(snapshot.player()),
                                LogId.E_7001, snapshot.player().getName(), "return_gate")
                ));
            }
            DungeonService.DungeonEntryHit hit = service.findNearestEntry(snapshot.player());
            if (hit == null) return List.of();
            return List.of(new PlayerInputCandidate(
                    "dungeon-entry", InteractionTier.WORLD_INTERACTION, hit.hitDistance(),
                    InteractionCandidateOrder.DUNGEON_ENTRY, hit.dungeonId(), InputClaimPolicy.CLAIM,
                    () -> runSafely(() -> notifyEntryResult(snapshot.player(), service.requestEntry(snapshot.player(), hit.dungeonId())),
                            LogId.E_7001, snapshot.player().getName(), "entry")
            ));
        }
        if (context.family() == InputFamily.DROP_ITEM) {
            UUID sessionId = service.findNearbyCancelController(snapshot.player());
            if (sessionId == null) return List.of();
            return List.of(new PlayerInputCandidate(
                    "dungeon-cancel-drop", InteractionTier.WORLD_INTERACTION, 0.0D,
                    InteractionCandidateOrder.DUNGEON_CONTROLLER, sessionId.toString(),
                    InputClaimPolicy.CLAIM_AND_CANCEL,
                    () -> runSafely(
                            () -> openCancel(snapshot.player(), sessionId),
                            LogId.E_7001,
                            snapshot.player().getName(),
                            "cancel_drop"
                    )
            ));
        }
        if (context.family() == InputFamily.RIGHT_CLICK && snapshot.isMainHandInput()) {
            DungeonService.DungeonRewardChestTarget target = service.findRewardChestTarget(snapshot.player());
            if (target != null) {
                Double hitDistance = snapshot.hitDistance(target.block());
                if (hitDistance != null && snapshot.isVisible(hitDistance)) {
                    String targetKey = rewardChestTargetKey(target);
                    return List.of(new PlayerInputCandidate(
                            "dungeon-reward-chest",
                            InteractionTier.WORLD_INTERACTION,
                            hitDistance,
                            InteractionCandidateOrder.DUNGEON_CONTROLLER,
                            targetKey,
                            InputClaimPolicy.CLAIM_AND_CANCEL,
                            () -> isCurrentRewardChestTarget(snapshot, targetKey),
                            () -> service.openRewardChest(snapshot.player(), target.block())
                    ));
                }
            }
            DungeonService.CartographTarget cartograph = service.findCartographTarget(snapshot.player());
            if (cartograph == null) {
                return List.of();
            }
            return List.of(new PlayerInputCandidate(
                    "dungeon-cartograph",
                    InteractionTier.ITEM_USE,
                    0.0D,
                    0,
                    cartograph.equipmentInstanceId(),
                    InputClaimPolicy.CLAIM_AND_CANCEL,
                    () -> service.isCurrentCartographTarget(
                            snapshot.player(), cartograph.equipmentInstanceId()),
                    () -> runSafely(
                            () -> service.handleCartographRightClick(snapshot.player()),
                            LogId.E_7001,
                            snapshot.player().getName(),
                            "cartograph"
                    )
            ));
        }
        return List.of();
    }

    /**
     * 報酬 CHEST 候補を現在の視線・所有権・受取対象で再検証します。
     *
     * @param snapshot 入力時の interaction snapshot
     * @param expectedTargetKey 選択時の報酬 CHEST 識別子
     * @return 同じ報酬 CHEST を現在も視認して操作できる場合は {@code true}
     */
    private boolean isCurrentRewardChestTarget(
            @NotNull PlayerInteractionSnapshot snapshot,
            @NotNull String expectedTargetKey
    ) {
        DungeonService.DungeonRewardChestTarget current = service.findRewardChestTarget(snapshot.player());
        if (current == null
                || current.block().getType() != Material.CHEST
                || !rewardChestTargetKey(current).equals(expectedTargetKey)) {
            return false;
        }
        PlayerInteractionSnapshot currentSnapshot = snapshot.refresh();
        Double hitDistance = currentSnapshot.hitDistance(current.block());
        return hitDistance != null && currentSnapshot.isVisible(hitDistance);
    }

    /**
     * 報酬 CHEST のセッションとブロック座標から決定的な候補キーを生成します。
     *
     * @param target 報酬 CHEST 候補
     * @return 同じ報酬 CHEST を一意に識別するキー
     */
    private @NotNull String rewardChestTargetKey(
            @NotNull DungeonService.DungeonRewardChestTarget target
    ) {
        Block block = target.block();
        return target.sessionId() + ":" + block.getWorld().getUID()
                + ":" + block.getX() + ":" + block.getY() + ":" + block.getZ();
    }

    private void notifyEntryResult(@NotNull Player player, @NotNull DungeonService.StartRequestResult result) {
        if (result.status() == DungeonService.StartStatus.ALREADY_IN_PROGRESS) {
            sendChallengeConflict(player, result.activeChallengeName());
        } else if (result.status() == DungeonService.StartStatus.PARTY_SIZE) {
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_7004,
                    result.min(), result.max(), result.current());
        } else if (result.status() == DungeonService.StartStatus.PARTICIPANT_BUSY) {
            sendChallengeConflict(player, result.activeChallengeName());
        } else if (result.status() == DungeonService.StartStatus.HUB_UNAVAILABLE) {
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_7019);
        }
    }

    private void sendChallengeConflict(@NotNull Player player, String activeChallengeName) {
        if (activeChallengeName == null || activeChallengeName.isBlank()) {
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_7024);
            return;
        }
        PlayerMessageService.getInstance().send(player, PlayerMsgId.P_7005, activeChallengeName);
    }

    private void openCancel(@NotNull Player player, @NotNull UUID sessionId) {
        if (!service.isSessionLeader(player.getUniqueId(), sessionId)) {
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_7028);
            GuiSound.DENY.play(player);
            return;
        }
        GuiSound.OPEN.play(player);
        service.cancelGui().open(
                player, sessionId, service.emergencyCooldownRemainingSeconds(sessionId));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(@NotNull InventoryClickEvent event) {
        if (event.isCancelled()) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (service.cancelGui().isInventory(event.getView().getTopInventory())) {
            event.setCancelled(true);
            if (HotbarShortcutClickSupport.handle(event, player, inventoryService)) return;
            UUID sessionId = service.cancelGui().sessionId(event.getView().getTopInventory());
            if (sessionId == null) return;
            if (event.getRawSlot()
                    == io.github.maaasu.astralRecord.feature.dungeon.gui.DungeonCancelGui.EMERGENCY_TELEPORT_SLOT) {
                handleEmergencyTeleportButton(player, sessionId);
                return;
            }
            if (event.getRawSlot() != io.github.maaasu.astralRecord.feature.dungeon.gui.DungeonCancelGui.CANCEL_SLOT) return;
            DungeonService.CancelResult result = service.cancelForLeader(player.getUniqueId(), sessionId);
            switch (result) {
                case CANCELLED -> PlayerMessageService.getInstance().send(player, PlayerMsgId.P_7030);
                case NOT_LEADER -> PlayerMessageService.getInstance().send(player, PlayerMsgId.P_7028);
                case NO_SESSION -> PlayerMessageService.getInstance().send(player, PlayerMsgId.P_7029);
            }
            player.closeInventory();
            return;
        }
        DungeonRewardGui.Holder holder = service.rewardGui().holder(event.getView().getTopInventory());
        if (holder != null) {
            event.setCancelled(true);
            if (HotbarShortcutClickSupport.handle(event, player, inventoryService)) return;
            if (!holder.playerId().equals(player.getUniqueId())) return;
            service.handleRewardClick(
                    player,
                    holder.sessionId(),
                    holder.pageIndex(),
                    event.getRawSlot(),
                    holder.claimIdAt(event.getRawSlot())
            );
            return;
        }
        DungeonMapGui.Holder mapHolder = service.mapGui().holder(event.getView().getTopInventory());
        if (mapHolder != null) {
            event.setCancelled(true);
            if (HotbarShortcutClickSupport.handle(event, player, inventoryService)) return;
            if (!mapHolder.playerId().equals(player.getUniqueId())) return;
            if (event.getRawSlot() == DungeonMapGui.CLOSE_SLOT) {
                player.closeInventory();
            } else if (event.getRawSlot() == DungeonMapGui.PREVIOUS_SLOT) {
                service.openMapPage(player, mapHolder.sessionId(), mapHolder.pageIndex() - 1);
            } else if (event.getRawSlot() == DungeonMapGui.NEXT_SLOT) {
                service.openMapPage(player, mapHolder.sessionId(), mapHolder.pageIndex() + 1);
            } else {
                Integer roomId = mapHolder.roomIdAt(event.getRawSlot());
                if (roomId != null) {
                    if (service.teleportToClearedRoom(player, mapHolder.sessionId(), roomId)) {
                        player.closeInventory();
                    } else {
                        PlayerMessageService.getInstance().send(player, PlayerMsgId.P_7090);
                        GuiSound.DENY.play(player);
                    }
                }
            }
            return;
        }
        io.github.maaasu.astralRecord.feature.dungeon.gui.DungeonEmergencyTeleportGui.Holder emergencyHolder =
                service.emergencyTeleportGui().holder(event.getView().getTopInventory());
        if (emergencyHolder != null) {
            event.setCancelled(true);
            if (HotbarShortcutClickSupport.handle(event, player, inventoryService)) return;
            if (!emergencyHolder.playerId().equals(player.getUniqueId())) return;
            if (event.getRawSlot() == io.github.maaasu.astralRecord.feature.dungeon.gui.DungeonEmergencyTeleportGui.CLOSE_SLOT) {
                player.closeInventory();
                return;
            }
            if (event.getRawSlot() == io.github.maaasu.astralRecord.feature.dungeon.gui.DungeonEmergencyTeleportGui.PREVIOUS_SLOT) {
                service.openEmergencyTeleportSelectionPage(player, emergencyHolder.sessionId(), emergencyHolder.pageIndex() - 1);
                return;
            }
            if (event.getRawSlot() == io.github.maaasu.astralRecord.feature.dungeon.gui.DungeonEmergencyTeleportGui.NEXT_SLOT) {
                service.openEmergencyTeleportSelectionPage(player, emergencyHolder.sessionId(), emergencyHolder.pageIndex() + 1);
                return;
            }
            Integer roomId = emergencyHolder.roomIdAt(event.getRawSlot());
            if (roomId != null) {
                if (service.selectEmergencyTeleportRoom(player, emergencyHolder.sessionId(), roomId)) {
                    player.closeInventory();
                } else {
                    PlayerMessageService.getInstance().send(player, PlayerMsgId.P_7102);
                    GuiSound.DENY.play(player);
                }
            }
            return;
        }
        DungeonArchiveGui.ListHolder listHolder = service.archiveGui()
                .listHolder(event.getView().getTopInventory());
        if (listHolder != null) {
            event.setCancelled(true);
            if (HotbarShortcutClickSupport.handle(event, player, inventoryService)) return;
            if (!listHolder.playerId().equals(player.getUniqueId())) return;
            if (event.getRawSlot() == DungeonArchiveGui.BACK_SLOT) {
                player.closeInventory();
            } else if (event.getRawSlot() == DungeonArchiveGui.PREVIOUS_SLOT) {
                service.openArchiveListPage(player, listHolder.accountId(), listHolder.pageIndex() - 1);
            } else if (event.getRawSlot() == DungeonArchiveGui.NEXT_SLOT) {
                service.openArchiveListPage(player, listHolder.accountId(), listHolder.pageIndex() + 1);
            } else {
                String dungeonId = listHolder.dungeonIdAt(event.getRawSlot());
                if (dungeonId != null) {
                    service.openArchiveDetails(
                            player, listHolder.accountId(), dungeonId, listHolder.pageIndex(), 0);
                }
            }
            return;
        }
        DungeonArchiveGui.DetailHolder detailHolder = service.archiveGui()
                .detailHolder(event.getView().getTopInventory());
        if (detailHolder == null) return;
        event.setCancelled(true);
        if (HotbarShortcutClickSupport.handle(event, player, inventoryService)) return;
        if (!detailHolder.playerId().equals(player.getUniqueId())) return;
        if (event.getRawSlot() == DungeonArchiveGui.BACK_SLOT) {
            service.openArchiveListPage(
                    player, detailHolder.accountId(), detailHolder.listPageIndex());
        } else if (event.getRawSlot() == DungeonArchiveGui.PREVIOUS_SLOT) {
            service.openArchiveDetails(
                    player, detailHolder.accountId(), detailHolder.dungeonId(),
                    detailHolder.listPageIndex(), detailHolder.pageIndex() - 1);
        } else if (event.getRawSlot() == DungeonArchiveGui.NEXT_SLOT) {
            service.openArchiveDetails(
                    player, detailHolder.accountId(), detailHolder.dungeonId(),
                    detailHolder.listPageIndex(), detailHolder.pageIndex() + 1);
        }
    }

    private void handleEmergencyTeleportButton(@NotNull Player player, @NotNull UUID sessionId) {
        DungeonService.EmergencyTeleportResult result = service.handleEmergencyTeleportButton(player, sessionId);
        if (result == DungeonService.EmergencyTeleportResult.TELEPORTING) {
            player.closeInventory();
        } else if (result != DungeonService.EmergencyTeleportResult.SELECTION_OPENED) {
            PlayerMessageService.getInstance().send(player, result
                    == DungeonService.EmergencyTeleportResult.COOLDOWN
                    ? PlayerMsgId.P_7095
                    : PlayerMsgId.P_7102);
            GuiSound.DENY.play(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryDrag(@NotNull InventoryDragEvent event) {
        if (service.cancelGui().isInventory(event.getView().getTopInventory())
                || service.rewardGui().isInventory(event.getView().getTopInventory())
                || service.mapGui().holder(event.getView().getTopInventory()) != null
                || service.emergencyTeleportGui().holder(event.getView().getTopInventory()) != null
                || service.archiveGui().listHolder(event.getView().getTopInventory()) != null
                || service.archiveGui().detailHolder(event.getView().getTopInventory()) != null) {
            event.setCancelled(true);
        }
    }
}
