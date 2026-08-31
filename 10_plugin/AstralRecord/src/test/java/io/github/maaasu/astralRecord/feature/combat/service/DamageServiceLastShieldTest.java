package io.github.maaasu.astralRecord.feature.combat.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageComponent;
import io.github.maaasu.astralRecord.feature.combat.model.DamageResult;
import io.github.maaasu.astralRecord.feature.combat.model.DamageSource;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionType;
import io.github.maaasu.astralRecord.feature.mob.service.MobCombatService;
import io.github.maaasu.astralRecord.feature.mob.service.MobKnockbackService;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.playersetting.service.PlayerSettingService;
import io.github.maaasu.astralRecord.feature.skill.model.PassiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
import io.github.maaasu.astralRecord.feature.skill.model.SkillResourceType;
import io.github.maaasu.astralRecord.feature.skill.service.LastShieldSkillRuntimeService;
import io.github.maaasu.astralRecord.feature.skill.service.PassiveSkillService;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.shared.display.DisplayTextService;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DamageServiceLastShieldTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 14. ラストシールドの実装契約
     * 検証契約: シールドを破壊する直接攻撃だけを1回無効化し、クールダウン中は通常のシールド破壊経路へ戻す。
     */
    @Test
    void shieldBreakingDirectDamageIsNegatedOnceThenRespectsCooldown() {
        StatusService statusService = activatedStatusService();
        ParticleDisplayService particleDisplayService = mock(ParticleDisplayService.class);
        DamageService damageService = damageService(statusService, particleDisplayService);
        LastShieldSkillRuntimeService runtime = new LastShieldSkillRuntimeService(particleDisplayService);
        damageService.setLastShieldSkillRuntimeService(runtime);

        AstPlayer attacker = attacker();
        AstPlayer victim = shieldedVictim();
        runtime.activate(passiveContext(victim, 2400L));

        PlayerMessageService messageService = mock(PlayerMessageService.class);
        DamageResult negated;
        try (MockedStatic<PlayerMessageService> messages = mockStatic(PlayerMessageService.class)) {
            messages.when(PlayerMessageService::getInstance).thenReturn(messageService);
            negated = attack(damageService, attacker, victim, DamageSource.NORMAL_ATTACK);
            verify(messageService).send(victim.getBukkit(), PlayerMsgId.P_5880);
        }

        assertEquals(0.0D, negated.finalDamage(), 0.0001D);
        assertEquals(0.0D, negated.shieldDamage(), 0.0001D);
        assertFalse(negated.shieldBroken());
        assertEquals(1.0D, victim.getStatusSnapshot().getCurrentShield(), 0.0001D);
        assertEquals(100.0D, victim.getStatusSnapshot().getCurrentHp(), 0.0001D);
        verify(particleDisplayService).spawnForNearbyViewers(
                any(org.bukkit.Location.class),
                eq(SharedParticleDefinitions.LAST_SHIELD_ACTIVATION_TOTEM)
        );
        verify(particleDisplayService).spawnForNearbyViewers(
                any(org.bukkit.Location.class),
                eq(SharedParticleDefinitions.LAST_SHIELD_ACTIVATION_FLASH)
        );

        DamageResult cooldownHit = attack(damageService, attacker, victim, DamageSource.SKILL);

        assertTrue(cooldownHit.shieldBroken());
        assertEquals(1.0D, cooldownHit.shieldDamage(), 0.0001D);
        assertEquals(0.0D, victim.getStatusSnapshot().getCurrentShield(), 0.0001D);
        assertEquals(100.0D, victim.getStatusSnapshot().getCurrentHp(), 0.0001D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 14. ラストシールドの実装契約
     * 検証契約: 状態異常DoTはラストシールドの無効化対象に含めない。
     */
    @Test
    void conditionDotIsNotNegated() {
        StatusService statusService = activatedStatusService();
        ParticleDisplayService particleDisplayService = mock(ParticleDisplayService.class);
        DamageService damageService = damageService(statusService, particleDisplayService);
        LastShieldSkillRuntimeService runtime = new LastShieldSkillRuntimeService(particleDisplayService);
        damageService.setLastShieldSkillRuntimeService(runtime);
        AstPlayer victim = shieldedVictim();
        runtime.activate(passiveContext(victim, 2400L));

        DamageResult result = damageService.applyConditionDamage(
                null,
                AstEntity.player(victim),
                5.0D,
                ConditionType.POISON
        );

        assertEquals(5.0D, result.finalDamage(), 0.0001D);
        assertEquals(1.0D, victim.getStatusSnapshot().getCurrentShield(), 0.0001D);
        assertEquals(95.0D, victim.getStatusSnapshot().getCurrentHp(), 0.0001D);
    }

    private DamageService damageService(
            StatusService statusService,
            ParticleDisplayService particleDisplayService
    ) {
        return new DamageService(
                statusService,
                mock(MobService.class),
                mock(MobCombatService.class),
                mock(MobKnockbackService.class),
                mock(DisplayTextService.class),
                mock(PlayerSettingService.class),
                particleDisplayService
        );
    }

    private StatusService activatedStatusService() {
        StatusService statusService = new StatusService();
        PassiveSkillService passiveSkillService = mock(PassiveSkillService.class);
        when(passiveSkillService.isPassiveSkillActive(
                any(AstPlayer.class),
                eq(StatusService.SHIELD_ACTIVATE_SKILL_ID)
        )).thenReturn(true);
        statusService.setPassiveSkillService(passiveSkillService);
        return statusService;
    }

    private AstPlayer attacker() {
        AstPlayer attacker = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.ADMIN);
        attacker.setStatusSnapshot(DesignTestFixtures.statusSnapshot(Map.of(
                StatusType.MAX_HEALTH, 100.0D,
                StatusType.ATTACK, 10.0D,
                StatusType.ACCURACY, 100.0D,
                StatusType.CRITICAL_RATE, 0.0D,
                StatusType.SUPER_CRITICAL_RATE, 0.0D,
                StatusType.FINAL_DAMAGE_MULTIPLIER, 100.0D
        ), 100.0D, 0.0D, 0.0D));
        return attacker;
    }

    private AstPlayer shieldedVictim() {
        PlayerMock victimBukkitPlayer = spy(server().addPlayer());
        doNothing().when(victimBukkitPlayer).playHurtAnimation(anyFloat());
        AstPlayer victim = DesignTestFixtures.astPlayer(victimBukkitPlayer, AccountMode.ADMIN);
        victim.setStatusSnapshot(DesignTestFixtures.statusSnapshot(Map.of(
                StatusType.MAX_HEALTH, 100.0D,
                StatusType.MAX_SHIELD, 5.0D,
                StatusType.EVASION, 0.0D
        ), 100.0D, 0.0D, 0.0D).withCurrentShield(1.0D));
        return victim;
    }

    private PassiveSkillContext passiveContext(AstPlayer player, long cooldownTicks) {
        return new PassiveSkillContext(
                player,
                new SkillDefinition(
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
                ),
                Instant.EPOCH,
                0L
        );
    }

    private DamageResult attack(
            DamageService damageService,
            AstPlayer attacker,
            AstPlayer victim,
            DamageSource source
    ) {
        return damageService.attack(
                AstEntity.player(attacker),
                AstEntity.player(victim),
                AttackType.MELEE,
                List.of(DamageComponent.defaultComponent()),
                source
        );
    }
}
