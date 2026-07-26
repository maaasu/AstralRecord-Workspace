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
        return multiplier(attacker.id(), Modifier::outgoingMultiplier);
    }

    /** 対象へ適用するノックバック倍率を返します。 */
    public double knockbackMultiplier(@NotNull AstEntity target) {
        return multiplier(target.id(), Modifier::knockbackMultiplier);
    }

    /** 指定対象の全効果を解除します。 */
    public void clear(@NotNull UUID entityId) {
        modifiersByEntity.remove(entityId);
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
            long expiresAtMillis
    ) {
    }
}
