package io.github.maaasu.astralRecord.feature.skill.executor;

import io.github.maaasu.astralRecord.feature.skill.model.SkillCastContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastTrigger;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCaster;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import org.junit.jupiter.api.Test;
import org.bukkit.Location;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;

class SwordsmanShieldActivateSkillExecutorTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## シールドアクティベートの実装契約
     * 検証契約: シールドアクティベートはPASSIVEとして登録でき、直接castでは効果を発生させない。
     */
    @Test
    void isPassiveMarkerAndCannotBeCast() {
        SwordsmanShieldActivateSkillExecutor executor = new SwordsmanShieldActivateSkillExecutor();

        assertEquals(SwordsmanShieldActivateSkillExecutor.ID, executor.implementationId());
        assertEquals(SkillKind.PASSIVE, executor.kind());
        SkillCastContext context = new SkillCastContext(
            new SkillDefinition(
                SwordsmanShieldActivateSkillExecutor.ID,
                SwordsmanShieldActivateSkillExecutor.ID,
                "シールドアクティベート",
                null,
                null,
                List.of(),
                0L,
                0.0D,
                0L,
                1,
                null
            ),
            mock(SkillCaster.class),
            null,
            List.of(),
            new Location(null, 0.0D, 0.0D, 0.0D),
            StatusSnapshot.empty(),
            SkillCastTrigger.SYSTEM,
            Instant.EPOCH
        );
        SkillCastResult result = executor.cast(context);
        assertFalse(result.success());
    }
}
