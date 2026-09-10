package io.github.maaasu.astralRecord.feature.skill.active.service;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.DamageResult;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.ToDoubleFunction;

/**
 * ガード等の短時間ダメージ・ノックバック倍率を保持します。
 * <p>
 * 効果は ID ごとに置き換え、異なる効果は乗算します。期限切れは参照時に除去するため、
 * 個別スキルが解除タスクを持つ必要はありません。
 */
public final class TemporarySkillEffectService {

    private static final long MILLIS_PER_TICK = 50L;
    private final Map<UUID, Map<String, Modifier>> modifiersByEntity = new ConcurrentHashMap<>();
    private final LongSupplier currentTimeMillis;
    private final Map<UUID, Map<Double, Long>> defenseReductions = new ConcurrentHashMap<>();
    private final Map<UUID, Ward> wards = new ConcurrentHashMap<>();

    /** システム時刻を使う runtime サービスを作成します。 */
    public TemporarySkillEffectService() {
        this(System::currentTimeMillis);
    }

    TemporarySkillEffectService(@NotNull LongSupplier currentTimeMillis) {
        this.currentTimeMillis = currentTimeMillis;
    }

    /** 指定対象へ一時的な被・与ダメージ倍率とノックバック倍率を設定します。 */
    public void apply(
            @NotNull UUID entityId,
            @NotNull String effectId,
            long durationTicks,
            double incomingMultiplier,
            double outgoingMultiplier,
            double knockbackMultiplier
    ) {
        long expiresAtMillis = currentTimeMillis.getAsLong() + Math.max(1L, durationTicks) * MILLIS_PER_TICK;
        modifiersByEntity.computeIfAbsent(entityId, ignored -> new ConcurrentHashMap<>())
                .put(effectId, new Modifier(
                        Math.max(0.0D, incomingMultiplier),
                        Math.max(0.0D, outgoingMultiplier),
                        Math.max(0.0D, knockbackMultiplier),
                        expiresAtMillis
                ));
    }

    /** 対象へ適用する被ダメージ倍率を返します。 */
    public double incomingMultiplier(@NotNull AstEntity target) {
        return multiplier(target.id(), Modifier::incomingMultiplier);
    }

    /** 攻撃者へ適用する与ダメージ倍率を返します。 */
    public double outgoingMultiplier(@NotNull AstEntity attacker) {
        Ward ward = currentWard(attacker.id());
        double covenant = ward != null && ward.songExpiresAt > currentTimeMillis.getAsLong()
                ? ward.outgoingMultiplier : 1.0D;
        return multiplier(attacker.id(), Modifier::outgoingMultiplier) * covenant;
    }

    /** 対象へ適用するノックバック倍率を返します。 */
    public double knockbackMultiplier(@NotNull AstEntity target) {
        return multiplier(target.id(), Modifier::knockbackMultiplier);
    }

    /** 指定対象の全効果を解除します。 */
    public void clear(@NotNull UUID entityId) {
        modifiersByEntity.remove(entityId);
        defenseReductions.remove(entityId);
        wards.remove(entityId);
    }

    /**
     * 指定対象の指定効果だけを解除します。
     *
     * @param entityId 対象エンティティの UUID
     * @param effectId 解除する効果 ID
     */
    public void clear(@NotNull UUID entityId, @NotNull String effectId) {
        Map<String, Modifier> modifiers = modifiersByEntity.get(entityId);
        if (modifiers == null) {
            return;
        }
        modifiers.remove(effectId);
        if (modifiers.isEmpty()) {
            modifiersByEntity.remove(entityId, modifiers);
        }
    }

    /** 全効果を解除します。 */
    public void clearAll() {
        modifiersByEntity.clear();
        defenseReductions.clear();
        wards.clear();
    }

    /**
     * 全防御と種別防御の一時低下を登録します。異なる強度は個別に期限を持ち、最大値だけ有効です。
     * @param entityId 対象Mob ID
     * @param percent 低下率（0より大きく50以下）
     * @param durationTicks 正の効果tick数
     */
    public void reduceDefense(@NotNull UUID entityId, double percent, long durationTicks) {
        if (!Double.isFinite(percent) || percent <= 0.0D || percent > 50.0D || durationTicks <= 0L) return;
        defenseReductions.computeIfAbsent(entityId, ignored -> new ConcurrentHashMap<>())
                .merge(percent, currentTimeMillis.getAsLong() + durationTicks * MILLIS_PER_TICK, Math::max);
    }

    /**
     * 有効な防御低下のうち最も強い倍率を返します。期限切れは破棄します。
     * @param entityId 対象ID
     * @return 0.5～1の防御倍率
     */
    public double defenseMultiplier(@NotNull UUID entityId) {
        Map<Double, Long> entries = defenseReductions.get(entityId);
        if (entries == null) return 1.0D;
        long now = currentTimeMillis.getAsLong();
        entries.entrySet().removeIf(entry -> entry.getValue() <= now);
        if (entries.isEmpty()) defenseReductions.remove(entityId, entries);
        return 1.0D - entries.keySet().stream().mapToDouble(Double::doubleValue).max().orElse(0.0D) / 100.0D;
    }

