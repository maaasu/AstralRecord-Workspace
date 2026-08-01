package io.github.maaasu.astralRecord.feature.skill.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PassiveSkillServiceTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## パッシブスロット
     * 検証契約: 基本5枠へPASSIVE_SKILL_SLOTSを1値1枠で加え、負数を無視し最大9枠に制限する。
     */
    @Test
    void activePassiveSlotCountUsesBaseFiveAndCapsStatusBonusAtNine() {
        PassiveSkillService service = new PassiveSkillService(
            mock(AstralRecord.class),
            mock(SkillService.class),
            mock(SkillBindPresetService.class),
            mock(SkillOwnershipService.class),
            mock(SkillPermissionService.class),
            mock(LearnedSkillResolver.class)
        );
        AstPlayer player = mock(AstPlayer.class);
        StatusSnapshot snapshot = mock(StatusSnapshot.class);
        when(player.getStatusSnapshot()).thenReturn(snapshot);
        when(snapshot.getMaxValue(StatusType.PASSIVE_SKILL_SLOTS)).thenReturn(-1.0D, 2.9D, 99.0D);

        assertEquals(5, service.activePassiveSlotCount(player));
        assertEquals(7, service.activePassiveSlotCount(player));
        assertEquals(9, service.activePassiveSlotCount(player));
    }
}
