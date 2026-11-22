package io.github.maaasu.astralRecord.feature.skill.service;

import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SkillDamagePreviewTest {
    /** 攻撃タグや倍率名があるだけの非攻撃・未定義効果から攻撃ビルドを評価しない。 */
    @Test
    void neverInfersAnAttackFromTagsOrStandaloneParameters() {
        for (SkillKind kind : SkillKind.values()) {
            var skill = mock(SkillDefinition.class);
            when(skill.getId()).thenReturn("fixture");
            when(skill.getImplementationId()).thenReturn("fixture_effect");
            when(skill.getKind()).thenReturn(kind);
            when(skill.getTags()).thenReturn(List.of("magic", "support"));
            when(skill.getParams()).thenReturn(Map.of("damageRatio", 2.0));
            var stats = mock(StatusSnapshot.class);
            SkillDamagePreview.describe(stats, skill, 40, 0, 0);
            verifyNoInteractions(stats);
        }
    }
}
