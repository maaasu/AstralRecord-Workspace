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

class GuideProgressEvaluatorTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-サービス.md
     * 章・見出し: # 09_3-サービス > ## 8. ガイド進捗評価
     * 検証契約: 表示順に関係なく、条件を満たした未達成 step を達成対象にする。
     */
    @Test
    void evaluate_AcceptsMatchingStepRegardlessOfDisplayOrder() {
        GuideEntry guide = guide();

        assertEquals(List.of("cast_skill"), ids(GuideProgressEvaluator.evaluate(
            guide,
            Set.of(),
            GuideConditionType.SKILL_CAST,
            "starter_skill"
        )));

        assertEquals(List.of("open_action_ring"), ids(GuideProgressEvaluator.evaluate(
            guide,
            Set.of(),
            GuideConditionType.ACTION_RING_OPENED,
            null
        )));

        assertEquals(List.of("cast_skill"), ids(GuideProgressEvaluator.evaluate(
            guide,
            Set.of(new GuideStepKey(guide.id(), "open_action_ring")),
            GuideConditionType.SKILL_CAST,
            "starter_skill"
        )));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-サービス.md
     * 章・見出し: # 09_3-サービス > ## 8. ガイド進捗評価
     * 検証契約: guide stepにtargetIdがある場合だけ対象IDまで一致させる。
     */
    @Test
    void evaluate_AppliesOptionalTargetId() {
        GuideEntry guide = new GuideEntry(
            3,
            "specific_skill",
            "skill",
            10,
            "specific",
            null,
            null,
            List.of(new GuideStep(
                "cast",
                "cast",
                List.of(),
                new GuideCondition(GuideConditionType.SKILL_CAST, "fire_boost"),
                null
            ))
        );

        assertEquals(List.of(), GuideProgressEvaluator.evaluate(
            guide,
            Set.of(),
            GuideConditionType.SKILL_CAST,
            "other_skill"
        ));
        assertEquals(List.of("cast"), ids(GuideProgressEvaluator.evaluate(
            guide,
            Set.of(),
            GuideConditionType.SKILL_CAST,
            "fire_boost"
        )));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-サービス.md
     * 章・見出し: # 09_3-サービス > ## 8. ガイド進捗評価
     * 検証契約: MOB_DEFEATED条件は対象Mob IDに加えて指定レベルまで一致させる。
     */
    @Test
    void evaluate_AppliesOptionalMobLevel() {
        GuideEntry guide = new GuideEntry(
            3,
            "specific_mob_level",
            "beginner",
            10,
            "specific",
            null,
            null,
            List.of(new GuideStep(
                "kill_level_two",
                "kill",
                List.of(),
                new GuideCondition(GuideConditionType.MOB_DEFEATED, "grassboar", 2),
                null
            ))
        );

        assertEquals(List.of(), GuideProgressEvaluator.evaluate(
            guide, Set.of(), GuideConditionType.MOB_DEFEATED, "grassboar", 1
        ));
        assertEquals(List.of("kill_level_two"), ids(GuideProgressEvaluator.evaluate(
            guide, Set.of(), GuideConditionType.MOB_DEFEATED, "grassboar", 2
        )));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-サービス.md
     * 章・見出し: # 09_3-サービス > ## 8. ガイド進捗評価
     * 検証契約: 同一イベントに一致する未達成 step は、表示順に関係なくすべて達成対象にする。
     */
    @Test
    void evaluate_ReturnsEveryMatchingUncompletedStep() {
        GuideEntry guide = new GuideEntry(
            3,
            "duplicate_condition",
            "guide",
            10,
            "guide",
            null,
            null,
            List.of(
                new GuideStep(
                    "first",
                    "first",
                    List.of(),
                    new GuideCondition(GuideConditionType.SKILL_CAST, "starter_skill"),
                    null
                ),
                new GuideStep(
                    "second",
                    "second",
                    List.of(),
                    new GuideCondition(GuideConditionType.SKILL_CAST, "starter_skill"),
                    null
                )
            )
        );

        assertEquals(List.of("first", "second"), ids(GuideProgressEvaluator.evaluate(
            guide,
            Set.of(),
            GuideConditionType.SKILL_CAST,
            "starter_skill"
        )));
        assertEquals(List.of("second"), ids(GuideProgressEvaluator.evaluate(
            guide,
            Set.of(new GuideStepKey(guide.id(), "first")),
            GuideConditionType.SKILL_CAST,
            "starter_skill"
        )));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-サービス.md
     * 章・見出し: # 09_3-サービス > ## 8. ガイド進捗評価
     * 検証契約: onboarding条件は種別と対象IDの両方が一致した成功イベントだけを受理する。
     */
    @Test
    void evaluate_AcceptsOnboardingConditionWithSpawnerTarget() {
        GuideEntry guide = new GuideEntry(
            3,
            "nox_gathering",
            "world",
            10,
            "gather",
            null,
            null,
            List.of(new GuideStep(
                "gather_at_nox",
                "gather",
                List.of(),
                new GuideCondition(GuideConditionType.GATHERING_COMPLETED, "nox_flora_spawner"),
                null
            ))
        );

        assertEquals(List.of(), GuideProgressEvaluator.evaluate(
            guide,
            Set.of(),
            GuideConditionType.MOB_DEFEATED,
            "midgard_grassboar"
        ));
        assertEquals(List.of(), GuideProgressEvaluator.evaluate(
            guide,
            Set.of(),
            GuideConditionType.GATHERING_COMPLETED,
            "other_spawner"
        ));
        assertEquals(List.of("gather_at_nox"), ids(GuideProgressEvaluator.evaluate(
            guide,
            Set.of(),
            GuideConditionType.GATHERING_COMPLETED,
            "nox_flora_spawner"
        )));
    }

    private GuideEntry guide() {
        return new GuideEntry(
            3,
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
                    List.of(),
                    new GuideCondition(GuideConditionType.ACTION_RING_OPENED, null),
                    null
                ),
                new GuideStep(
                    "cast_skill",
                    "cast",
                    List.of(),
                    new GuideCondition(GuideConditionType.SKILL_CAST, null),
                    null
                )
            )
        );
    }

    private List<String> ids(List<GuideStep> steps) {
        return steps.stream().map(GuideStep::id).toList();
    }
}
