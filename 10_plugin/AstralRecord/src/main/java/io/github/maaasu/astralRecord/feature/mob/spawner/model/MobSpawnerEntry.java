package io.github.maaasu.astralRecord.feature.mob.spawner.model;

import org.jetbrains.annotations.NotNull;

/**
 * スポナーが抽選対象にする Mob と重みを表します。
 *
 * @param mobId  Mob テンプレート ID
 * @param weight 抽選重み。1 未満は 1 として扱います
 */
public record MobSpawnerEntry(@NotNull String mobId, int weight) {

    public MobSpawnerEntry {
        if (weight < 1) {
            weight = 1;
        }
    }
}
