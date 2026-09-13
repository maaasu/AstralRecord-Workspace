package io.github.maaasu.astralRecord.feature.skill.executor.active.paladin;

import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillCombatService;
import io.github.maaasu.astralRecord.feature.status.model.HealthRecoveryContext;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PaladinHolyControlRecoveryTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/07-status/3-メソッド仕様/07_3-サービス.md
     * 章・見出し: # 07_3-サービス > ## 1. StatusService メソッド仕様 > ### 回復量への支援力適用
     * 検証契約: ホーリーコントロールのHP/MP回復は発動者を回復元として共通回復処理へ渡す。
     */
    @Test
    void partyRecoveryUsesCasterAsRecoverySource() {
        SkillCombatService combatService = mock(SkillCombatService.class);
        StatusService statusService = mock(StatusService.class);
        AstPlayer caster = mock(AstPlayer.class);
        AstPlayer target = mock(AstPlayer.class);
        HealthRecoveryContext recoveryContext = HealthRecoveryContext.by(caster, "ホーリーコントロール");

        PaladinHolyControlExecutor.recoverPartyMember(
            combatService,
            statusService,
            caster,
            target,
            10.0D,
            20.0D,
            recoveryContext
        );

        verify(combatService).recoverHp(same(target), eq(10.0D), same(recoveryContext));
        verify(statusService).recoverMp(same(target), eq(20.0D), same(caster));
    }
}
