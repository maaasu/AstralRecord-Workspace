package io.github.maaasu.astralRecord.feature.guide.model;

import org.jetbrains.annotations.NotNull;

/**
 * アカウントの完了済みガイド手順を識別するキーです。
 *
 * @param guideId ガイド ID
 * @param stepId 手順 ID
 */
public record GuideStepKey(
    @NotNull String guideId,
    @NotNull String stepId
) {
}
