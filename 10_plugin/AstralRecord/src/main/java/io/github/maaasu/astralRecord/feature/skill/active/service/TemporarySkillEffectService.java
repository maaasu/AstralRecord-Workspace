package io.github.maaasu.astralRecord.feature.skill.active.service;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
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
        apply(
                entityId,
                effectId,
                durationTicks,
                incomingMultiplier,
                outgoingMultiplier,
                knockbackMultiplier,
                1.0D
        );
    }

    private void apply(
            @NotNull UUID entityId,
            @NotNull String effectId,
            long durationTicks,
            double incomingMultiplier,
            double outgoingMultiplier,
            double knockbackMultiplier,
            double defenseMultiplier
    ) {
        long expiresAtMillis = currentTimeMillis.getAsLong() + Math.max(1L, durationTicks) * MILLIS_PER_TICK;
        modifiersByEntity.computeIfAbsent(entityId, ignored -> new ConcurrentHashMap<>())
                .put(effectId, new Modifier(
                        Math.max(0.0D, incomingMultiplier),
                        Math.max(0.0D, outgoingMultiplier),
                        Math.max(0.0D, knockbackMultiplier),
                        Math.max(0.0D, defenseMultiplier),
                        expiresAtMillis
                ));
    }

    /**
     * 対象の防御力へ一時的な乗算補正を設定します。
     * <p>
     * 同じ対象・同じeffect IDの補正は置き換えられるため、同一スキルの再付与を重複させません。
     * 異なるeffect IDの補正は他の一時効果と同じく乗算されます。
     *
     * @param entityId 対象エンティティ UUID
     * @param effectId 効果 ID
     * @param durationTicks 持続tick
     * @param defenseMultiplier 防御力へ適用する倍率（0以上）
     */
    public void applyDefenseMultiplier(
            @NotNull UUID entityId,
            @NotNull String effectId,
            long durationTicks,
            double defenseMultiplier
    ) {
        if (!Double.isFinite(defenseMultiplier) || defenseMultiplier < 0.0D) {
            throw new IllegalArgumentException("defenseMultiplier must be a finite non-negative number");
        }
        apply(entityId, effectId, durationTicks, 1.0D, 1.0D, 1.0D, defenseMultiplier);
    }

    /** 対象へ適用する被ダメージ倍率を返します。 */
    public double incomingMultiplier(@NotNull AstEntity target) {
        return multiplier(target.id(), Modifier::incomingMultiplier);
    }

    /** 攻撃者へ適用する与ダメージ倍率を返します。 */
    public double outgoingMultiplier(@NotNull AstEntity attacker) {
        return multiplier(attacker.id(), Modifier::outgoingMultiplier);
    }

    /** 対象へ適用するノックバック倍率を返します。 */
    public double knockbackMultiplier(@NotNull AstEntity target) {
        return multiplier(target.id(), Modifier::knockbackMultiplier);
    }

    /** 対象の防御力へ適用する一時倍率を返します。 */
    public double defenseMultiplier(@NotNull AstEntity target) {
        return multiplier(target.id(), Modifier::defenseMultiplier);
    }

    /** 指定対象の全効果を解除します。 */
    public void clear(@NotNull UUID entityId) {
        modifiersByEntity.remove(entityId);
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
            double defenseMultiplier,
            long expiresAtMillis
    ) {
    }
}
