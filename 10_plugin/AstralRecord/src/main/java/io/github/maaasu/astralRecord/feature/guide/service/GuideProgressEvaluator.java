package io.github.maaasu.astralRecord.feature.guide.service;

import io.github.maaasu.astralRecord.feature.guide.model.GuideConditionType;
import io.github.maaasu.astralRecord.feature.guide.model.GuideEntry;
import io.github.maaasu.astralRecord.feature.guide.model.GuideStep;
import io.github.maaasu.astralRecord.feature.guide.model.GuideStepKey;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * ガイドの未達成 step を副作用なしで評価します。
 */
final class GuideProgressEvaluator {
    private GuideProgressEvaluator() {
    }

    /**
     * 未達成で、発生イベントに一致するすべての手順を返します。
     *
     * @param guide 対象ガイド
     * @param completed 完了済み手順
     * @param eventType 発生イベント種別
     * @param targetId 発生イベントの対象ID
     * @return 達成対象手順。一致しない場合は空リスト
     */
    static @NotNull List<GuideStep> evaluate(
        @NotNull GuideEntry guide,
        @NotNull Set<GuideStepKey> completed,
        @NotNull GuideConditionType eventType,
        @Nullable String targetId
    ) {
        List<GuideStep> matched = new ArrayList<>();
        for (GuideStep step : guide.steps()) {
            if (completed.contains(new GuideStepKey(guide.id(), step.id()))) {
                continue;
            }
            if (step.condition().matches(eventType, targetId)) {
                matched.add(step);
            }
        }
        return List.copyOf(matched);
    }
}
