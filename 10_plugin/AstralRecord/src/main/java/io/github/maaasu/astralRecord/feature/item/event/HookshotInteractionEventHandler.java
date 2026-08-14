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

/** フックショットの右クリック候補と短期牽引の終了イベントを共通gatewayへ接続します。 */
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
        if (context.family() != InputFamily.RIGHT_CLICK || !snapshot.isMainHandInput()) {
            return List.of();
        }
        AstPlayer astPlayer = AstPlayerCache.get(snapshot.player());
        if (!isPlayerMode(astPlayer)) {
            return List.of();
        }
        String equipmentInstanceId = hookshotUseService.findCurrentHookshotInstanceId(astPlayer);
        if (equipmentInstanceId == null) {
            return List.of();
        }
        if (!hookshotUseService.hasValidAnchor(astPlayer)) {
            return List.of();
        }
        Double clickedBlockDistance = snapshot.action() == Action.RIGHT_CLICK_BLOCK
            && snapshot.clickedBlock() != null
            ? snapshot.hitDistance(snapshot.clickedBlock())
            : null;
        return List.of(new PlayerInputCandidate(
            "hookshot-use",
            InteractionTier.WORLD_INTERACTION,
            clickedBlockDistance == null ? snapshot.blockingDistance() : clickedBlockDistance,
            InteractionCandidateOrder.HOOKSHOT,
            equipmentInstanceId,
            InputClaimPolicy.CLAIM_AND_CANCEL,
            () -> hookshotUseService.isCurrentHookshot(astPlayer, equipmentInstanceId)
                && hookshotUseService.hasValidAnchor(astPlayer),
            () -> runSafely(
                () -> hookshotUseService.fire(astPlayer),
                LogId.E_3002,
                "hookshot_use:" + snapshot.player().getName()
            )
        ));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(@NotNull PlayerQuitEvent event) {
        hookshotUseService.cancel(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(@NotNull PlayerDeathEvent event) {
        hookshotUseService.cancel(event.getEntity().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChangedWorld(@NotNull PlayerChangedWorldEvent event) {
        hookshotUseService.cancel(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTeleport(@NotNull PlayerTeleportEvent event) {
        hookshotUseService.cancel(event.getPlayer().getUniqueId());
    }

    private static boolean isPlayerMode(AstPlayer player) {
        return player != null && player.getAccount().getMode() == AccountMode.PLAYER;
    }
}
