package io.github.maaasu.astralRecord.feature.status.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

/**
 * オンラインプレイヤーの HP / MP / エネルギーを定期的に少しずつ回復させる定常タスク。
 * <p>
 * 各ステータスの基準回復量は {@link StatusType#HP_REGEN} / {@link StatusType#MP_REGEN} /
 * {@link StatusType#ENERGY_REGEN}（5秒あたりの値として定義）を使用し、1秒ごとに 1/5 ずつ加算します。
 */
public class StatusRegenTask {

    /** 1秒 = 20Tick */
    private static final long PERIOD_TICKS = 20L;

    /** {@link StatusType#HP_REGEN} などが「5秒あたり」の値として定義されているための分母 */
    private static final double REGEN_PERIOD_SECONDS = 5.0D;
    private static final long SHIELD_RECHARGE_BASE_DELAY_MS = 10_000L;

    private final StatusService statusService;
    private BukkitTask task;

    public StatusRegenTask(@NotNull StatusService statusService) {
        this.statusService = statusService;
    }

    /**
     * 定期回復タスクを開始します。多重起動は無視されます。
     *
     * @param plugin プラグイン本体
     */
    public void start(@NotNull AstralRecord plugin) {
        if (task != null) {
            return;
        }
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, PERIOD_TICKS, PERIOD_TICKS);
    }

    /**
     * 定期回復タスクを停止します。
     */
    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void tick() {
        for (AstPlayer astPlayer : AstPlayerCache.getAll()) {
            Player player = astPlayer.getBukkit();
            if (!player.isOnline() || player.isDead()) {
                continue;
            }
            applyRegen(astPlayer);
        }
    }

    /**
     * プレイヤー1人分の回復処理を行います。
     *
     * @param astPlayer 対象プレイヤー
     */
    private void applyRegen(@NotNull AstPlayer astPlayer) {
        StatusSnapshot snapshot = statusService.getStatus(astPlayer);

        double hpRegenPerSecond = snapshot.getMaxValue(StatusType.HP_REGEN) / REGEN_PERIOD_SECONDS;
        double mpRegenPerSecond = snapshot.getMaxValue(StatusType.MP_REGEN) / REGEN_PERIOD_SECONDS;
        double energyRegenPerSecond = snapshot.getMaxValue(StatusType.ENERGY_REGEN) / REGEN_PERIOD_SECONDS;

        if (hpRegenPerSecond > 0.0D && snapshot.getCurrentHp() < snapshot.getMaxValue(StatusType.MAX_HEALTH)) {
            statusService.recoverHp(astPlayer, hpRegenPerSecond);
        }
        if (mpRegenPerSecond > 0.0D && snapshot.getCurrentMp() < snapshot.getMaxValue(StatusType.MAX_MANA)) {
            statusService.recoverMp(astPlayer, mpRegenPerSecond);
        }
        if (energyRegenPerSecond > 0.0D && snapshot.getCurrentEnergy() < snapshot.getMaxValue(StatusType.MAX_ENERGY)) {
            statusService.recoverEnergy(astPlayer, energyRegenPerSecond);
        }
        if (shouldRechargeShield(snapshot)) {
            double amount = 1.0D + snapshot.getMaxValue(StatusType.SHIELD_RECHARGE_RATE);
            statusService.recoverShield(astPlayer, amount);
        }
    }

    private boolean shouldRechargeShield(@NotNull StatusSnapshot snapshot) {
        double maxShield = snapshot.getMaxValue(StatusType.MAX_SHIELD);
        if (maxShield <= 0.0D || snapshot.getCurrentShield() >= maxShield) {
            return false;
        }
        double reduction = Math.clamp(snapshot.getMaxValue(StatusType.SHIELD_RECHARGE_REDUCTION), 0.0D, 95.0D);
        long delayMs = Math.max(500L, Math.round(SHIELD_RECHARGE_BASE_DELAY_MS * (1.0D - reduction / 100.0D)));
        long changedAt = snapshot.getShieldChangedAtMs();
        return changedAt > 0L && System.currentTimeMillis() - changedAt >= delayMs;
    }
}
