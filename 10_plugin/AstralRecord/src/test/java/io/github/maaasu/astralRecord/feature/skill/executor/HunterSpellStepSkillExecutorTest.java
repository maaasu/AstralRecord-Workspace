package io.github.maaasu.astralRecord.feature.skill.executor;

import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import io.github.maaasu.astralRecord.feature.skill.model.SkillResourceType;
import io.github.maaasu.astralRecord.feature.skill.service.SpellStepSkillRuntimeService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HunterSpellStepSkillExecutorTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 20. スペルステップの実装契約
     * 検証契約: スペルステップexecutorはPASSIVEとして固定20tick、sound key、正の音程と0以上の音量を持つ定義だけを受け付ける。
     */
    @Test
    void validatesPassiveParameters() {
        HunterSpellStepSkillExecutor executor = new HunterSpellStepSkillExecutor(
                new SpellStepSkillRuntimeService()
        );

        assertEquals(HunterSpellStepSkillExecutor.ID, executor.implementationId());
        assertEquals(SkillKind.PASSIVE, executor.kind());
        assertDoesNotThrow(() -> executor.validateParams(skill(Map.of(
                "windowTicks", 20,
                "triggerSound", "block.beacon.power_select",
                "triggerSoundVolume", 0.8D,
                "triggerSoundPitch", 1.3D
        ))));
        assertThrows(SkillParameterException.class, () -> executor.validateParams(skill(Map.of(
                "windowTicks", 19,
                "triggerSound", "block.beacon.power_select",
                "triggerSoundVolume", 0.8D,
                "triggerSoundPitch", 1.3D
        ))));
        assertThrows(SkillParameterException.class, () -> executor.validateParams(skill(Map.of(
                "windowTicks", 20,
                "triggerSound", "",
                "triggerSoundVolume", 0.8D,
                "triggerSoundPitch", 1.3D
        ))));
        assertThrows(SkillParameterException.class, () -> executor.validateParams(skill(Map.of(
                "windowTicks", 20,
                "triggerSound", "block.beacon.power_select",
                "triggerSoundVolume", -0.1D,
                "triggerSoundPitch", 1.3D
        ))));
        assertThrows(SkillParameterException.class, () -> executor.validateParams(skill(Map.of(
                "windowTicks", 20,
                "triggerSound", "block.beacon.power_select",
                "triggerSoundVolume", 0.8D,
                "triggerSoundPitch", 0.0D
        ))));
    }

    private static SkillDefinition skill(Map<String, Object> params) {
        return new SkillDefinition(
                HunterSpellStepSkillExecutor.ID,
                HunterSpellStepSkillExecutor.ID,
                "スペルステップ",
                "遠隔スキルから身をかわすパッシブ。",
                "FEATHER",
                List.of(),
                0L,
                0.0D,
                0L,
                1,
                null,
                params,
                List.of("passive", "windwait"),
                SkillKind.PASSIVE,
                true,
                SkillResourceType.MANA,
                0.0D
        );
    }
}
