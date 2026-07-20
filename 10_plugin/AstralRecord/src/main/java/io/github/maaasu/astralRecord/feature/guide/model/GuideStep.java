package io.github.maaasu.astralRecord.feature.guide.model;

import org.jetbrains.annotations.NotNull;

/**
 * ガイド内の順序付き手順です。
 *
 * @param id ガイド内で一意な手順 ID
 * @param text プレイヤーへ表示する説明
 * @param condition 達成条件
 */
public record GuideStep(
    @NotNull String id,
    @NotNull String text,
    @NotNull GuideCondition condition
) {
}
