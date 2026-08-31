package io.github.maaasu.astralRecord.feature.skill.executor;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.model.PassiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
import io.github.maaasu.astralRecord.feature.skill.model.SkillResourceType;
import io.github.maaasu.astralRecord.feature.skill.service.PassiveSkillService;
import io.github.maaasu.astralRecord.feature.status.model.ShieldRechargeState;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class AdministratorShieldRechargeSkillExecutorTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/14-combat/14_0-概要.md
     * 章・見出し: # 14_0-概要 > ## 5. 固定HPダメージとShield
     * 検証契約: 固定したrechargeRate=2%のスキル定義がexecutor経由で実行時の最大Shield30・毎秒0.6へ反映される。
     */
    @Test
    void configuredRechargeRateReachesStatusRuntime() {
        StatusService statusService = activatedStatusService();
        AstPlayer player = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.ADMIN);
        player.setStatusSnapshot(shieldSnapshot(30.0D, 10.0D));

        AdministratorShieldRechargeSkillExecutor executor = new AdministratorShieldRechargeSkillExecutor(
            statusService,
            mock(ParticleDisplayService.class)
        );
        executor.onActivate(new PassiveSkillContext(
            player,
            definition(),
            Instant.EPOCH,
            0L
        ));

        ShieldRechargeState state = statusService.startShieldRechargeWhileRetained(player, 1_000L);

        assertNotNull(state);
        assertEquals(9_000L, state.completesAtMs());
        assertEquals(0.6D, state.rechargeAmount(), 0.0001D);
        assertTrue(statusService.completeShieldRechargeIfReady(player, 9_000L));
        assertEquals(10.6D, player.getStatusSnapshot().getCurrentShield(), 0.0001D);
        assertNotNull(statusService.getShieldRechargeState(player));

        for (int second = 1; second < 34; second++) {
            assertTrue(statusService.completeShieldRechargeIfReady(player, 9_000L + second * 1_000L));
        }
        assertEquals(30.0D, player.getStatusSnapshot().getCurrentShield(), 0.0001D);
        assertNull(statusService.getShieldRechargeState(player));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/14-combat/14_0-概要.md
     * 章・見出し: # 14_0-概要 > ## 5. 固定HPダメージとShield
     * 検証契約: 管理者向けスキルを有効にしていても、破壊後は通常の30秒全回復となり、スキル演出を出さない。
     */
    @Test
    void brokenShieldUsesFullRecoveryEvenWithSkillConfigured() {
        StatusService statusService = activatedStatusService();
        ParticleDisplayService particleDisplayService = mock(ParticleDisplayService.class);
        AstPlayer player = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.ADMIN);
        player.setStatusSnapshot(shieldSnapshot(30.0D));

        AdministratorShieldRechargeSkillExecutor executor = new AdministratorShieldRechargeSkillExecutor(
            statusService,
            particleDisplayService
        );
        executor.onActivate(new PassiveSkillContext(
            player,
            definition(),
            Instant.EPOCH,
            0L
        ));

        ShieldRechargeState state = statusService.startShieldRecharge(player, 1_000L);
        executor.onTick(new PassiveSkillContext(
            player,
            definition(),
            Instant.EPOCH,
            10L
        ));

        assertEquals(31_000L, state.completesAtMs());
        assertEquals(30.0D, state.rechargeAmount(), 0.0001D);
        assertEquals(false, state.incrementalRecovery());
        verify(particleDisplayService, never()).spawnForNearbyViewers(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyList(),
            org.mockito.ArgumentMatchers.any()
        );
    }

    private static SkillDefinition definition() {
        return new SkillDefinition(
            AdministratorShieldRechargeSkillExecutor.ID,
            AdministratorShieldRechargeSkillExecutor.ID,
            "シールドリチャージ",
            null,
            "SHIELD",
            List.of(),
            0L,
            0.0D,
            0L,
            1,
            null,
            Map.of(
                "maxShield", 30.0D,
                "rechargeDelaySeconds", 8.0D,
                "rechargePercentPerSecond", 2.0D,
                "particleIntervalTicks", 10.0D
            ),
            List.of("passive", "defense"),
            SkillKind.PASSIVE,
            true,
            SkillResourceType.MANA,
            0.0D
        );
    }

    private static StatusSnapshot shieldSnapshot(double maxShield) {
        return shieldSnapshot(maxShield, 0.0D);
    }

    private static StatusSnapshot shieldSnapshot(double maxShield, double currentShield) {
        return DesignTestFixtures.statusSnapshot(Map.of(
            StatusType.MAX_HEALTH, 100.0D,
            StatusType.MAX_SHIELD, maxShield
        ), 100.0D, 0.0D, 0.0D).withCurrentShield(currentShield);
    }

    private StatusService activatedStatusService() {
        StatusService statusService = new StatusService();
        PassiveSkillService passiveSkillService = mock(PassiveSkillService.class);
        org.mockito.Mockito.when(passiveSkillService.isPassiveSkillActive(
            org.mockito.ArgumentMatchers.any(AstPlayer.class),
            org.mockito.ArgumentMatchers.eq(StatusService.SHIELD_ACTIVATE_SKILL_ID)
        )).thenReturn(true);
        statusService.setPassiveSkillService(passiveSkillService);
        return statusService;
    }
}
