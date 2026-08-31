package io.github.maaasu.astralRecord.feature.skill.active.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.service.DamageService;
import io.github.maaasu.astralRecord.feature.condition.service.ConditionService;
import io.github.maaasu.astralRecord.feature.mob.service.MobKnockbackService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.service.PassiveSkillService;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SkillCombatServiceShieldRecoveryTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 10. シールドドレインの実装契約 > ### 10.1 数値・対象・演出
     * 検証契約: Shield吸収要求が上限を超える場合、実StatusService経路はMAX_SHIELDまで加算し実増加差分だけを返す。
     */
    @Test
    void returnsActualShieldIncreaseAfterMaximumCap() {
        StatusService statusService = activatedStatusService();
        SkillCombatService combat = combat(statusService, mock(ConditionService.class));
        AstPlayer player = playerWithShield(80.0D, 100.0D);

        double recovered = combat.recoverShield(AstEntity.player(player), 50.0D);

        assertEquals(20.0D, recovered, 0.0001D);
        assertEquals(100.0D, player.getStatusSnapshot().getCurrentShield(), 0.0001D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 10. シールドドレインの実装契約 > ### 10.1 数値・対象・演出
     * 検証契約: 回復阻害中のShield吸収は実StatusService経路で現在値を変更せず実増加量0を返す。
     */
    @Test
    void returnsZeroWhenHealingIsBlocked() {
        ConditionService conditionService = mock(ConditionService.class);
        when(conditionService.isHealingBlocked(any(AstEntity.class))).thenReturn(true);
        StatusService statusService = activatedStatusService();
        statusService.setConditionService(conditionService);
        SkillCombatService combat = combat(statusService, conditionService);
        AstPlayer player = playerWithShield(40.0D, 100.0D);

        double recovered = combat.recoverShield(AstEntity.player(player), 20.0D);

        assertEquals(0.0D, recovered, 0.0001D);
        assertEquals(40.0D, player.getStatusSnapshot().getCurrentShield(), 0.0001D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 19. ヒールアローの実装契約 > ### 19.1 数値・対象・終端
     * 検証契約: ヒールアローのHP回復はStatusService経路を通り、最大HPを超えた要求では実増加量だけを返す。
     */
    @Test
    void returnsActualHpIncreaseAfterMaximumCap() {
        StatusService statusService = new StatusService();
        SkillCombatService combat = combat(statusService, mock(ConditionService.class));
        AstPlayer player = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.PLAYER);
        StatusSnapshot snapshot = DesignTestFixtures.statusSnapshot(Map.of(
                StatusType.MAX_HEALTH, 100.0D
        ), 100.0D, 0.0D, 0.0D).withCurrentValues(80.0D, 0.0D);
        player.setStatusSnapshot(snapshot);

        double recovered = combat.recoverHp(player, 50.0D);

        assertEquals(20.0D, recovered, 0.0001D);
        assertEquals(100.0D, player.getStatusSnapshot().getCurrentHp(), 0.0001D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 19. ヒールアローの実装契約 > ### 19.1 数値・対象・終端
     * 検証契約: ヒールアローのHP回復は回復阻害中にStatusServiceが現在HPを変更せず、実増加量0を返す。
     */
    @Test
    void returnsZeroWhenHpHealingIsBlocked() {
        ConditionService conditionService = mock(ConditionService.class);
        when(conditionService.isHealingBlocked(any(AstEntity.class))).thenReturn(true);
        StatusService statusService = new StatusService();
        statusService.setConditionService(conditionService);
        SkillCombatService combat = combat(statusService, conditionService);
        AstPlayer player = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.PLAYER);
        StatusSnapshot snapshot = DesignTestFixtures.statusSnapshot(Map.of(
                StatusType.MAX_HEALTH, 100.0D
        ), 100.0D, 0.0D, 0.0D).withCurrentValues(60.0D, 0.0D);
        player.setStatusSnapshot(snapshot);

        double recovered = combat.recoverHp(player, 20.0D);

        assertEquals(0.0D, recovered, 0.0001D);
        assertEquals(60.0D, player.getStatusSnapshot().getCurrentHp(), 0.0001D);
    }

    private AstPlayer playerWithShield(double currentShield, double maximumShield) {
        AstPlayer player = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.PLAYER);
        StatusSnapshot snapshot = DesignTestFixtures.statusSnapshot(Map.of(
                StatusType.MAX_HEALTH, 100.0D,
                StatusType.MAX_SHIELD, maximumShield
        ), 100.0D, 0.0D, 0.0D).withCurrentShield(currentShield);
        player.setStatusSnapshot(snapshot);
        return player;
    }

    private SkillCombatService combat(StatusService statusService, ConditionService conditionService) {
        return new SkillCombatService(
                mock(DamageService.class),
                conditionService,
                mock(MobKnockbackService.class),
                statusService
        );
    }

    private StatusService activatedStatusService() {
        StatusService statusService = new StatusService();
        PassiveSkillService passiveSkillService = mock(PassiveSkillService.class);
        when(passiveSkillService.isPassiveSkillActive(
                any(AstPlayer.class),
                org.mockito.ArgumentMatchers.eq(StatusService.SHIELD_ACTIVATE_SKILL_ID)
        )).thenReturn(true);
        statusService.setPassiveSkillService(passiveSkillService);
        return statusService;
    }
}
