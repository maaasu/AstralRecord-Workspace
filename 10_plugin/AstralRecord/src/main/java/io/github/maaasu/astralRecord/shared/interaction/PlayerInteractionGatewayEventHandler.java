package io.github.maaasu.astralRecord.shared.interaction;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.papermc.paper.event.player.PlayerArmSwingEvent;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 競合するプレイヤー入力を family ごとに正規化し、全候補から勝者を一件だけ実行する入口です。
 * Bukkit の cancel は勝者確定後にだけ反映し、feature resolver は候補探索中に副作用を起こしません。
 */
public final class PlayerInteractionGatewayEventHandler extends AbstractEventHandler {
    private static final String ACTION_RING_CANDIDATE_PREFIX = "skill-action-ring";

    private final AstralRecord plugin;
    private final PlayerInputDispatcher<PlayerInteractionSnapshot> dispatcher;
    private final PlayerInputSequenceLedger sequenceLedger = new PlayerInputSequenceLedger();
    private final Predicate<Player> actionRingOpen;
    private final Consumer<Player> actionRingClose;
    private final Map<PlayerInputToken, PendingArmSwing> pendingArmSwings = new HashMap<>();

    /**
     * 共通入力 gateway を生成します。
     *
     * @param plugin プラグイン本体
     * @param resolvers feature ごとの副作用なし候補 resolver
     * @param inputLocked ロード中など入力全体を拒否する状態の判定
     * @param actionRingOpen アクションリングが開いているかの判定
     * @param actionRingClose アクションリングを閉じる処理
     */
    public PlayerInteractionGatewayEventHandler(
        @NotNull AstralRecord plugin,
        @NotNull Collection<? extends PlayerInputResolver<PlayerInteractionSnapshot>> resolvers,
        @NotNull Predicate<Player> inputLocked,
        @NotNull Predicate<Player> actionRingOpen,
        @NotNull Consumer<Player> actionRingClose
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.actionRingOpen = Objects.requireNonNull(actionRingOpen, "actionRingOpen");
        this.actionRingClose = Objects.requireNonNull(actionRingClose, "actionRingClose");
        Objects.requireNonNull(inputLocked, "inputLocked");
        List<PlayerInputResolver<PlayerInteractionSnapshot>> allResolvers = new ArrayList<>();
        allResolvers.add(context -> inputLocked.test(context.inputSnapshot().player())
            ? List.of(new PlayerInputCandidate(
                "player-input-lock",
                InteractionTier.INPUT_LOCK,
                0.0D,
                0,
                context.playerId().toString(),
                InputClaimPolicy.CLAIM_AND_CANCEL,
                () -> {
                }
            ))
            : List.of());
        allResolvers.addAll(resolvers);
        allResolvers.add(new VanillaInteractionResolver());
        this.dispatcher = new PlayerInputDispatcher<>(allResolvers);
    }

