package io.github.maaasu.astralRecord.feature.combat.service;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 直近1秒の与ダメージ集計をプレイヤー単位で管理します。
 */
public final class CombatDpsTrackerService {
    private static final long TRACKING_WINDOW_MILLIS = 1000L;

    private final Map<UUID, Deque<DamageSample>> damageSamplesByPlayer = new ConcurrentHashMap<>();

    /**
     * 与ダメージイベントを1秒DPS集計へ反映します。
     *
     * @param playerId プレイヤーUUID
     * @param finalDamage 最終与ダメージ
     */
    public void recordDamage(@NotNull UUID playerId, double finalDamage) {
        if (finalDamage <= 0.0D) {
            return;
        }
        Deque<DamageSample> samples = damageSamplesByPlayer.computeIfAbsent(playerId, ignored -> new ArrayDeque<>());
        long now = System.currentTimeMillis();
        samples.addLast(new DamageSample(now, finalDamage));
        cleanupSamples(samples, now);
        if (samples.isEmpty()) {
            damageSamplesByPlayer.remove(playerId);
        }
    }

    /**
     * 指定プレイヤーの最新1秒DPS値を返します。
     *
     * @param playerId プレイヤーUUID
     * @return 1秒あたりの平均与ダメージ
     */
    public double getCurrentDps(@NotNull UUID playerId) {
        Deque<DamageSample> samples = damageSamplesByPlayer.get(playerId);
        if (samples == null || samples.isEmpty()) {
            return 0.0D;
        }
        long now = System.currentTimeMillis();
        cleanupSamples(samples, now);
        double total = 0.0D;
        for (DamageSample sample : samples) {
            total += sample.finalDamage();
        }
        if (samples.isEmpty()) {
            damageSamplesByPlayer.remove(playerId);
        }
        return total;
    }

    private void cleanupSamples(@NotNull Deque<DamageSample> samples, long now) {
        long threshold = now - TRACKING_WINDOW_MILLIS;
        while (!samples.isEmpty() && samples.peekFirst().occurredAtMs() < threshold) {
            samples.removeFirst();
        }
    }

    private record DamageSample(long occurredAtMs, double finalDamage) {
    }
}
