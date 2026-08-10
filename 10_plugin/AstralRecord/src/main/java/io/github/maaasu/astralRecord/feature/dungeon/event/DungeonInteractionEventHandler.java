package io.github.maaasu.astralRecord.feature.dungeon.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.dungeon.gui.DungeonRewardGui;
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
import org.bukkit.entity.Player;
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
        return List.of();
    }

    private void notifyEntryResult(@NotNull Player player, @NotNull DungeonService.StartRequestResult result) {
        if (result.status() == DungeonService.StartStatus.ALREADY_IN_PROGRESS) {
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_7024);
        } else if (result.status() == DungeonService.StartStatus.NOT_PARTY_LEADER) {
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_7003);
        } else if (result.status() == DungeonService.StartStatus.PARTY_SIZE) {
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_7004,
                    result.min(), result.max(), result.current());
        } else if (result.status() == DungeonService.StartStatus.PARTICIPANT_BUSY) {
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_7005);
        } else if (result.status() == DungeonService.StartStatus.HUB_UNAVAILABLE) {
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_7019);
        }
    }

    private void openCancel(@NotNull Player player, @NotNull UUID sessionId) {
        if (!service.isSessionLeader(player.getUniqueId(), sessionId)) {
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_7028);
            GuiSound.DENY.play(player);
            return;
        }
        GuiSound.OPEN.play(player);
        service.cancelGui().open(player, sessionId);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(@NotNull InventoryClickEvent event) {
        if (event.isCancelled()) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (service.cancelGui().isInventory(event.getView().getTopInventory())) {
            event.setCancelled(true);
            if (HotbarShortcutClickSupport.handle(event, player, inventoryService)) return;
            if (event.getRawSlot() != io.github.maaasu.astralRecord.feature.dungeon.gui.DungeonCancelGui.CANCEL_SLOT) return;
            UUID sessionId = service.cancelGui().sessionId(event.getView().getTopInventory());
            if (sessionId == null) return;
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
        if (holder == null) return;
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
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryDrag(@NotNull InventoryDragEvent event) {
        if (service.cancelGui().isInventory(event.getView().getTopInventory())
                || service.rewardGui().isInventory(event.getView().getTopInventory())) {
            event.setCancelled(true);
        }
    }
}
