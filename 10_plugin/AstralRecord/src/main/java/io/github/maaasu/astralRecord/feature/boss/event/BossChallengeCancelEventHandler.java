package io.github.maaasu.astralRecord.feature.boss.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.boss.gui.BossChallengeCancelGui;
import io.github.maaasu.astralRecord.feature.boss.service.BossChallengeService;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.shared.interaction.InputClaimPolicy;
import io.github.maaasu.astralRecord.shared.interaction.InputFamily;
import io.github.maaasu.astralRecord.shared.interaction.InputSource;
import io.github.maaasu.astralRecord.shared.interaction.InteractionCandidateOrder;
import io.github.maaasu.astralRecord.shared.interaction.InteractionTier;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInputCandidate;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInputContext;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInputResolver;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInteractionSnapshot;
import io.github.maaasu.astralRecord.shared.gui.sound.GuiSound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * ボス挑戦中止装置のインタラクト、ドロップ操作、GUI 操作を処理します。
 */
public final class BossChallengeCancelEventHandler extends AbstractEventHandler
    implements PlayerInputResolver<PlayerInteractionSnapshot> {
    private final BossChallengeService service;
    private final BossChallengeCancelGui gui;

    public BossChallengeCancelEventHandler(
            @NotNull BossChallengeService service,
            @NotNull BossChallengeCancelGui gui
    ) {
        this.service = service;
        this.gui = gui;
    }

    @Override
    public @NotNull Collection<PlayerInputCandidate> resolve(
        @NotNull PlayerInputContext<PlayerInteractionSnapshot> context
    ) {
        PlayerInteractionSnapshot snapshot = context.inputSnapshot();
        if (context.family() == InputFamily.DROP_ITEM) {
            UUID challengeId = service.findNearbyCancelController(snapshot.player());
            if (challengeId == null) {
                return List.of();
            }
            return List.of(new PlayerInputCandidate(
                "boss-cancel-drop",
                InteractionTier.WORLD_INTERACTION,
                0.0D,
                InteractionCandidateOrder.BOSS_CONTROLLER,
                challengeId.toString(),
                InputClaimPolicy.CLAIM_AND_CANCEL,
                () -> runSafely(
                    () -> openForLeader(snapshot.player(), challengeId),
                    LogId.E_6501,
                    snapshot.player().getName()
                )
            ));
        }
        if (context.family() != InputFamily.RIGHT_CLICK
            || (context.source() != InputSource.PLAYER_INTERACT_ENTITY
            && context.source() != InputSource.PLAYER_INTERACT_AT_ENTITY)
            || !snapshot.isMainHandInput()
            || !snapshot.player().isSneaking()
            || snapshot.targetEntity() == null) {
            return List.of();
        }
        UUID challengeId = service.resolveCancelInteraction(snapshot.targetEntity());
        Double hitDistance = snapshot.hitDistance(snapshot.targetEntity());
        if (challengeId == null || hitDistance == null || !snapshot.isVisible(hitDistance)) {
            return List.of();
        }
        return List.of(new PlayerInputCandidate(
            "boss-cancel-controller",
            InteractionTier.WORLD_INTERACTION,
            hitDistance,
            InteractionCandidateOrder.BOSS_CONTROLLER,
            challengeId.toString(),
            InputClaimPolicy.CLAIM_AND_CANCEL,
            () -> runSafely(
                () -> openForLeader(snapshot.player(), challengeId),
                LogId.E_6501,
                snapshot.player().getName()
            )
        ));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryClick(@NotNull InventoryClickEvent event) {
        if (!gui.isInventory(event.getView().getTopInventory())) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (event.getRawSlot() != BossChallengeCancelGui.CANCEL_SLOT) {
            GuiSound.DENY.play(player);
            return;
        }
        UUID challengeId = gui.getChallengeId(event.getView().getTopInventory());
        if (challengeId == null) {
            GuiSound.DENY.play(player);
            return;
        }
        BossChallengeService.PlayerCancelResult result = service.stopChallengeForLeader(
                player.getUniqueId(),
                challengeId
        );
        notifyResult(player, result, challengeId);
        player.closeInventory();
    }

    private void openForLeader(@NotNull Player player, @NotNull UUID challengeId) {
        if (!service.isChallengeLeader(player.getUniqueId(), challengeId)) {
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_6526);
            GuiSound.DENY.play(player);
            return;
        }
        GuiSound.OPEN.play(player);
        gui.open(player, challengeId);
    }

    private void notifyResult(
            @NotNull Player player,
            @NotNull BossChallengeService.PlayerCancelResult result,
            @NotNull UUID challengeId
    ) {
        switch (result) {
            case STOPPED -> {
                PlayerMessageService.getInstance().send(player, PlayerMsgId.P_6528);
                GuiSound.SUCCESS.play(player);
            }
            case NOT_LEADER -> {
                PlayerMessageService.getInstance().send(player, PlayerMsgId.P_6526);
                GuiSound.DENY.play(player);
            }
            case NO_CHALLENGE -> {
                PlayerMessageService.getInstance().send(player, PlayerMsgId.P_6527);
                GuiSound.DENY.play(player);
            }
        }
    }
}
