package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.feature.buff.model.ActiveBuff;
import io.github.maaasu.astralRecord.feature.buff.service.BuffAcquisitionDisplayService;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.ItemConsumable;
import io.github.maaasu.astralRecord.feature.item.model.ItemConsumableEffect;
import io.github.maaasu.astralRecord.feature.item.model.ItemConsumableEffectType;
import io.github.maaasu.astralRecord.feature.item.model.ItemConsumableOnUse;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.model.ItemReference;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.feature.status.model.HealthRecoveryContext;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import io.github.maaasu.astralRecord.shared.timing.MovementCancelableWait;
import io.github.maaasu.astralRecord.shared.timing.MovementCancelableWaitCallbacks;
import io.github.maaasu.astralRecord.shared.timing.MovementCancelableWaitCancelReason;
import io.github.maaasu.astralRecord.shared.timing.MovementCancelableWaitService;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * ポーションなどの消耗品を右クリック使用したときの待機・効果適用を担当します。
 */
public final class PotionUseService {
    private static final String STATUS_HP = "hp";
    private static final String STATUS_HEALTH = "health";
    private static final String STATUS_MAX_HEALTH = "max_health";
    private static final String STATUS_MP = "mp";
    private static final String STATUS_MANA = "mana";
    private static final String STATUS_MAX_MANA = "max_mana";
    private static final String STATUS_EN = "en";
    private static final String STATUS_ENERGY = "energy";
    private static final String STATUS_MAX_ENERGY = "max_energy";
    private static final long DEFAULT_USE_TIME_TICKS = 40L;
    private static final long DEFAULT_COOLDOWN_TICKS = 40L;
    private static final long MILLIS_PER_TICK = 50L;
    private static final long EFFECT_PERIOD_TICKS = 5L;
    private static final int RING_POINTS = 8;
    private static final Title.Times COUNTDOWN_TITLE_TIMES =
        Title.Times.times(Duration.ZERO, Duration.ofMillis(900L), Duration.ofMillis(120L));
    private static final Title.Times RESULT_TITLE_TIMES =
        Title.Times.times(Duration.ZERO, Duration.ofMillis(1400L), Duration.ofMillis(200L));

    private final MovementCancelableWaitService movementCancelableWaitService;
    private final InventoryService inventoryService;
    private final StatusService statusService;
    private final BuffAcquisitionDisplayService buffDisplayService;
    private final ParticleDisplayService particleDisplayService;
    private final Map<UUID, PendingPotionUse> pendingUses = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, Long>> cooldownExpiresAtMillis = new ConcurrentHashMap<>();

    /**
     * サービスを生成します。
     *
     * @param movementCancelableWaitService 移動キャンセル付き待機サービス
     * @param inventoryService 消費元ホットバー更新サービス
     * @param statusService ステータス・バフ適用サービス
     * @param buffDisplayService バフ獲得通知サービス
     * @param particleDisplayService パーティクル表示サービス
     */
    public PotionUseService(
        @NotNull MovementCancelableWaitService movementCancelableWaitService,
        @NotNull InventoryService inventoryService,
        @NotNull StatusService statusService,
        @NotNull BuffAcquisitionDisplayService buffDisplayService,
        @NotNull ParticleDisplayService particleDisplayService
    ) {
        this.movementCancelableWaitService = movementCancelableWaitService;
        this.inventoryService = inventoryService;
        this.statusService = statusService;
        this.buffDisplayService = buffDisplayService;
        this.particleDisplayService = particleDisplayService;
    }