    /**
     * 職業を問わない時限シールドを付与します。残量以上の強度でだけ置換し、加算しません。
     * @param entityId 対象プレイヤーUUID
     * @param amount 正の防護量
     * @param durationTicks 正の持続tick数
     * @return 付与・置換できた場合true
     */
    public boolean grantWard(@NotNull UUID entityId, double amount, long durationTicks) {
        if (!Double.isFinite(amount) || amount <= 0.0D || durationTicks <= 0L) return false;
        Ward current = currentWard(entityId);
        if (current != null && current.remaining > amount) return false;
        wards.put(entityId, new Ward(amount, currentTimeMillis.getAsLong() + durationTicks * MILLIS_PER_TICK));
        return true;
    }

    /**
     * 既存シールドへ聖歌を結び付けます。破壊・消費・置換後に効果は持ち越しません。
     * @param entityId 対象プレイヤーUUID
     * @param outgoingMultiplier 与ダメージ倍率（1～1.5）
     * @param durationTicks 正の持続tick数
     * @return 有効なシールドへ適用できた場合true
     */
    public boolean empowerWard(@NotNull UUID entityId, double outgoingMultiplier, long durationTicks) {
        Ward ward = currentWard(entityId);
        if (ward == null || !Double.isFinite(outgoingMultiplier) || outgoingMultiplier < 1.0D
                || outgoingMultiplier > 1.5D || durationTicks <= 0L) return false;
        long now = currentTimeMillis.getAsLong();
        if (ward.songExpiresAt > now && ward.outgoingMultiplier > outgoingMultiplier) return false;
        ward.outgoingMultiplier = outgoingMultiplier;
        ward.songExpiresAt = Math.min(ward.expiresAt, now + durationTicks * MILLIS_PER_TICK);
        return true;
    }

    /**
     * 生存中の時限シールド量を返します。
     * @param entityId 対象UUID
     * @return 現在防護量、失効時0
     */
    public double wardAmount(@NotNull UUID entityId) {
        Ward ward = currentWard(entityId);
        return ward == null ? 0.0D : ward.remaining;
    }

    /**
     * 自身の時限シールドを全量消費します。通常のタンクシールドへは触れません。
     * @param entityId 消費対象UUID
     * @return 実消費量
     */
    public double consumeWard(@NotNull UUID entityId) {
        double amount = wardAmount(entityId);
        wards.remove(entityId);
        return amount;
    }

    /**
     * 通常シールド判定後のHPダメージを防護量で吸収します。固定HP成分は通常成分の後に吸収します。
     * @param entityId 被弾プレイヤーUUID
     * @param result 通常シールド・固定HP加算後の命中結果
     * @return 吸収量をシールドダメージとして表示する結果。通常シールド破壊フラグは維持
     */
    public @NotNull DamageResult absorbWard(@NotNull UUID entityId, @NotNull DamageResult result) {
        Ward ward = currentWard(entityId);
        if (ward == null || result.evaded() || result.finalDamage() <= 0.0D) return result;
        double absorbed = Math.min(ward.remaining, result.finalDamage());
        ward.remaining -= absorbed;
        if (ward.remaining <= 0.0D) wards.remove(entityId);
        double healthDamage = result.finalDamage() - absorbed;
        return new DamageResult(healthDamage, Math.min(result.fixedHealthDamage(), healthDamage),
                result.shieldDamage() + absorbed, result.shieldBroken(), result.critical(),
                result.superStarCritical(), result.evaded(), result.hitChance(), result.accuracy(),
                result.evasion(), result.breakdown());
    }

    /**
     * 定期保守で未参照の期限切れ状態も破棄します。ゲームスレッドから呼び出します。
     */
    public void pruneExpired() {
        long now = currentTimeMillis.getAsLong();
        wards.entrySet().removeIf(entry -> entry.getValue().expiresAt <= now);
        defenseReductions.keySet().forEach(this::defenseMultiplier);
        modifiersByEntity.keySet().forEach(id -> multiplier(id, Modifier::incomingMultiplier));
    }

    /** 失効状態を除去し、有効な時限シールドだけ取得します。 */
    private Ward currentWard(UUID entityId) {
        Ward ward = wards.get(entityId);
        if (ward != null && ward.expiresAt <= currentTimeMillis.getAsLong()) {
            wards.remove(entityId);
            return null;
        }
        return ward;
    }

    private static final class Ward {
        private double remaining;
        private final long expiresAt;
        private double outgoingMultiplier = 1.0D;
        private long songExpiresAt;

        private Ward(double remaining, long expiresAt) {
            this.remaining = remaining;
            this.expiresAt = expiresAt;
        }
    }

    private double multiplier(
            @NotNull UUID entityId,
            @NotNull ToDoubleFunction<Modifier> selector
    ) {
        Map<String, Modifier> modifiers = modifiersByEntity.get(entityId);
        if (modifiers == null) {
            return 1.0D;
        }
        long now = currentTimeMillis.getAsLong();
        modifiers.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis() <= now);
        if (modifiers.isEmpty()) {
            modifiersByEntity.remove(entityId, modifiers);
            return 1.0D;
        }
        return modifiers.values().stream()
                .mapToDouble(selector)
                .reduce(1.0D, (left, right) -> left * right);
    }

    private record Modifier(
            double incomingMultiplier,
            double outgoingMultiplier,
            double knockbackMultiplier,
            long expiresAtMillis
    ) {
    }
}
