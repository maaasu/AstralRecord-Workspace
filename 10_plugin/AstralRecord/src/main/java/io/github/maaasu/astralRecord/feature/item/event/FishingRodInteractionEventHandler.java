package io.github.maaasu.astralRecord.feature.item.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.item.service.FishingRodUseService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.event.PlayerRuntimeDiscardEvent;
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
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;

/** キャスト・リール・魚の取り外しを仲裁し、釣り上げ後の攻撃は通常の戦闘入力へ委ねます。 */
public final class FishingRodInteractionEventHandler extends AbstractEventHandler
    implements PlayerInputResolver<PlayerInteractionSnapshot> {

    private final FishingRodUseService useService;

    /**
     * 釣り竿の入力処理を構成します。
     *
     * @param useService 釣り竿の業務サービス
     */
    public FishingRodInteractionEventHandler(@NotNull FishingRodUseService useService) {
        this.useService = useService;
    }

    /**
     * リール中の左右クリックと吊り下げ中の取り外しだけを占有します。
     * 釣り上げ後の左クリック、解除後の左右クリックは通常攻撃・スキルへ委ねます。
     * @param context 共通入力の読取専用スナップショット
     * @return 実行直前にも状態を検証する候補
     */
    @Override
    public @NotNull Collection<PlayerInputCandidate> resolve(
        @NotNull PlayerInputContext<PlayerInteractionSnapshot> context
    ) {
        PlayerInteractionSnapshot snapshot = context.inputSnapshot();
        if (context.family() != InputFamily.RIGHT_CLICK && context.family() != InputFamily.LEFT_CLICK) {
            return List.of();
        }
        AstPlayer astPlayer = AstPlayerCache.get(snapshot.player());
        if (!snapshot.isMainHandInput()) {
            return List.of();
        }
        if (!isPlayerMode(astPlayer)) {
            return List.of();
        }
        if (useService.isFishing(astPlayer)) {
            return useService.reservesFishingInput(astPlayer, context.family() == InputFamily.RIGHT_CLICK)
                ? resolveFishingCandidate(context, astPlayer) : List.of();
        }
        if (context.family() != InputFamily.RIGHT_CLICK) {
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
            "fishing-rod-cast",
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
                "fishing_rod_cast:" + snapshot.player().getName()
            )
        ));
    }

    /** 専有中の釣り入力だけを一度処理します。命中判定・ダメージ計算は通常戦闘側が担当します。 */
    private Collection<PlayerInputCandidate> resolveFishingCandidate(
        PlayerInputContext<PlayerInteractionSnapshot> context, AstPlayer player
    ) {
        return List.of(new PlayerInputCandidate(
            "fishing-session", InteractionTier.EXCLUSIVE_CONTEXT, 0.0D,
            InteractionCandidateOrder.FISHING_ROD, player.getBukkit().getUniqueId().toString(),
            InputClaimPolicy.CLAIM_AND_CANCEL,
            () -> useService.reservesFishingInput(player, context.family() == InputFamily.RIGHT_CLICK),
            () -> runSafely(() -> {
                if (context.family() == InputFamily.RIGHT_CLICK) {
                    useService.fishingRightClick(player);
                } else {
                    useService.fishingLeftClick(player);
                }
            }, LogId.E_3002, "fishing_input:" + player.getBukkit().getName())
        ));
    }

    /**
     * キャスト中の再入力を回収へ接続し、バニラ釣りを抑止します。
     *
     * @param snapshot 入力時の状態
     * @param astPlayer キャスト中のプレイヤー
     * @param equipmentInstanceId 現在の釣り竿
     * @return 同じ釣り竿のキャスト中だけ有効な回収候補
     */
    private @NotNull Collection<PlayerInputCandidate> resolveActiveCastCandidate(
        @NotNull PlayerInteractionSnapshot snapshot,
        @NotNull AstPlayer astPlayer,
        @NotNull String equipmentInstanceId
    ) {
        return List.of(new PlayerInputCandidate(
            "fishing-rod-active",
            InteractionTier.WORLD_INTERACTION,
            candidateDistance(snapshot),
            InteractionCandidateOrder.FISHING_ROD,
            equipmentInstanceId + ":active",
            InputClaimPolicy.CLAIM_AND_CANCEL,
            () -> useService.isCurrentFishingRod(astPlayer, equipmentInstanceId)
                && useService.isCasting(astPlayer),
            () -> runSafely(
                () -> useService.retract(astPlayer),
                LogId.E_3002,
                "fishing_rod_retract:" + snapshot.player().getName()
            )
        ));
    }

    private static double candidateDistance(@NotNull PlayerInteractionSnapshot snapshot) {
        return snapshot.action() == Action.RIGHT_CLICK_BLOCK && snapshot.clickedBlock() != null
            ? snapshot.hitDistance(snapshot.clickedBlock())
            : snapshot.blockingDistance();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(@NotNull PlayerQuitEvent event) {
        clearPlayerRuntime(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRuntimeDiscard(@NotNull PlayerRuntimeDiscardEvent event) {
        clearPlayerRuntime(event.getPlayer());
    }

    private void clearPlayerRuntime(@NotNull Player player) {
        useService.cancel(player.getUniqueId());
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