    /**
     * 消耗品の使用待機を開始し、待機完了後に効果適用とアイテム消費を行います。
     *
     * @param astPlayer 使用プレイヤー
     * @param hand 使用した手
     * @param model 使用アイテム定義
     * @return 使用待機を開始できた場合 true
     */
    public boolean use(@NotNull AstPlayer astPlayer, @NotNull EquipmentSlot hand, @NotNull ItemModel model) {
        ItemConsumable consumable = model.getConsumable();
        if (consumable == null || consumable.getEffects().isEmpty()) {
            return false;
        }

        UUID playerId = astPlayer.getBukkit().getUniqueId();
        if (pendingUses.containsKey(playerId)) {
            return false;
        }

        long remainingCooldownTicks = remainingCooldownTicks(playerId, model.getId());
        if (remainingCooldownTicks > 0L) {
            PlayerMessageService.getInstance().send(
                astPlayer,
                PlayerMsgId.P_5264,
                displayItemName(model),
                formatTicksAsSeconds(remainingCooldownTicks)
            );
            return false;
        }

        long useTimeTicks = resolveUseTimeTicks(consumable);
        BossBar bossBar = Bukkit.createBossBar(
            PlayerMsgResource.format(PlayerMsgId.P_5267.getId(), displayItemName(model), secondsRemaining(useTimeTicks, 0L)),
            BarColor.GREEN,
            BarStyle.SEGMENTED_20
        );
        bossBar.setVisible(true);
        bossBar.setProgress(0.0D);
        bossBar.addPlayer(astPlayer.getBukkit());

        PendingPotionUse pending = new PendingPotionUse(astPlayer, hand, model, consumable, useTimeTicks, bossBar);
        updateUseDisplay(pending, secondsRemaining(useTimeTicks, 0L));
        playUseStartEffects(astPlayer.getBukkit(), consumable.getOnUse());
        pendingUses.put(playerId, pending);
        pending.setWait(movementCancelableWaitService.begin(
            astPlayer.getBukkit(),
            useTimeTicks,
            new MovementCancelableWaitCallbacks() {
                @Override
                public void onTick(long elapsedTicks, double progress) {
                    tickPendingUse(playerId, pending, elapsedTicks, progress);
                }

                @Override
                public void onComplete() {
                    completePendingUse(playerId, pending);
                }

                @Override
                public void onCancel(@NotNull MovementCancelableWaitCancelReason reason) {
                    cancelPendingUse(playerId, pending, shouldNotifyCancel(reason));
                }
            }
        ));
        PlayerMessageService.getInstance().send(
            astPlayer,
            PlayerMsgId.P_5249,
            displayItemName(model),
            formatTicksAsSeconds(useTimeTicks)
        );
        return true;
    }

    /**
     * 進行中のポーション使用待機をキャンセルします。
     *
     * @param astPlayer キャンセル対象プレイヤー
     * @param notify プレイヤーへ通知する場合は {@code true}
     * @return キャンセル対象が存在した場合は {@code true}
     */
    public boolean cancelPendingUse(@NotNull AstPlayer astPlayer, boolean notify) {
        return cancelPendingUse(astPlayer.getBukkit().getUniqueId(), notify);
    }

    /**
     * 進行中のポーション使用待機を破棄します。切断時のクリーンアップ用途です。
     *
     * @param playerId プレイヤー UUID
     * @return キャンセル対象が存在した場合は {@code true}
     */
    public boolean cancelPendingUse(@NotNull UUID playerId) {
        return cancelPendingUse(playerId, false);
    }

    private boolean cancelPendingUse(@NotNull UUID playerId, boolean notify) {
        PendingPotionUse pending = pendingUses.get(playerId);
        if (pending == null) {
            return false;
        }
        MovementCancelableWaitCancelReason reason = notify
            ? MovementCancelableWaitCancelReason.HELD_ITEM_CHANGED
            : MovementCancelableWaitCancelReason.MANUAL;
        if (pending.waitHandle() != null) {
            return pending.waitHandle().cancel(reason);
        }

        cancelPendingUse(playerId, pending, notify);
        return true;
    }

