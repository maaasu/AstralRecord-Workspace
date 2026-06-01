package io.github.maaasu.astralRecord.feature.adventurerecord.model;

import io.github.maaasu.astralRecord.feature.mob.model.MobCategory;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.UUID;

/**
 * アカウント単位の Mob 討伐記録です。
 *
 * @param recordId 討伐記録 ID
 * @param accountId アカウント ID
 * @param mobId Mob マスタ ID
 * @param category Mob カテゴリ
 * @param defeatCount 討伐数
 * @param firstDefeatedAt 初回討伐日時
 * @param lastDefeatedAt 最新討伐日時
 */
public record AdventureMobRecord(
    @NotNull UUID recordId,
    @NotNull UUID accountId,
    @NotNull String mobId,
    @NotNull MobCategory category,
    long defeatCount,
    @NotNull Instant firstDefeatedAt,
    @NotNull Instant lastDefeatedAt
) {
}
