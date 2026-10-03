package io.github.maaasu.astralRecord.feature.item.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.fishing.model.FishingEntityProtection;
import io.github.maaasu.astralRecord.feature.item.service.DebugFishingRodUseService;
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
import org.bukkit.entity.Entity;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;

/** 釣り竿のキャスト、リール、外した魚への攻撃を共通入力gatewayへ接続します。 */
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

    /**
     * 釣り中の左右クリックを排他的に解決し、バニラ操作や通常スキルとの二重実行を防ぎます。
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
        // 所有者以外のクリックやoffhandからも、魚をバケツ等で取得させない。
        boolean targetFish = snapshot.targetEntity() != null
            && FishingEntityProtection.isManaged(snapshot.targetEntity());
        AstPlayer astPlayer = AstPlayerCache.get(snapshot.player());
        if (targetFish && (!snapshot.isMainHandInput() || !isPlayerMode(astPlayer)
            || !useService.isFishing(astPlayer))) {
            return List.of(new PlayerInputCandidate(
                "fishing-protected-entity", InteractionTier.EXCLUSIVE_CONTEXT, 0.0D,
                InteractionCandidateOrder.FISHING_ROD, snapshot.directTargetKey(),
                InputClaimPolicy.CLAIM_AND_CANCEL, () -> { }
            ));
        }
        if (!snapshot.isMainHandInput()) {
            return List.of();
        }
        if (!isPlayerMode(astPlayer)) {
            return List.of();
        }
        if (useService.isFishing(astPlayer)) {
            return resolveFishingCandidate(context, astPlayer);
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

    /** 釣りの入力を一度だけ処理し、解放した魚への攻撃は実体への照準と遮蔽を再検査します。 */
    private Collection<PlayerInputCandidate> resolveFishingCandidate(
        PlayerInputContext<PlayerInteractionSnapshot> context, AstPlayer player
    ) {
        return List.of(new PlayerInputCandidate(
            "fishing-session", InteractionTier.EXCLUSIVE_CONTEXT, 0.0D,
            InteractionCandidateOrder.FISHING_ROD, player.getBukkit().getUniqueId().toString(),
            InputClaimPolicy.CLAIM_AND_CANCEL, () -> useService.isFishing(player),
            () -> runSafely(() -> {
                if (context.family() == InputFamily.RIGHT_CLICK) {
                    useService.fishingRightClick(player);
                } else {
                    useService.fishingLeftClick(player, validatedHit(context.inputSnapshot().refresh()));
                }
            }, LogId.E_3002, "fishing_input:" + player.getBukkit().getName())
        ));
    }

    /** 直接クリックされた魚の当たり判定が現在の攻撃射程内かつ遮蔽物の手前なら返します。 */
    private Entity validatedHit(PlayerInteractionSnapshot snapshot) {
        Entity target = snapshot.targetEntity();
        if (target == null || !target.isValid() || !FishingEntityProtection.isManaged(target)
            || !target.getWorld().equals(snapshot.player().getWorld())) {
            return null;
        }
        Double distance = snapshot.hitDistance(target, 0.10D);
        AttributeInstance attribute = snapshot.player().getAttribute(Attribute.ENTITY_INTERACTION_RANGE);
        double reach = attribute == null ? 3.0D : attribute.getValue();
        return distance != null && distance <= reach && snapshot.isVisible(distance) ? target : null;
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
            "debug-fishing-rod-active",
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
                "debug_fishing_rod_retract:" + snapshot.player().getName()
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
