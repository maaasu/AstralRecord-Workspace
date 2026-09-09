package io.github.maaasu.astralRecord.feature.item.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.item.service.DebugFishingRodUseService;
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

/** デバッグ釣り竿の発射とキャスト中の入力抑止を共通入力gatewayへ接続します。 */
public final class DebugFishingRodInteractionEventHandler extends AbstractEventHandler
    implements PlayerInputResolver<PlayerInteractionSnapshot> {

    private final DebugFishingRodUseService useService;

    /**
     * デバッグ釣り竿の入力処理を構成します。
     *
     * @param useService デバッグ釣り竿の業務サービス
     */
    public DebugFishingRodInteractionEventHandler(@NotNull DebugFishingRodUseService useService) {
        this.useService = useService;
    }

    @Override
    public @NotNull Collection<PlayerInputCandidate> resolve(
        @NotNull PlayerInputContext<PlayerInteractionSnapshot> context
    ) {
        PlayerInteractionSnapshot snapshot = context.inputSnapshot();
        if (!snapshot.isMainHandInput() || context.family() != InputFamily.RIGHT_CLICK) {
            return List.of();
        }
        AstPlayer astPlayer = AstPlayerCache.get(snapshot.player());
        if (!isPlayerMode(astPlayer)) {
            return List.of();
        }
        String equipmentInstanceId = useService.findCurrentFishingRodInstanceId(astPlayer);
        if (equipmentInstanceId == null) {
            return List.of();
        }
        if (useService.isCasting(astPlayer)) {
            return resolveActiveCastCandidate(snapshot, astPlayer, equipmentInstanceId);
        }
        if (!useService.canCast(astPlayer)) {
            return List.of();
        }
        return List.of(new PlayerInputCandidate(
            "debug-fishing-rod-cast",
            InteractionTier.WORLD_INTERACTION,
            candidateDistance(snapshot),
            InteractionCandidateOrder.FISHING_ROD,
            equipmentInstanceId,
            InputClaimPolicy.CLAIM_AND_CANCEL,
            () -> useService.isCurrentFishingRod(astPlayer, equipmentInstanceId)
                && useService.canCast(astPlayer),
            () -> runSafely(
                () -> useService.cast(astPlayer),
                LogId.E_3002,
                "debug_fishing_rod_cast:" + snapshot.player().getName()
            )
        ));
    }

    /**
     * キャスト中の再入力を消費し、繰り出した糸の巻き取りとバニラ釣りを防ぎます。
     *
     * @param snapshot 入力時の状態
     * @param astPlayer キャスト中のプレイヤー
     * @param equipmentInstanceId 現在の釣り竿
     * @return 同じ釣り竿のキャスト中だけ有効な入力抑止候補
     */
    private @NotNull Collection<PlayerInputCandidate> resolveActiveCastCandidate(
        @NotNull PlayerInteractionSnapshot snapshot,
        @NotNull AstPlayer astPlayer,
        @NotNull String equipmentInstanceId
    ) {
        return List.of(new PlayerInputCandidate(
            "debug-fishing-rod-active",
            InteractionTier.WORLD_INTERACTION,
            candidateDistance(snapshot),
            InteractionCandidateOrder.FISHING_ROD,
            equipmentInstanceId + ":active",
            InputClaimPolicy.CLAIM_AND_CANCEL,
            () -> useService.isCurrentFishingRod(astPlayer, equipmentInstanceId)
                && useService.isCasting(astPlayer),
            () -> { /* 巻き取りは行わず、入力だけを消費する。 */ }
        ));
    }

    private static double candidateDistance(@NotNull PlayerInteractionSnapshot snapshot) {
        return snapshot.action() == Action.RIGHT_CLICK_BLOCK && snapshot.clickedBlock() != null
            ? snapshot.hitDistance(snapshot.clickedBlock())
            : snapshot.blockingDistance();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(@NotNull PlayerQuitEvent event) {
        useService.cancel(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(@NotNull PlayerDeathEvent event) {
        useService.cancel(event.getEntity().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChangedWorld(@NotNull PlayerChangedWorldEvent event) {
        useService.cancel(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTeleport(@NotNull PlayerTeleportEvent event) {
        useService.cancel(event.getPlayer().getUniqueId());
    }

    private static boolean isPlayerMode(AstPlayer player) {
        return player != null && player.getAccount().getMode() == AccountMode.PLAYER;
    }
}