    private void cancelPendingUse(@NotNull UUID playerId, @NotNull PendingPotionUse pending, boolean notify) {
        if (!pendingUses.remove(playerId, pending)) {
            return;
        }
        cleanupPending(pending);
        if (notify && pending.astPlayer().getBukkit().isOnline()) {
            String displayName = displayItemName(pending.model());
            PlayerMessageService.getInstance().send(pending.astPlayer(), PlayerMsgId.P_5263, displayName);
            showResultTitle(
                pending.astPlayer().getBukkit(),
                PlayerMsgId.P_5270,
                PlayerMsgId.P_5263,
                displayName
            );
            pending.astPlayer().getBukkit().playSound(
                pending.astPlayer().getBukkit().getLocation(),
                Sound.BLOCK_BEACON_DEACTIVATE,
                SoundCategory.PLAYERS,
                0.55F,
                1.35F
            );
        }
    }

    private void completePendingUse(@NotNull UUID playerId, @NotNull PendingPotionUse pending) {
        if (!pendingUses.remove(playerId, pending)) {
            return;
        }
        cleanupPending(pending);
        if (!pending.astPlayer().getBukkit().isOnline() || !isStillHolding(pending)) {
            return;
        }
        if (!applyAndConsume(pending)) {
            return;
        }
        showResultTitle(
            pending.astPlayer().getBukkit(),
            PlayerMsgId.P_5268,
            PlayerMsgId.P_5269,
            displayItemName(pending.model())
        );
        startCooldown(playerId, pending.model().getId(), resolveCooldownTicks(pending.consumable()));
    }

    private void tickPendingUse(
        @NotNull UUID playerId,
        @NotNull PendingPotionUse pending,
        long elapsedTicks,
        double progress
    ) {
        if (pendingUses.get(playerId) != pending) {
            return;
        }

        Player player = pending.astPlayer().getBukkit();
        if (!player.isOnline()) {
            cancelPendingUse(playerId, false);
            return;
        }

        pending.bossBar().setProgress(progress);

        int remainingSeconds = secondsRemaining(pending.useTimeTicks(), elapsedTicks);
        if (remainingSeconds != pending.lastDisplayedSeconds()) {
            updateUseDisplay(pending, remainingSeconds);
        }
        if (elapsedTicks % EFFECT_PERIOD_TICKS == 0L) {
            playUseChargeEffects(player, elapsedTicks, progress, pending.consumable().getOnUse());
        }
    }

    private void cleanupPending(@NotNull PendingPotionUse pending) {
        pending.astPlayer().getBukkit().resetTitle();
        pending.bossBar().removeAll();
        pending.bossBar().setVisible(false);
    }

