package io.github.maaasu.astralRecord.feature.guide.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * ガイド内に表示する手順です。表示順と達成判定の順序は独立しています。
 *
 * @param id ガイド内で一意な手順 ID
 * @param text プレイヤーへ表示する説明
 * @param details 操作方法の詳細説明
 * @param condition 達成条件
 * @param action クリック時の案内アクション
 */
public record GuideStep(
    @NotNull String id,
    @NotNull String text,
    @NotNull List<String> details,
    @NotNull GuideCondition condition,
    @Nullable GuideAction action
) {
    public GuideStep {
        details = details == null ? List.of() : List.copyOf(details);
    }
}
