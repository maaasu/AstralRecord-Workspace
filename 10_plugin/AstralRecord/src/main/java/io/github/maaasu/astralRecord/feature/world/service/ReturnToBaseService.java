package io.github.maaasu.astralRecord.feature.world.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.world.model.WorldMasterData;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import io.github.maaasu.astralRecord.shared.timing.MovementCancelableWait;
import io.github.maaasu.astralRecord.shared.timing.MovementCancelableWaitCallbacks;
import io.github.maaasu.astralRecord.shared.timing.MovementCancelableWaitCancelReason;
import io.github.maaasu.astralRecord.shared.timing.MovementCancelableWaitService;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 参加時スポーン地点を拠点として扱い、一定時間静止後の帰還処理を提供します。
 */
public final class ReturnToBaseService {
    private static final long BASE_GOLD_COST = 100L;
    private static final long RETURN_DELAY_TICKS = 60L;
    private static final long EFFECT_PERIOD_TICKS = 5L;
    private static final int RING_POINTS = 10;
    private static final Title.Times COUNTDOWN_TITLE_TIMES =
        Title.Times.times(Duration.ZERO, Duration.ofMillis(1100L), Duration.ofMillis(150L));
    private static final Title.Times RESULT_TITLE_TIMES =
        Title.Times.times(Duration.ZERO, Duration.ofSeconds(2L), Duration.ofMillis(250L));

    private final AstralRecord plugin;
    private final MovementCancelableWaitService movementCancelableWaitService;
    private final WorldService worldService;
    private final InventoryService inventoryService;
    private final ParticleDisplayService particleDisplayService;
    private final String joinSpawnWorldId;
    private final Map<UUID, PendingReturn> pendingReturns = new ConcurrentHashMap<>();

    /**
     * サービスを初期化します。
     *
     * @param plugin プラグイン本体
     * @param movementCancelableWaitService 移動キャンセル付き待機サービス
     * @param worldService ワールドサービス
     * @param inventoryService インベントリサービス
     * @param particleDisplayService パーティクル表示サービス
     * @param joinSpawnWorldId 参加時スポーン先の WorldMasterData ID
     */
    public ReturnToBaseService(
        @NotNull AstralRecord plugin,
        @NotNull MovementCancelableWaitService movementCancelableWaitService,
        @NotNull WorldService worldService,
        @NotNull InventoryService inventoryService,
        @NotNull ParticleDisplayService particleDisplayService,
        @NotNull String joinSpawnWorldId
    ) {
        this.plugin = plugin;
        this.movementCancelableWaitService = movementCancelableWaitService;
        this.worldService = worldService;
        this.inventoryService = inventoryService;
        this.particleDisplayService = particleDisplayService;
        this.joinSpawnWorldId = joinSpawnWorldId;
    }

    /**
     * プレイヤーレベルに応じた帰還ゴールドコストを返します。
     *
     * @param playerLevel プレイヤーレベル
     * @return 帰還に必要なゴールド量
     */
    public static long calculateGoldCost(int playerLevel) {
        return BASE_GOLD_COST * Math.max(1, playerLevel);
    }