    private void updateUseDisplay(@NotNull PendingPotionUse pending, int remainingSeconds) {
        pending.setLastDisplayedSeconds(remainingSeconds);
        Player player = pending.astPlayer().getBukkit();
        player.showTitle(Title.title(
            PlayerMsgResource.formatComponent(PlayerMsgId.P_5265.getId(), displayItemName(pending.model())),
            PlayerMsgResource.formatComponent(PlayerMsgId.P_5266.getId(), displayItemName(pending.model()), remainingSeconds),
            COUNTDOWN_TITLE_TIMES
        ));
        pending.bossBar().setTitle(PlayerMsgResource.format(
            PlayerMsgId.P_5267.getId(),
            displayItemName(pending.model()),
            remainingSeconds
        ));
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

    private void playUseStartEffects(@NotNull Player player, @Nullable ItemConsumableOnUse onUse) {
        playUsingSound(player, onUse, 0.7F, 1.0F);
        playUseChargeEffects(player, 0L, 0.0D, onUse);
    }

    private void playUseChargeEffects(
        @NotNull Player player,
        long elapsedTicks,
        double progress,
        @Nullable ItemConsumableOnUse onUse
    ) {
        Location base = player.getLocation().clone();
        double radius = 0.55D + (progress * 0.25D) + (Math.sin(elapsedTicks * 0.22D) * 0.04D);
        for (int index = 0; index < RING_POINTS; index++) {
            double angle = (elapsedTicks * 0.24D) + ((Math.PI * 2.0D * index) / RING_POINTS);
            double x = Math.cos(angle) * radius;
            double z = Math.sin(angle) * radius;
            double y = 0.85D + (Math.sin(angle + (elapsedTicks * 0.10D)) * 0.12D);
            particleDisplayService.spawnForNearbyViewers(
                base.clone().add(x, y, z),
                SharedParticleDefinitions.POTION_USE_RING_DUST
            );
        }
        particleDisplayService.spawnForNearbyViewers(
            base.clone().add(0.0D, 1.05D, 0.0D),
            SharedParticleDefinitions.POTION_USE_ENCHANT
        );
        if (elapsedTicks % 10L == 0L) {
            playUsingSound(player, onUse, 0.35F, (float) (1.18D + (progress * 0.24D)));
        }
    }

    private void playUsingSound(
        @NotNull Player player,
        @Nullable ItemConsumableOnUse onUse,
        float volume,
        float pitch
    ) {
        String usingSound = onUse == null ? null : onUse.getUsingSound();
        if (usingSound != null && !usingSound.isBlank()) {
            player.playSound(player.getLocation(), usingSound.trim(), SoundCategory.PLAYERS, volume, pitch);
            return;
        }
        player.playSound(player.getLocation(), Sound.ENTITY_GENERIC_DRINK, SoundCategory.PLAYERS, volume, pitch);
    }

    private boolean applyAndConsume(@NotNull PendingPotionUse pending) {
        boolean applied = false;
        String recoverySource = plainDisplayItemName(pending.model());
        for (ItemConsumableEffect effect : pending.consumable().getEffects()) {
            if (!passesRate(effect.getRate())) {
                continue;
            }
            applied |= applyEffect(pending.astPlayer(), effect, recoverySource);
        }

        if (!applied) {
            return false;
        }

        int consumeAmount = pending.consumable().getOnUse() == null
            ? 1
            : Math.max(1, pending.consumable().getOnUse().getAmount());
        if (!inventoryService.consumeHotbarItemInHand(
            pending.astPlayer(),
            pending.hand(),
            pending.model().getId(),
            consumeAmount
        )) {
            PlayerMessageService.getInstance().send(pending.astPlayer(), PlayerMsgId.P_5245);
            return false;
        }

        playOnUse(pending.astPlayer(), pending.consumable().getOnUse());
        return true;
    }

    private boolean isStillHolding(@NotNull PendingPotionUse pending) {
        ItemReference currentReference = inventoryService.getItemReferenceInHand(
            pending.astPlayer(),
            pending.hand()
        );
        return currentReference != null && pending.model().getId().equalsIgnoreCase(currentReference.itemId());
    }

    private void startCooldown(@NotNull UUID playerId, @NotNull String itemId, long cooldownTicks) {
        if (cooldownTicks <= 0L) {
            return;
        }
        cooldownExpiresAtMillis
            .computeIfAbsent(playerId, ignored -> new ConcurrentHashMap<>())
            .put(normalizeItemId(itemId), System.currentTimeMillis() + (cooldownTicks * MILLIS_PER_TICK));
    }

    private long remainingCooldownTicks(@NotNull UUID playerId, @NotNull String itemId) {
        Map<String, Long> playerCooldowns = cooldownExpiresAtMillis.get(playerId);
        if (playerCooldowns == null) {
            return 0L;
        }
        String normalizedItemId = normalizeItemId(itemId);
        Long expiresAt = playerCooldowns.get(normalizedItemId);
        if (expiresAt == null) {
            return 0L;
        }
        long remainingMillis = expiresAt - System.currentTimeMillis();
        if (remainingMillis <= 0L) {
            playerCooldowns.remove(normalizedItemId);
            if (playerCooldowns.isEmpty()) {
                cooldownExpiresAtMillis.remove(playerId, playerCooldowns);
            }
            return 0L;
        }
        return Math.max(1L, (remainingMillis + MILLIS_PER_TICK - 1L) / MILLIS_PER_TICK);
    }

    private boolean applyEffect(
        @NotNull AstPlayer astPlayer,
        @NotNull ItemConsumableEffect effect,
        @NotNull String recoverySource
    ) {
        if (effect.getType() == ItemConsumableEffectType.BUFF) {
            return applyBuff(astPlayer, effect.getBuffId());
        }
        if (effect.getType() == ItemConsumableEffectType.RECOVER) {
            return applyRecover(astPlayer, effect, recoverySource);
        }
        return false;
    }

    private boolean applyBuff(@NotNull AstPlayer astPlayer, @Nullable String buffId) {
        if (buffId == null || buffId.isBlank()) {
            Logger.log(LogId.W_5204, "consumable");
            return false;
        }
        statusService.applyBuff(astPlayer, buffId);
        ActiveBuff activeBuff = statusService.getActiveBuffs(astPlayer).stream()
            .filter(buff -> buff.getType().getId().equalsIgnoreCase(buffId))
            .findFirst()
            .orElse(null);
        if (activeBuff == null) {
            return false;
        }
        buffDisplayService.show(astPlayer.getBukkit(), activeBuff);
        return true;
    }

    private boolean applyRecover(
        @NotNull AstPlayer astPlayer,
        @NotNull ItemConsumableEffect effect,
        @NotNull String recoverySource
    ) {
        double value = effect.getValue() == null ? 0.0D : effect.getValue();
        if (value <= 0.0D) {
            return false;
        }

        String status = normalizeStatus(effect.getStatus());
        StatusSnapshot snapshot = statusService.getStatus(astPlayer);
        if (STATUS_HP.equals(status) || STATUS_HEALTH.equals(status) || STATUS_MAX_HEALTH.equals(status)) {
            statusService.recoverHp(
                astPlayer,
                resolveRecoverAmount(value, effect.isPercent(), snapshot, StatusType.MAX_HEALTH),
                HealthRecoveryContext.self(recoverySource)
            );
            return true;
        }
        if (STATUS_MP.equals(status) || STATUS_MANA.equals(status) || STATUS_MAX_MANA.equals(status)) {
            statusService.recoverMp(astPlayer, resolveRecoverAmount(value, effect.isPercent(), snapshot, StatusType.MAX_MANA));
            return true;
        }
        if (STATUS_EN.equals(status) || STATUS_ENERGY.equals(status) || STATUS_MAX_ENERGY.equals(status)) {
            statusService.recoverEnergy(astPlayer, resolveRecoverAmount(value, effect.isPercent(), snapshot, StatusType.MAX_ENERGY));
            return true;
        }
        return false;
    }

    private double resolveRecoverAmount(
        double value,
        boolean percent,
        @NotNull StatusSnapshot snapshot,
        @NotNull StatusType maxType
    ) {
        return percent ? snapshot.getMaxValue(maxType) * value : value;
    }

    private boolean passesRate(double rate) {
        if (rate >= 100.0D) {
            return true;
        }
        if (rate <= 0.0D) {
            return false;
        }
        return ThreadLocalRandom.current().nextDouble(100.0D) < rate;
    }

    private void playOnUse(@NotNull AstPlayer astPlayer, @Nullable ItemConsumableOnUse onUse) {
        Location location = astPlayer.getBukkit().getLocation();
        World world = astPlayer.getBukkit().getWorld();
        if (world == null) {
            return;
        }
        if (onUse != null && onUse.getSound() != null && !onUse.getSound().isBlank()) {
            astPlayer.getBukkit().playSound(location, onUse.getSound(), 1.0F, 1.0F);
        } else {
            astPlayer.getBukkit().playSound(location, Sound.ENTITY_GENERIC_DRINK, 0.8F, 1.15F);
        }
        if (onUse != null && onUse.getEffect() != null && !onUse.getEffect().isBlank()) {
            Particle particle = parseParticle(onUse.getEffect());
            if (particle != null) {
                particleDisplayService.spawnForNearbyViewers(
                    location.clone().add(0.0D, 1.0D, 0.0D),
                    particle,
                    16,
                    0.35D,
                    0.45D,
                    0.35D,
                    0.02D
                );
            }
        }
    }

    private @NotNull String normalizeStatus(@Nullable String raw) {
        return raw == null ? "" : raw.trim().replace('-', '_').toLowerCase(Locale.ROOT);
    }

    private @Nullable Particle parseParticle(@NotNull String raw) {
        return SharedParticleDefinitions.resolveParticle(raw);
    }

    private static long resolveUseTimeTicks(@NotNull ItemConsumable consumable) {
        ItemConsumableOnUse onUse = consumable.getOnUse();
        return onUse == null ? DEFAULT_USE_TIME_TICKS : Math.max(1L, onUse.getUseTimeTicks());
    }

    private static long resolveCooldownTicks(@NotNull ItemConsumable consumable) {
        ItemConsumableOnUse onUse = consumable.getOnUse();
        return onUse == null ? DEFAULT_COOLDOWN_TICKS : Math.max(0L, onUse.getCooldownTicks());
    }

    private static boolean shouldNotifyCancel(@NotNull MovementCancelableWaitCancelReason reason) {
        return reason == MovementCancelableWaitCancelReason.MOVED
            || reason == MovementCancelableWaitCancelReason.HELD_ITEM_CHANGED;
    }

    private static @NotNull String normalizeItemId(@NotNull String itemId) {
        return itemId.trim().toLowerCase(Locale.ROOT);
    }

    private static @NotNull String displayItemName(@NotNull ItemModel model) {
        return ColorCodeUtil.toLegacyText(model.getName(), model.getId());
    }

    private static @NotNull String plainDisplayItemName(@NotNull ItemModel model) {
        return ColorCodeUtil.toPlainText(model.getName(), "ポーション");
    }

    private static @NotNull String formatTicksAsSeconds(long ticks) {
        double seconds = Math.max(0L, ticks) / 20.0D;
        if (seconds == Math.rint(seconds)) {
            return String.format(Locale.ROOT, "%.0f秒", seconds);
        }
        return String.format(Locale.ROOT, "%.1f秒", seconds);
    }

    private static int secondsRemaining(long durationTicks, long elapsedTicks) {
        long remainingTicks = Math.max(0L, durationTicks - elapsedTicks);
        return Math.max(1, (int) Math.ceil(remainingTicks / 20.0D));
    }

    private static final class PendingPotionUse {
        private final AstPlayer astPlayer;
        private final EquipmentSlot hand;
        private final ItemModel model;
        private final ItemConsumable consumable;
        private final long useTimeTicks;
        private final BossBar bossBar;
        private int lastDisplayedSeconds = -1;
        private MovementCancelableWait wait;

        private PendingPotionUse(
            @NotNull AstPlayer astPlayer,
            @NotNull EquipmentSlot hand,
            @NotNull ItemModel model,
            @NotNull ItemConsumable consumable,
            long useTimeTicks,
            @NotNull BossBar bossBar
        ) {
            this.astPlayer = astPlayer;
            this.hand = hand;
            this.model = model;
            this.consumable = consumable;
            this.useTimeTicks = useTimeTicks;
            this.bossBar = bossBar;
        }

        private @NotNull AstPlayer astPlayer() {
            return astPlayer;
        }

        private @NotNull EquipmentSlot hand() {
            return hand;
        }

        private @NotNull ItemModel model() {
            return model;
        }

        private @NotNull ItemConsumable consumable() {
            return consumable;
        }

        private long useTimeTicks() {
            return useTimeTicks;
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
