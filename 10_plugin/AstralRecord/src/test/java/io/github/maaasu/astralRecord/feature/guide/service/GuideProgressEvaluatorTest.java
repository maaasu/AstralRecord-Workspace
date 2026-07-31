package io.github.maaasu.astralRecord.feature.guide.service;

import io.github.maaasu.astralRecord.feature.guide.model.GuideCondition;
import io.github.maaasu.astralRecord.feature.guide.model.GuideConditionType;
import io.github.maaasu.astralRecord.feature.guide.model.GuideEntry;
import io.github.maaasu.astralRecord.feature.guide.model.GuideStep;
import io.github.maaasu.astralRecord.feature.guide.model.GuideStepKey;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class GuideProgressEvaluatorTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-サービス.md
     * 章・見出し: # 09_3-サービス > ## 8. ガイド進捗評価
     * 検証契約: guide requires stepをmaster記載順に全件評価する。
     */
    @Test
    void evaluate_RequiresStepsInMasterOrder() {
        GuideEntry guide = guide();

        assertNull(GuideProgressEvaluator.evaluate(
            guide,
            Set.of(),
            GuideConditionType.SKILL_CAST,
            "starter_skill"
        ));

        GuideStep first = GuideProgressEvaluator.evaluate(
            guide,
            Set.of(),
            GuideConditionType.ACTION_RING_OPENED,
            null
        );
        assertEquals("open_action_ring", first.id());

        GuideStep second = GuideProgressEvaluator.evaluate(
            guide,
            Set.of(new GuideStepKey(guide.id(), first.id())),
            GuideConditionType.SKILL_CAST,
            "starter_skill"
        );
        assertEquals("cast_skill", second.id());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-サービス.md
     * 章・見出し: # 09_3-サービス > ## 8. ガイド進捗評価
     * 検証契約: guide stepにtargetIdがある場合だけ対象IDまで一致させる。
     */
    @Test
    void evaluate_AppliesOptionalTargetId() {
        GuideEntry guide = new GuideEntry(
            2,
            "specific_skill",
            "skill",
            10,
            "specific",
            null,
            null,
            List.of(new GuideStep(
                "cast",
                "cast",
                new GuideCondition(GuideConditionType.SKILL_CAST, "fire_boost")
            ))
        );

        assertNull(GuideProgressEvaluator.evaluate(
            guide,
            Set.of(),
            GuideConditionType.SKILL_CAST,
            "other_skill"
        ));
        assertEquals("cast", GuideProgressEvaluator.evaluate(
            guide,
            Set.of(),
            GuideConditionType.SKILL_CAST,
            "fire_boost"
        ).id());
    }

    private GuideEntry guide() {
        return new GuideEntry(
            2,
            "action_ring_skill_cast",
            "skill",
            10,
            "guide",
            null,
            null,
            List.of(
                new GuideStep(
                    "open_action_ring",
                    "open",
                    new GuideCondition(GuideConditionType.ACTION_RING_OPENED, null)
                ),
                new GuideStep(
                    "cast_skill",
                    "cast",
                    new GuideCondition(GuideConditionType.SKILL_CAST, null)
                )
            )
        );
    }
}
