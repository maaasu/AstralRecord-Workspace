package io.github.maaasu.astralRecord.feature.skill.executor;

import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
import io.github.maaasu.astralRecord.feature.skill.model.SkillLevelDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import io.github.maaasu.astralRecord.feature.skill.model.SkillResourceType;
import io.github.maaasu.astralRecord.feature.skill.service.ArcaneFlowSkillRuntimeService;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class MageArcaneFlowSkillExecutorTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 25. メイジ アーケインフローの実装契約
     * 検証契約: executorはPASSIVEかつ最大Lv.5、Lv.1 5%と各レベル+1.25%の固定定義だけを受け付ける。
     */
    @Test
    void validatesFixedFiveLevelProgression() {
        MageArcaneFlowSkillExecutor executor = new MageArcaneFlowSkillExecutor(
                new ArcaneFlowSkillRuntimeService(mock(ParticleDisplayService.class))
        );

        assertEquals(MageArcaneFlowSkillExecutor.ID, executor.implementationId());
        assertEquals(SkillKind.PASSIVE, executor.kind());
        assertDoesNotThrow(() -> executor.validateParams(definition(5, 5.0D, 1.25D)));
        assertThrows(SkillParameterException.class, () -> executor.validateParams(definition(4, 5.0D, 1.25D)));
        assertThrows(SkillParameterException.class, () -> executor.validateParams(definition(5, 4.0D, 1.25D)));
        assertThrows(SkillParameterException.class, () -> executor.validateParams(definition(5, 5.0D, 1.0D)));
    }

    private static SkillDefinition definition(int maxLevel, double base, double delta) {
        List<SkillLevelDefinition> levels = List.of(
                level(2, delta),
                level(3, delta),
                level(4, delta),
                level(5, delta)
        );
        return new SkillDefinition(
                MageArcaneFlowSkillExecutor.ID,
                MageArcaneFlowSkillExecutor.ID,
                "アーケインフロー",
                null,
                "AMETHYST_SHARD",
                List.of(),
                0L,
                0.0D,
                0L,
                1,
                null,
                Map.of("castTimeReductionPercent", base),
                List.of("passive", "magic"),
                SkillKind.PASSIVE,
                true,
                SkillResourceType.MANA,
                0.0D,
                null,
                maxLevel,
                levels,
                List.of(),
                List.of()
        );
    }

    private static SkillLevelDefinition level(int level, double delta) {
        return new SkillLevelDefinition(
                level,
                0L,
                0.0D,
                0L,
                Map.of("castTimeReductionPercent", delta),
                List.of()
        );
    }
}
