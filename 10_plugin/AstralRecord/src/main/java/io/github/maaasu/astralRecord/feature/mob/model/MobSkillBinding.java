package io.github.maaasu.astralRecord.feature.mob.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * Mob マスターから Mob 専用スキルExecutorへ渡す紐付けです。
 *
 * <p>プレイヤー用 {@code SkillDefinition} とは独立しており、習得、職業、
 * レベルなどのプレイヤー用状態を持ちません。{@code params} は個別Executorが宣言した
 * 数値パラメータだけを受け付け、通常は2～3個、最大10個を上限とします。</p>
 *
 * @param id              Mob 専用スキル ID
 * @param activationRange 発動開始距離の上書き。未指定時はExecutor既定値
 * @param cooldownTicks   再使用間隔の上書き。未指定時はExecutor既定値
 * @param castTimeTicks   詠唱時間の上書き。未指定時はExecutor既定値
 * @param params          Executor固有の数値パラメータ
 */
public record MobSkillBinding(
        @NotNull String id,
        @Nullable Double activationRange,
        @Nullable Long cooldownTicks,
        @Nullable Long castTimeTicks,
        @NotNull Map<String, Double> params
) {

    /** Mob スキル紐付けを安全な不変値として構築します。 */
    public MobSkillBinding {
        id = id == null ? "" : id.trim();
        params = Map.copyOf(params == null ? Map.of() : params);
        if (params.size() > 10) {
            throw new IllegalArgumentException("Mob skill params は10個以下で指定してください: " + id);
        }
        if (activationRange != null && (!Double.isFinite(activationRange) || activationRange < 0.0D)) {
            throw new IllegalArgumentException("activationRange は0以上の有限値で指定してください: " + id);
        }
        if (cooldownTicks != null && cooldownTicks < 0L) {
            throw new IllegalArgumentException("cooldownTicks は0以上で指定してください: " + id);
        }
        if (castTimeTicks != null && castTimeTicks < 0L) {
            throw new IllegalArgumentException("castTimeTicks は0以上で指定してください: " + id);
        }
    }
}
