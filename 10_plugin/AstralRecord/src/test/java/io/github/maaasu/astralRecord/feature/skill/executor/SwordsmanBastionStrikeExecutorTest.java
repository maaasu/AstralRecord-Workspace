package io.github.maaasu.astralRecord.feature.skill.executor;

import io.github.maaasu.astralRecord.feature.skill.model.SkillCastContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import io.github.maaasu.astralRecord.feature.skill.model.SkillResourceType;
import io.github.maaasu.astralRecord.feature.skill.service.BastionStrikeSkillRuntimeService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class SwordsmanBastionStrikeExecutorTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 13. バスティオンストライクの実装契約
     * 検証契約: バスティオンストライクはパッシブとして登録され、直接castでは効果を発生させない。
     */
    @Test
    void isPassiveAndDirectCastIsDisabled() {
        SwordsmanBastionStrikeExecutor executor = new SwordsmanBastionStrikeExecutor(
                mock(BastionStrikeSkillRuntimeService.class)
        );

        assertEquals(SkillKind.PASSIVE, executor.kind());
        assertFalse(executor.cast(null).success());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 13. バスティオンストライクの実装契約 > ### 13.1 数値・対象・演出
     * 検証契約: 正のクールダウン、射程、反撃倍率を持つパッシブ定義だけを受け付ける。
     */
    @Test
    void validatesPassiveCombatParams() {
        SwordsmanBastionStrikeExecutor executor = new SwordsmanBastionStrikeExecutor(
                mock(BastionStrikeSkillRuntimeService.class)
        );

        assertDoesNotThrow(() -> executor.validateParams(definition(3000L, 6.0D, 1.875D, SkillKind.PASSIVE)));

        SkillParameterException cooldownException = assertThrows(
                SkillParameterException.class,
                () -> executor.validateParams(definition(0L, 6.0D, 1.875D, SkillKind.PASSIVE))
        );
        assertEquals("cooldownTicks", cooldownException.key());

        SkillParameterException rangeException = assertThrows(
                SkillParameterException.class,
                () -> executor.validateParams(definition(3000L, 0.0D, 1.875D, SkillKind.PASSIVE))
        );
        assertEquals("range", rangeException.key());

        SkillParameterException ratioException = assertThrows(
                SkillParameterException.class,
                () -> executor.validateParams(definition(3000L, 6.0D, 0.0D, SkillKind.PASSIVE))
        );
        assertEquals("damageRatio", ratioException.key());
    }

    private static SkillDefinition definition(
            long cooldownTicks,
            double range,
            double damageRatio,
            SkillKind kind
    ) {
        return new SkillDefinition(
                SwordsmanBastionStrikeExecutor.ID,
                SwordsmanBastionStrikeExecutor.ID,
                "バスティオンストライク",
                "攻撃を受け止めて返す近接反撃。",
                "SOUL_CAMPFIRE",
                List.of(),
                cooldownTicks,
                0.0D,
                0L,
                1,
                null,
                Map.of("range", range, "damageRatio", damageRatio),
                List.of("passive", "melee", "defense"),
                kind,
                true,
                SkillResourceType.MANA,
                0.0D
        );
    }
}
