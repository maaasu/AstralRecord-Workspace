package io.github.maaasu.astralRecord.feature.item.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.item.service.HookshotUseService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.shared.interaction.InputClaimPolicy;
import io.github.maaasu.astralRecord.shared.interaction.InputFamily;
import io.github.maaasu.astralRecord.shared.interaction.InteractionCandidateOrder;
import io.github.maaasu.astralRecord.shared.interaction.InteractionTier;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInputCandidate;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInputContext;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInputResolver;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInteractionSnapshot;
import org.bukkit.event.block.Action;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;

/** フックショットのクリック装填・自動発射候補と短期処理の終了イベントを共通gatewayへ接続します。 */
public final class HookshotInteractionEventHandler extends AbstractEventHandler
    implements PlayerInputResolver<PlayerInteractionSnapshot> {

    private final HookshotUseService hookshotUseService;

    /**
     * フックショットの入力・終了処理を構成します。
     *
     * @param hookshotUseService フックショット業務サービス
     */
    public HookshotInteractionEventHandler(@NotNull HookshotUseService hookshotUseService) {
        this.hookshotUseService = hookshotUseService;
    }

    @Override
    public @NotNull Collection<PlayerInputCandidate> resolve(
        @NotNull PlayerInputContext<PlayerInteractionSnapshot> context
    ) {
        PlayerInteractionSnapshot snapshot = context.inputSnapshot();
        if (!snapshot.isMainHandInput()) {
            return List.of();
        }
        AstPlayer astPlayer = AstPlayerCache.get(snapshot.player());
        if (!isPlayerMode(astPlayer)) {
            return List.of();
        }
        if (context.family() != InputFamily.RIGHT_CLICK && context.family() != InputFamily.LEFT_CLICK) {
            return List.of();
        }
        String equipmentInstanceId = hookshotUseService.findCurrentHookshotInstanceId(astPlayer);
        if (equipmentInstanceId == null) {
            return List.of();
        }

        if (context.family() == InputFamily.RIGHT_CLICK) {
            return resolveLoadingCandidate(
                snapshot,
                astPlayer,
                equipmentInstanceId,
                Action.RIGHT_CLICK_BLOCK,
                false
            );
        }
        if (hookshotUseService.isCurrentHookshotLoaded(astPlayer, equipmentInstanceId)) {
            return resolveFireCandidate(snapshot, astPlayer, equipmentInstanceId);
        }
        return resolveLoadingCandidate(
            snapshot,
            astPlayer,
            equipmentInstanceId,
            Action.LEFT_CLICK_BLOCK,
            true
        );
    }

    private @NotNull Collection<PlayerInputCandidate> resolveLoadingCandidate(
        @NotNull PlayerInteractionSnapshot snapshot,
        @NotNull AstPlayer astPlayer,
        @NotNull String equipmentInstanceId,
        @NotNull Action blockAction,
        boolean fireOnCompletion
    ) {
        if (!hookshotUseService.canStartLoading(astPlayer)) {
            return List.of();
        }
        return List.of(new PlayerInputCandidate(
            "hookshot-load",
            InteractionTier.WORLD_INTERACTION,
            candidateDistance(snapshot, blockAction),
            InteractionCandidateOrder.HOOKSHOT,
            equipmentInstanceId,
            InputClaimPolicy.CLAIM_AND_CANCEL,
            () -> hookshotUseService.isCurrentHookshot(astPlayer, equipmentInstanceId)
                && hookshotUseService.canStartLoading(astPlayer),
            () -> runSafely(
                () -> hookshotUseService.startLoading(astPlayer, fireOnCompletion),
                LogId.E_3002,
                "hookshot_load:" + snapshot.player().getName()
            )
        ));
    }

    private @NotNull Collection<PlayerInputCandidate> resolveFireCandidate(
        @NotNull PlayerInteractionSnapshot snapshot,
        @NotNull AstPlayer astPlayer,
        @NotNull String equipmentInstanceId
    ) {
        if (!hookshotUseService.canFire(astPlayer, equipmentInstanceId)) {
            return List.of();
        }
        return List.of(new PlayerInputCandidate(
            "hookshot-fire",
            InteractionTier.WORLD_INTERACTION,
            candidateDistance(snapshot, Action.LEFT_CLICK_BLOCK),
            InteractionCandidateOrder.HOOKSHOT,
            equipmentInstanceId,
            InputClaimPolicy.CLAIM_AND_CANCEL,
            () -> hookshotUseService.canFire(astPlayer, equipmentInstanceId),
            () -> runSafely(
                () -> hookshotUseService.fire(astPlayer),
                LogId.E_3002,
                "hookshot_fire:" + snapshot.player().getName()
            )
        ));
    }

    private static double candidateDistance(
        @NotNull PlayerInteractionSnapshot snapshot,
        @NotNull Action blockAction
    ) {
        Double clickedBlockDistance = snapshot.action() == blockAction && snapshot.clickedBlock() != null
            ? snapshot.hitDistance(snapshot.clickedBlock())
            : null;
        return clickedBlockDistance == null ? snapshot.blockingDistance() : clickedBlockDistance;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(@NotNull PlayerQuitEvent event) {
        hookshotUseService.cancel(event.getPlayer().getUniqueId());
        hookshotUseService.cancelLoading(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(@NotNull PlayerDeathEvent event) {
        hookshotUseService.cancel(event.getEntity().getUniqueId());
        hookshotUseService.cancelLoading(event.getEntity().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChangedWorld(@NotNull PlayerChangedWorldEvent event) {
        hookshotUseService.cancel(event.getPlayer().getUniqueId());
        hookshotUseService.cancelLoading(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTeleport(@NotNull PlayerTeleportEvent event) {
        hookshotUseService.cancel(event.getPlayer().getUniqueId());
        hookshotUseService.cancelLoading(event.getPlayer().getUniqueId());
    }

    private static boolean isPlayerMode(AstPlayer player) {
        return player != null && player.getAccount().getMode() == AccountMode.PLAYER;
    }
}
