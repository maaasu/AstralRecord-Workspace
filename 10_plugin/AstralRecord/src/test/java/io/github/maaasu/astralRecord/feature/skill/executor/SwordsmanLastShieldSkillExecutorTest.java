package io.github.maaasu.astralRecord.feature.skill.executor;

import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import io.github.maaasu.astralRecord.feature.skill.model.SkillResourceType;
import io.github.maaasu.astralRecord.feature.skill.service.LastShieldSkillRuntimeService;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class SwordsmanLastShieldSkillExecutorTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 11. ラストシールドの実装契約
     * 検証契約: ラストシールドは正のクールダウンを持つマスタだけを受け付ける。
     */
    @Test
    void validatesPositiveCooldown() {
        SwordsmanLastShieldSkillExecutor executor = new SwordsmanLastShieldSkillExecutor(
                new LastShieldSkillRuntimeService(mock(ParticleDisplayService.class))
        );

        assertDoesNotThrow(() -> executor.validateParams(skill(2400L)));
        assertThrows(SkillParameterException.class, () -> executor.validateParams(skill(0L)));
    }

    private SkillDefinition skill(long cooldownTicks) {
        return new SkillDefinition(
                "swordsman_last_shield",
                "swordsman_last_shield",
                "ラストシールド",
                "シールド破壊を防ぐ防御パッシブ。",
                "SHIELD",
                List.of(),
                cooldownTicks,
                0.0D,
                0L,
                1,
                null,
                Map.of(),
                List.of("passive", "defense"),
                SkillKind.PASSIVE,
                true,
                SkillResourceType.MANA,
                0.0D
        );
    }
}