    /**
     * 拠点帰還のカウントダウンを開始します。
     *
     * @param astPlayer 対象プレイヤー
     * @return 開始できた場合は {@code true}
     */
    public boolean beginReturn(@NotNull AstPlayer astPlayer) {
        Player player = astPlayer.getBukkit();
        if (!player.isOnline()) {
            return false;
        }

        WorldMasterData baseWorld = resolveBaseWorld();
        if (baseWorld == null) {
            PlayerMessageService.getInstance().send(astPlayer, PlayerMsgId.P_5611);
            showResultTitle(player, PlayerMsgId.P_5615, PlayerMsgId.P_5611);
            return false;
        }

        cancelPending(player.getUniqueId(), false);

        long goldCost = calculateGoldCost(astPlayer.getAccount().getLevel());
        long currentGold = inventoryService.getCurrencyAmount(astPlayer.getAccount().getUuid(), ItemService.DEFAULT_CURRENCY_ITEM_ID)
            + inventoryService.getCurrencyAmount(astPlayer.getAccount().getUuid(), ItemService.LEGACY_DEFAULT_CURRENCY_ITEM_ID);
        if (currentGold < goldCost) {
            PlayerMessageService.getInstance().send(astPlayer, PlayerMsgId.P_5609, goldCost);
            showResultTitle(player, PlayerMsgId.P_5615, PlayerMsgId.P_5609, goldCost);
            return false;
        }

        BossBar bossBar = Bukkit.createBossBar(
            PlayerMsgResource.format(PlayerMsgId.P_5614.getId(), secondsRemaining(0L)),
            BarColor.BLUE,
            BarStyle.SOLID
        );
        bossBar.setVisible(true);
        bossBar.setProgress(0.0D);
        bossBar.addPlayer(player);

        PendingReturn pending = new PendingReturn(astPlayer, baseWorld, goldCost, bossBar);
        updateCountdownDisplay(pending, secondsRemaining(0L));
        playStartEffects(player);
        PlayerMessageService.getInstance().send(astPlayer, PlayerMsgId.P_5607, goldCost);

        pendingReturns.put(player.getUniqueId(), pending);
        pending.setWait(movementCancelableWaitService.begin(
            player,
            RETURN_DELAY_TICKS,
            new MovementCancelableWaitCallbacks() {
                @Override
                public void onTick(long elapsedTicks, double progress) {
                    tickPendingReturn(player.getUniqueId(), pending, elapsedTicks, progress);
                }

                @Override
                public void onComplete() {
                    completeReturn(player.getUniqueId(), pending);
                }

                @Override
                public void onCancel(@NotNull MovementCancelableWaitCancelReason reason) {
                    cancelPending(player.getUniqueId(), pending, reason);
                }
            }
        ));
        return true;
    }

