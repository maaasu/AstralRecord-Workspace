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
import io.github.maaasu.astralRecord.shared.interaction.InputSource;
import io.github.maaasu.astralRecord.shared.interaction.InteractionCandidateOrder;
import io.github.maaasu.astralRecord.shared.interaction.InteractionTier;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInputCandidate;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInputContext;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInputResolver;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInteractionSnapshot;
import io.papermc.paper.event.player.PlayerArmSwingEvent;
import io.papermc.paper.event.player.PlayerPickItemEvent;
import org.bukkit.event.block.Action;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;

/** キャスト・リール・魚の取り外しを仲裁し、魚を外すまで吊り下げ中の攻撃と持ち替えを抑止します。 */
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
     * リール中の左右クリックを占有します。吊り下げ中の右クリックで魚を外すまで、
     * 攻撃・アイテム使用・ホットバー変更・ドロップは通常の候補へ渡しません。
     * @param context 共通入力の読取専用スナップショット
     * @return 実行直前にも状態を検証する候補
     */
    @Override
    public @NotNull Collection<PlayerInputCandidate> resolve(
        @NotNull PlayerInputContext<PlayerInteractionSnapshot> context
    ) {
        PlayerInteractionSnapshot snapshot = context.inputSnapshot();
        if (useService.isHoldingLandedFish(snapshot.player())) {
            return resolveHangingCandidate(context);
        }
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
            return useService.reservesFishingInput(astPlayer)
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

    /**
     * 吊り下げ状態に対する入力を占有し、右クリックだけ魚を外します。
     * @param context 共通入力の読取専用スナップショット
     * @return 対象操作を取り消す候補。通常どおり許可する操作では空
     */
    private @NotNull Collection<PlayerInputCandidate> resolveHangingCandidate(
        @NotNull PlayerInputContext<PlayerInteractionSnapshot> context
    ) {
        boolean locked = switch (context.family()) {
            case RIGHT_CLICK, LEFT_CLICK, HOTBAR_SLOT, DROP_ITEM -> true;
            case BLOCK_MUTATION -> context.source() == InputSource.BLOCK_BREAK;
            case SNEAK -> false;
        };
        if (!locked) {
            return List.of();
        }
        PlayerInteractionSnapshot snapshot = context.inputSnapshot();
        boolean release = context.family() == InputFamily.RIGHT_CLICK;
        return List.of(new PlayerInputCandidate(
            "fishing-hanging", InteractionTier.INPUT_LOCK, 0.0D,
            InteractionCandidateOrder.FISHING_HANGING_LOCK, context.playerId().toString(),
            release ? InputClaimPolicy.CLAIM_AND_CANCEL : InputClaimPolicy.CANCEL_ONLY,
            () -> useService.isHoldingLandedFish(snapshot.player()),
            () -> {
                if (release) {
                    AstPlayer astPlayer = AstPlayerCache.get(snapshot.player());
                    if (astPlayer != null) {
                        runSafely(() -> useService.fishingRightClick(astPlayer),
                            LogId.E_3002, "fishing_input:" + snapshot.player().getName());
                    }
                }
            }
        ));
    }

    /** 専有中の釣り入力だけを一度処理します。 */
    private Collection<PlayerInputCandidate> resolveFishingCandidate(
        PlayerInputContext<PlayerInteractionSnapshot> context, AstPlayer player
    ) {
        return List.of(new PlayerInputCandidate(
            "fishing-session", InteractionTier.EXCLUSIVE_CONTEXT, 0.0D,
            InteractionCandidateOrder.FISHING_ROD, player.getBukkit().getUniqueId().toString(),
            InputClaimPolicy.CLAIM_AND_CANCEL,
            () -> useService.reservesFishingInput(player),
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

    /**
     * 吊り下げ中の腕振りを、次 tick の左クリック処理へ予約させず取り消します。
     * @param event Paper の腕振りイベント
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onArmSwing(@NotNull PlayerArmSwingEvent event) {
        if (useService.isHoldingLandedFish(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /**
     * 攻撃事前イベントを経由せず、プレイヤー自身が発生させた直接ダメージを拒否します。
     * @param event エンティティ間のダメージ。既に飛んでいる飛び道具などは対象外
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDirectPlayerDamage(@NotNull EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player && useService.isHoldingLandedFish(player)) {
            event.setCancelled(true);
        }
    }

    /**
     * ホットバー選択イベントを経由しないブロック・エンティティ選択による持ち替えを拒否します。
     * @param event Paper の持ち物選択イベント
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPickItem(@NotNull PlayerPickItemEvent event) {
        if (useService.isHoldingLandedFish(event.getPlayer())) {
            event.setCancelled(true);
        }
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
