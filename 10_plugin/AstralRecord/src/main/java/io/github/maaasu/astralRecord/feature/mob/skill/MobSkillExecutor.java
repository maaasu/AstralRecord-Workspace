package io.github.maaasu.astralRecord.feature.mob.skill;

import io.github.maaasu.astralRecord.feature.mob.model.MobSkillBinding;
import io.github.maaasu.astralRecord.feature.mob.model.MobSkillTiming;
import org.jetbrains.annotations.NotNull;

/**
 * プレイヤー用スキルマスターと分離した、Mob 専用スキルの実行契約です。
 *
 * <p>1つの実装は1つの {@link #id()} だけを担当します。Mob マスターは ID と少数の
 * {@link MobSkillBinding#params()} を指定し、スキルジェム・習得・職業・レベルは扱いません。</p>
 */
public interface MobSkillExecutor {

    /** @return Mob マスターから参照する一意な Mob スキル ID */
    @NotNull String id();

    /** @return Mob の頭上詠唱バーへ表示する日本語名 */
    @NotNull String displayName();

    /** @return Mob マスターで省略したときの既定発動設定 */
    @NotNull MobSkillTiming defaultTiming();

    /**
     * 紐付けの上書きを反映した実効タイミングを返します。
     *
     * @param binding Mob マスター上のスキル紐付け
     * @return 実効タイミング
     */
    default @NotNull MobSkillTiming resolveTiming(@NotNull MobSkillBinding binding) {
        MobSkillTiming defaults = defaultTiming();
        return new MobSkillTiming(
                binding.activationRange() == null ? defaults.activationRange() : binding.activationRange(),
                binding.cooldownTicks() == null ? defaults.cooldownTicks() : binding.cooldownTicks(),
                binding.castTimeTicks() == null ? defaults.castTimeTicks() : binding.castTimeTicks()
        );
    }

    /**
     * 個別パラメータを検証します。
     *
     * @param binding Mob マスター上のスキル紐付け
     * @throws IllegalArgumentException 未知のキーまたは値域外の値を受け取った場合
     */
    default void validate(@NotNull MobSkillBinding binding) {
        // パラメータを持たない Mob スキルは既定で空だけを許可します。
        if (!binding.params().isEmpty()) {
            throw new IllegalArgumentException("params を受け付けない Mob skill: " + id());
        }
    }

    /**
     * スキル効果を実行します。
     *
     * @param context 発動開始時点で固定された文脈
     * @return 実際にスキルを開始できた場合は {@code true}
     */
    boolean cast(@NotNull MobSkillContext context);
}
