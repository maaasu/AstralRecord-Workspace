package io.github.maaasu.astralRecord.feature.combat.model;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 一回の挑戦で確定した与ダメージ・被ダメージ・実HP回復量を保持します。 */
public final class EncounterCombatStatistics {
    private final Map<UUID, Double> damageDealt = new ConcurrentHashMap<>();
    private final Map<UUID, Double> damageTaken = new ConcurrentHashMap<>();
    private final Map<UUID, Double> healing = new ConcurrentHashMap<>();

    /**
     * 与ダメージを加算します。
     * @param playerId 攻撃者UUID
     * @param amount 実HP・シールドダメージ。正の有限値だけ加算します
     */
    public void addDamageDealt(UUID playerId, double amount) {
        add(damageDealt, playerId, amount);
    }

    /**
     * 被ダメージを加算します。
     * @param playerId 被弾者UUID
     * @param amount 実HP・シールドダメージ。正の有限値だけ加算します
     */
    public void addDamageTaken(UUID playerId, double amount) {
        add(damageTaken, playerId, amount);
    }

    /**
     * ヒール量を加算します。
     * @param playerId 回復者UUID
     * @param amount 自己回復を含む実HP回復量。正の有限値だけ加算します
     */
    public void addHealing(UUID playerId, double amount) {
        add(healing, playerId, amount);
    }

    /** @return 呼び出し時点の変更不能な与ダメージ集計 */
    public Map<UUID, Double> damageDealtSnapshot() {
        return Map.copyOf(damageDealt);
    }

    /** @return 呼び出し時点の変更不能な被ダメージ集計 */
    public Map<UUID, Double> damageTakenSnapshot() {
        return Map.copyOf(damageTaken);
    }

    /** @return 呼び出し時点の変更不能なヒール量集計 */
    public Map<UUID, Double> healingSnapshot() {
        return Map.copyOf(healing);
    }

    /**
     * 不成立・非有限の値を除外し、指定した指標だけを加算します。
     * @param totals 対象指標の累計
     * @param playerId 対象参加者
     * @param amount 実量
     */
    private void add(Map<UUID, Double> totals, UUID playerId, double amount) {
        if (Double.isFinite(amount) && amount > 0.0D) {
            totals.merge(playerId, amount, Double::sum);
        }
    }
}
