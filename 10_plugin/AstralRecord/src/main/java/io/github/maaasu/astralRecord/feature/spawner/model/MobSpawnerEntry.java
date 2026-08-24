package io.github.maaasu.astralRecord.feature.spawner.model;

import org.jetbrains.annotations.NotNull;

/**
 * スポナーが抽選対象にする Mob と重みを表します。
 *
 * @param mobId  Mob テンプレート ID
 * @param level  使用するレベルプロファイル。未指定時は Mob の最小レベル
 * @param weight 抽選重み。1 未満は 1 として扱います
 */
public record MobSpawnerEntry(@NotNull String mobId, Integer level, int weight) {

    /** 既存の mobId/weight 定義を維持するコンストラクタです。 */
    public MobSpawnerEntry(@NotNull String mobId, int weight) {
        this(mobId, null, weight);
    }

    public MobSpawnerEntry {
        if (level != null && level < 1) {
            level = null;
        }
        if (weight < 1) {
            weight = 1;
        }
    }
}