    /**
     * ゴールド消費と待機なしで拠点へ即時帰還を開始します。
     *
     * @param astPlayer 帰還対象プレイヤー
     * @return 帰還開始条件を満たした場合は {@code true}
     */
    public boolean beginImmediateReturn(@NotNull AstPlayer astPlayer) {
        Player player = astPlayer.getBukkit();
        if (!player.isOnline()) {
            return false;
        }

        WorldMasterData baseWorld = resolveBaseWorld();
        if (baseWorld == null) {
            PlayerMessageService.getInstance().send(astPlayer, PlayerMsgId.P_5611);
            showResultTitle(player, PlayerMsgId.P_5615, PlayerMsgId.P_5611);
            return false;
        }

        cancelPending(player.getUniqueId(), false);
        player.playSound(player.getLocation(), Sound.ITEM_CHORUS_FRUIT_TELEPORT, SoundCategory.PLAYERS, 0.9F, 1.05F);
        worldService.teleportToSpawnAsync(player, baseWorld).whenComplete((success, throwable) ->
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) {
                    return;
                }
                if (throwable != null || !Boolean.TRUE.equals(success)) {
                    PlayerMessageService.getInstance().send(astPlayer, PlayerMsgId.P_5611);
                    showResultTitle(player, PlayerMsgId.P_5615, PlayerMsgId.P_5611);
                    player.playSound(player.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, SoundCategory.PLAYERS, 0.8F, 0.85F);
                    return;
                }

                PlayerMessageService.getInstance().send(astPlayer, PlayerMsgId.P_5619);
                showResultTitle(player, PlayerMsgId.P_5617, PlayerMsgId.P_5618);
                playArrivalEffects(player);
            })
        );
        return true;
    }

    /**
     * 保持中の全帰還処理を停止します。
     */
    public void cancelAll() {
        for (UUID playerId : pendingReturns.keySet()) {
            cancelPending(playerId, false);
        }
    }

    private void tickPendingReturn(
        @NotNull UUID playerId,
        @NotNull PendingReturn pending,
        long elapsedTicks,
        double progress
    ) {
        if (pendingReturns.get(playerId) != pending) {
            return;
        }

        Player player = pending.astPlayer().getBukkit();
        if (!player.isOnline()) {
            cancelPending(playerId, false);
            return;
        }

        pending.bossBar().setProgress(progress);

        int remainingSeconds = secondsRemaining(elapsedTicks);
        if (remainingSeconds != pending.lastDisplayedSeconds()) {
            updateCountdownDisplay(pending, remainingSeconds);
        }
        if (elapsedTicks % EFFECT_PERIOD_TICKS == 0L) {
            playChargeEffects(player, elapsedTicks);
        }
    }

    private void cancelPending(
        @NotNull UUID playerId,
        @NotNull PendingReturn pending,
        @NotNull MovementCancelableWaitCancelReason reason
    ) {
        if (!pendingReturns.remove(playerId, pending)) {
            return;
        }

        Player player = pending.astPlayer().getBukkit();
        cleanupPending(pending);
        if (reason == MovementCancelableWaitCancelReason.MOVED && player.isOnline()) {
            PlayerMessageService.getInstance().send(pending.astPlayer(), PlayerMsgId.P_5608);
            showResultTitle(player, PlayerMsgId.P_5615, PlayerMsgId.P_5616);
            player.playSound(player.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, SoundCategory.PLAYERS, 0.8F, 0.85F);
        }
    }

    private void completeReturn(@NotNull UUID playerId, @NotNull PendingReturn pending) {
        if (!pendingReturns.remove(playerId, pending)) {
            return;
        }

        Player player = pending.astPlayer().getBukkit();
        cleanupPending(pending);
        if (!player.isOnline()) {
            return;
        }

        if (!inventoryService.consumeGold(pending.astPlayer().getAccount().getUuid(), pending.goldCost())) {
            PlayerMessageService.getInstance().send(pending.astPlayer(), PlayerMsgId.P_5609, pending.goldCost());
            showResultTitle(player, PlayerMsgId.P_5615, PlayerMsgId.P_5609, pending.goldCost());
            return;
        }
        inventoryService.saveNow(pending.astPlayer().getAccount().getUuid());

        player.playSound(player.getLocation(), Sound.ITEM_CHORUS_FRUIT_TELEPORT, SoundCategory.PLAYERS, 0.9F, 1.05F);
        worldService.teleportToSpawnAsync(player, pending.baseWorld()).whenComplete((success, throwable) ->
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) {
                    return;
                }
                if (throwable != null || !Boolean.TRUE.equals(success)) {
                    inventoryService.addGold(pending.astPlayer(), pending.goldCost());
                    inventoryService.saveNow(pending.astPlayer().getAccount().getUuid());
                    PlayerMessageService.getInstance().send(pending.astPlayer(), PlayerMsgId.P_5611);
                    showResultTitle(player, PlayerMsgId.P_5615, PlayerMsgId.P_5611);
                    player.playSound(player.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, SoundCategory.PLAYERS, 0.8F, 0.85F);
                    return;
                }

                PlayerMessageService.getInstance().send(pending.astPlayer(), PlayerMsgId.P_5610, pending.goldCost());
                showResultTitle(player, PlayerMsgId.P_5617, PlayerMsgId.P_5618);
                playArrivalEffects(player);
            })
        );
    }

    private boolean cancelPending(@NotNull UUID playerId, boolean notify) {
        PendingReturn pending = pendingReturns.get(playerId);
        if (pending == null) {
            return false;
        }
        MovementCancelableWaitCancelReason reason = notify
            ? MovementCancelableWaitCancelReason.MOVED
            : MovementCancelableWaitCancelReason.MANUAL;
        if (pending.waitHandle() != null) {
            return pending.waitHandle().cancel(reason);
        }

        cancelPending(playerId, pending, reason);
        return true;
    }

    private void cleanupPending(@NotNull PendingReturn pending) {
        pending.astPlayer().getBukkit().resetTitle();
        pending.bossBar().removeAll();
        pending.bossBar().setVisible(false);
    }

    private @Nullable WorldMasterData resolveBaseWorld() {
        WorldMasterData baseWorld = worldService.getById(joinSpawnWorldId);
        Location spawnLocation = baseWorld == null ? null : worldService.resolveSpawnLocation(baseWorld);
        if (baseWorld == null || spawnLocation == null || spawnLocation.getWorld() == null) {
            return null;
        }
        return baseWorld;
    }

    private void updateCountdownDisplay(@NotNull PendingReturn pending, int remainingSeconds) {
        pending.setLastDisplayedSeconds(remainingSeconds);
        Player player = pending.astPlayer().getBukkit();
        player.showTitle(Title.title(
            PlayerMsgResource.getComponent(PlayerMsgId.P_5612.getId()),
            PlayerMsgResource.formatComponent(PlayerMsgId.P_5613.getId(), remainingSeconds),
            COUNTDOWN_TITLE_TIMES
        ));
        pending.bossBar().setTitle(PlayerMsgResource.format(PlayerMsgId.P_5614.getId(), remainingSeconds));
    }

    private void showResultTitle(
        @NotNull Player player,
        @NotNull PlayerMsgId titleId,
        @NotNull PlayerMsgId subtitleId,
        Object... args
    ) {
        player.showTitle(Title.title(
            PlayerMsgResource.getComponent(titleId.getId()),
            PlayerMsgResource.formatComponent(subtitleId.getId(), args),
            RESULT_TITLE_TIMES
        ));
    }

    private void playStartEffects(@NotNull Player player) {
        player.playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, SoundCategory.PLAYERS, 0.75F, 1.15F);
        playChargeEffects(player, 0L);
    }

    private void playChargeEffects(@NotNull Player player, long elapsedTicks) {
        Location base = player.getLocation().clone();
        double radius = 0.8D + (Math.sin(elapsedTicks * 0.18D) * 0.08D);
        for (int index = 0; index < RING_POINTS; index++) {
            double angle = (elapsedTicks * 0.2D) + ((Math.PI * 2.0D * index) / RING_POINTS);
            double x = Math.cos(angle) * radius;
            double z = Math.sin(angle) * radius;
            double y = 0.25D + (Math.sin(angle + (elapsedTicks * 0.12D)) * 0.12D);
            particleDisplayService.spawnForNearbyViewers(
                base.clone().add(x, y, z),
                SharedParticleDefinitions.BASE_RETURN_RING_END_ROD
            );
        }
        particleDisplayService.spawnForNearbyViewers(
            base.clone().add(0.0D, 1.0D, 0.0D),
            SharedParticleDefinitions.BASE_RETURN_PORTAL
        );
        if (elapsedTicks % 20L == 0L) {
            player.playSound(player.getLocation(), Sound.BLOCK_BEACON_AMBIENT, SoundCategory.PLAYERS, 0.55F, 1.2F);
        }
    }

    private void playArrivalEffects(@NotNull Player player) {
        Location base = player.getLocation().clone();
        particleDisplayService.spawnForNearbyViewers(
            base.clone().add(0.0D, 1.0D, 0.0D),
            SharedParticleDefinitions.BASE_RETURN_PORTAL.withCount(24)
        );
        for (int index = 0; index < RING_POINTS + 2; index++) {
            double angle = (Math.PI * 2.0D * index) / (RING_POINTS + 2);
            double x = Math.cos(angle) * 1.2D;
            double z = Math.sin(angle) * 1.2D;
            particleDisplayService.spawnForNearbyViewers(
                base.clone().add(x, 0.35D, z),
                SharedParticleDefinitions.BASE_RETURN_RING_END_ROD
            );
        }
        player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, SoundCategory.PLAYERS, 0.9F, 1.05F);
    }

    private static int secondsRemaining(long elapsedTicks) {
        long remainingTicks = Math.max(0L, RETURN_DELAY_TICKS - elapsedTicks);
        return Math.max(1, (int) Math.ceil(remainingTicks / 20.0D));
    }

    private static final class PendingReturn {
        private final AstPlayer astPlayer;
        private final WorldMasterData baseWorld;
        private final long goldCost;
        private final BossBar bossBar;
        private int lastDisplayedSeconds = -1;
        private MovementCancelableWait wait;

        private PendingReturn(
            @NotNull AstPlayer astPlayer,
            @NotNull WorldMasterData baseWorld,
            long goldCost,
            @NotNull BossBar bossBar
        ) {
            this.astPlayer = astPlayer;
            this.baseWorld = baseWorld;
            this.goldCost = goldCost;
            this.bossBar = bossBar;
        }

        private @NotNull AstPlayer astPlayer() {
            return astPlayer;
        }

        private @NotNull WorldMasterData baseWorld() {
            return baseWorld;
        }

        private long goldCost() {
            return goldCost;
        }

        private @NotNull BossBar bossBar() {
            return bossBar;
        }

        private int lastDisplayedSeconds() {
            return lastDisplayedSeconds;
        }

        private void setLastDisplayedSeconds(int lastDisplayedSeconds) {
            this.lastDisplayedSeconds = lastDisplayedSeconds;
        }

        private @Nullable MovementCancelableWait waitHandle() {
            return wait;
        }

        private void setWait(@NotNull MovementCancelableWait wait) {
            this.wait = wait;
        }
    }
}