    /**
     * PlayerInteractEvent の左右クリックを semantic 入力として調停します。
     *
     * @param event interact イベント
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlayerInteract(@NotNull PlayerInteractEvent event) {
        Action action = event.getAction();
        InputFamily family;
        if (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) {
            family = InputFamily.RIGHT_CLICK;
        } else if (action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK) {
            family = InputFamily.LEFT_CLICK;
        } else {
            return;
        }
        PlayerInteractionSnapshot snapshot = PlayerInteractionSnapshot.create(
            event.getPlayer(),
            event,
            event.getHand(),
            action,
            null,
            event.getClickedBlock(),
            event.getBlockFace(),
            false
        );
        dispatchSemantic(family, InputSource.PLAYER_INTERACT, snapshot);
    }

    /**
     * PlayerInteractEntityEvent を右クリック入力として調停します。
     *
     * @param event entity interact イベント
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlayerInteractEntity(@NotNull PlayerInteractEntityEvent event) {
        PlayerInteractionSnapshot snapshot = PlayerInteractionSnapshot.create(
            event.getPlayer(),
            event,
            event.getHand(),
            null,
            event.getRightClicked(),
            null,
            null,
            false
        );
        dispatchSemantic(InputFamily.RIGHT_CLICK, InputSource.PLAYER_INTERACT_ENTITY, snapshot);
    }

    /**
     * PlayerInteractAtEntityEvent を右クリック入力として調停します。
     *
     * @param event entity position interact イベント
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlayerInteractAtEntity(@NotNull PlayerInteractAtEntityEvent event) {
        PlayerInteractionSnapshot snapshot = PlayerInteractionSnapshot.create(
            event.getPlayer(),
            event,
            event.getHand(),
            null,
            event.getRightClicked(),
            null,
            null,
            false
        );
        dispatchSemantic(InputFamily.RIGHT_CLICK, InputSource.PLAYER_INTERACT_AT_ENTITY, snapshot);
    }

    /**
     * entity 攻撃をダメージ発生前の左クリック入力として調停します。
     *
     * @param event Paper の攻撃事前イベント
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPrePlayerAttackEntity(@NotNull PrePlayerAttackEntityEvent event) {
        PlayerInteractionSnapshot snapshot = PlayerInteractionSnapshot.create(
            event.getPlayer(),
            event,
            EquipmentSlot.HAND,
            null,
            event.getAttacked(),
            null,
            null,
            event.willAttack()
        );
        dispatchSemantic(InputFamily.LEFT_CLICK, InputSource.PRE_PLAYER_ATTACK_ENTITY, snapshot);
    }

    /**
     * semantic イベントを伴わない packet-only 左クリックだけを次 tick の fallback として実行します。
     *
     * @param event Paper の arm swing イベント
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlayerArmSwing(@NotNull PlayerArmSwingEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.isCancelled()) {
            return;
        }
        PlayerInteractionSnapshot snapshot = PlayerInteractionSnapshot.create(
            event.getPlayer(),
            event,
            event.getHand(),
            null,
            null,
            null,
            null,
            false
        );
        int serverTick = plugin.getServer().getCurrentTick();
        PlayerInputToken token = sequenceLedger.correlate(
            snapshot.player().getUniqueId(),
            serverTick,
            InputFamily.LEFT_CLICK,
            InputSource.PLAYER_ARM_SWING,
            event.getHand().name(),
            ""
        );
        if (sequenceLedger.isClaimed(token)) {
            if (sequenceLedger.isCancelRequested(token)) {
                cancel(snapshot.event());
            }
            return;
        }
        PlayerInputContext<PlayerInteractionSnapshot> context = context(
            token,
            InputSource.PLAYER_ARM_SWING,
            snapshot
        );
        try {
            PlayerInputCandidate winner = dispatcher.select(context).orElse(null);
            if (winner == null) {
                return;
            }
            pendingArmSwings.put(token, new PendingArmSwing(context, winner));
            plugin.getServer().getScheduler().runTask(plugin, () -> executePendingArmSwing(token));
        } catch (RuntimeException exception) {
            sequenceLedger.claim(token, true);
            cancel(snapshot.event());
            logFailure(snapshot, InputSource.PLAYER_ARM_SWING, exception);
        }
    }

    /**
     * BlockPlaceEvent を block mutation family として調停します。
     *
     * @param event block place イベント
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBlockPlace(@NotNull BlockPlaceEvent event) {
        PlayerInteractionSnapshot snapshot = PlayerInteractionSnapshot.create(
            event.getPlayer(),
            event,
            event.getHand(),
            null,
            null,
            event.getBlockPlaced(),
            null,
            false
        );
        dispatchSemantic(InputFamily.BLOCK_MUTATION, InputSource.BLOCK_PLACE, snapshot);
    }

    /**
     * BlockBreakEvent を block mutation family として調停します。
     *
     * @param event block break イベント
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBlockBreak(@NotNull BlockBreakEvent event) {
        PlayerInteractionSnapshot snapshot = PlayerInteractionSnapshot.create(
            event.getPlayer(),
            event,
            EquipmentSlot.HAND,
            null,
            null,
            event.getBlock(),
            null,
            false
        );
        dispatchSemantic(InputFamily.BLOCK_MUTATION, InputSource.BLOCK_BREAK, snapshot);
    }

    /**
     * PlayerDropItemEvent を drop item family として調停します。
     *
     * @param event item drop イベント
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlayerDropItem(@NotNull PlayerDropItemEvent event) {
        PlayerInteractionSnapshot snapshot = PlayerInteractionSnapshot.create(
            event.getPlayer(),
            event,
            EquipmentSlot.HAND,
            null,
            event.getItemDrop(),
            null,
            null,
            false
        );
        dispatchSemantic(InputFamily.DROP_ITEM, InputSource.PLAYER_DROP_ITEM, snapshot);
    }

    /**
     * PlayerItemHeldEvent を hotbar slot family として調停します。
     *
     * @param event held slot イベント
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlayerItemHeld(@NotNull PlayerItemHeldEvent event) {
        PlayerInteractionSnapshot snapshot = PlayerInteractionSnapshot.create(
            event.getPlayer(),
            event,
            EquipmentSlot.HAND,
            null,
            null,
            null,
            null,
            false
        );
        dispatchSemantic(InputFamily.HOTBAR_SLOT, InputSource.PLAYER_ITEM_HELD, snapshot);
    }

    /**
     * PlayerToggleSneakEvent を sneak family として調停します。
     *
     * @param event sneak toggle イベント
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlayerToggleSneak(@NotNull PlayerToggleSneakEvent event) {
        PlayerInteractionSnapshot snapshot = PlayerInteractionSnapshot.create(
            event.getPlayer(),
            event,
            EquipmentSlot.HAND,
            null,
            null,
            null,
            null,
            false
        );
        dispatchSemantic(InputFamily.SNEAK, InputSource.PLAYER_TOGGLE_SNEAK, snapshot);
    }

    /**
     * 退出プレイヤーの短命な入力相関状態を破棄します。
     *
     * @param event quit イベント
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(@NotNull PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        sequenceLedger.clear(playerId);
        pendingArmSwings.keySet().removeIf(token -> token.playerId().equals(playerId));
    }

    private void dispatchSemantic(
        InputFamily family,
        InputSource source,
        PlayerInteractionSnapshot snapshot
    ) {
        int serverTick = plugin.getServer().getCurrentTick();
        UUID playerId = snapshot.player().getUniqueId();
        String handKey = snapshot.hand() == null ? "" : snapshot.hand().name();
        PlayerInputToken token = sequenceLedger.correlate(
            playerId,
            serverTick,
            family,
            source,
            handKey,
            snapshot.directTargetKey()
        );
        sequenceLedger.observeSemanticInput(token);
        if (isCancelled(snapshot)) {
            return;
        }
        if (sequenceLedger.isClaimed(token)) {
            if (sequenceLedger.isCancelRequested(token)) {
                cancel(snapshot.event());
            }
            return;
        }
        PlayerInputContext<PlayerInteractionSnapshot> context = context(token, source, snapshot);
        try {
            dispatcher.dispatch(context, winner -> beforeExecution(token, snapshot, winner));
        } catch (RuntimeException exception) {
            sequenceLedger.claim(token, true);
            cancel(snapshot.event());
            logFailure(snapshot, source, exception);
        }
    }

    private PlayerInputContext<PlayerInteractionSnapshot> context(
        PlayerInputToken token,
        InputSource source,
        PlayerInteractionSnapshot snapshot
    ) {
        return new PlayerInputContext<>(
            token.playerId(),
            token.sequence(),
            token.family(),
            source,
            snapshot
        );
    }

    private void beforeExecution(
        PlayerInputToken token,
        PlayerInteractionSnapshot snapshot,
        PlayerInputCandidate winner
    ) {
        if (winner.claimPolicy().isClaimed()) {
            sequenceLedger.claim(token, winner.claimPolicy().isCancelRequested());
        }
        if (winner.claimPolicy().isCancelRequested()) {
            cancel(snapshot.event());
        }
        closeActionRingForHigherPriorityWinner(snapshot.player(), winner);
    }

    private void executePendingArmSwing(PlayerInputToken token) {
        PendingArmSwing pending = pendingArmSwings.remove(token);
        if (pending == null
            || sequenceLedger.isClaimed(token)
            || sequenceLedger.hasSemanticInput(token)) {
            return;
        }
        PlayerInteractionSnapshot snapshot = pending.context.inputSnapshot();
        if (!snapshot.player().isOnline()) {
            return;
        }
        try {
            if (pending.winner.claimPolicy().isClaimed()) {
                sequenceLedger.claim(token, pending.winner.claimPolicy().isCancelRequested());
            }
            closeActionRingForHigherPriorityWinner(snapshot.player(), pending.winner);
            pending.winner.executeIfValid();
        } catch (RuntimeException exception) {
            sequenceLedger.claim(token, true);
            logFailure(snapshot, InputSource.PLAYER_ARM_SWING, exception);
        }
    }

    private void closeActionRingForHigherPriorityWinner(Player player, PlayerInputCandidate winner) {
        if (!winner.id().startsWith(ACTION_RING_CANDIDATE_PREFIX)
            && actionRingOpen.test(player)) {
            actionRingClose.accept(player);
        }
    }

    private void cancel(Event event) {
        if (event instanceof PlayerInteractEvent interactEvent) {
            interactEvent.setUseItemInHand(Event.Result.DENY);
            interactEvent.setUseInteractedBlock(Event.Result.DENY);
        }
        if (event instanceof Cancellable cancellable) {
            cancellable.setCancelled(true);
        }
    }

    /**
     * 先行処理によって入力全体が拒否済みかを判定します。
     * server-side Interaction entity は独自操作の入口であり、初期 cancel 状態でも候補評価を続行します。
     *
     * @param snapshot 入力 snapshot
     * @return 独自候補を評価せず終了すべき場合は true
     */
    private boolean isCancelled(@NotNull PlayerInteractionSnapshot snapshot) {
        Event event = snapshot.event();
        if (snapshot.targetEntity() instanceof Interaction
            && event instanceof PlayerInteractEntityEvent) {
            return false;
        }
        if (event instanceof PlayerInteractEvent interactEvent) {
            /*
             * AIR interact は vanilla の事前予測により block use だけが DENY で生成され、
             * Cancellable#isCancelled() も true を返す。item use まで DENY の場合だけ、
             * 先行ハンドラが入力全体を明示的に拒否したものとして扱う。
             */
            return interactEvent.useInteractedBlock() == Event.Result.DENY
                && interactEvent.useItemInHand() == Event.Result.DENY;
        }
        if (event instanceof PrePlayerAttackEntityEvent attackEvent) {
            /* willAttack=false による初期cancelでも、非攻撃entity向けの独自interact候補は評価する。 */
            return attackEvent.willAttack() && attackEvent.isCancelled();
        }
        return event instanceof Cancellable cancellable && cancellable.isCancelled();
    }

    private void logFailure(
        PlayerInteractionSnapshot snapshot,
        InputSource source,
        RuntimeException exception
    ) {
        Logger.log(
            LogId.E_5999,
            exception,
            snapshot.player().getName(),
            source.name()
        );
    }

    private record PendingArmSwing(
        PlayerInputContext<PlayerInteractionSnapshot> context,
        PlayerInputCandidate winner
    ) {
    }
}
