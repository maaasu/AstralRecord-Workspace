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
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillCombatService;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillEffectService;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillTargetingService;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillTaskService;
import io.github.maaasu.astralRecord.feature.skill.model.PassiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
import io.github.maaasu.astralRecord.feature.skill.model.SkillResourceType;
import io.github.maaasu.astralRecord.feature.skill.service.BastionStrikeSkillRuntimeService;
import io.github.maaasu.astralRecord.feature.skill.service.PassiveSkillService;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.shared.display.DisplayTextService;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
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
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DamageServiceBastionStrikeTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 13. バスティオンストライクの実装契約
     * 検証契約: シールド破壊時に命中する反撃が成立した場合だけ、元攻撃を無効化して不足Shieldを回復する。
     */
    @Test
    void successfulCounterattackIsConnectedToDamageService() {
        StatusService statusService = activatedStatusService();
        SkillTargetingService targeting = mock(SkillTargetingService.class);
        SkillCombatService combat = mock(SkillCombatService.class);
        SkillEffectService effects = mock(SkillEffectService.class);
        SkillTaskService tasks = mock(SkillTaskService.class);
        DamageService damageService = damageService(statusService);
        BastionStrikeSkillRuntimeService runtime = new BastionStrikeSkillRuntimeService(
                targeting, combat, effects, tasks
        );
        damageService.setBastionStrikeSkillRuntimeService(runtime);

        AstPlayer attacker = attacker();
        AstPlayer victim = shieldedVictim();
        AstEntity target = mock(AstEntity.class);
        Location targetLocation = victim.getBukkit().getLocation().clone().add(0.0D, 0.0D, 4.0D);
        when(target.location()).thenReturn(targetLocation);
        when(targeting.inLine(
                any(Player.class), any(Location.class), any(Vector.class), eq(6.0D), eq(0.0D), eq(1)
        )).thenReturn(List.of(target));
        when(combat.hit(
                any(AstEntity.class), same(target), eq(AttackType.MELEE), eq(io.github.maaasu.astralRecord.feature.combat.model.DamageElement.NONE), eq(1.875D)
        )).thenReturn(new DamageResult(30.0D));
        when(combat.recoverShield(any(AstEntity.class), eq(4.0D))).thenReturn(4.0D);
        runtime.activate(passiveContext(victim, 3000L));

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
        verify(combat).hit(
                any(AstEntity.class), same(target), eq(AttackType.MELEE),
                eq(io.github.maaasu.astralRecord.feature.combat.model.DamageElement.NONE), eq(1.875D)
        );
        verify(combat).recoverShield(any(AstEntity.class), eq(4.0D));

        DamageResult cooldownHit = attack(damageService, attacker, victim, DamageSource.SKILL);

        assertTrue(cooldownHit.shieldBroken());
        assertEquals(1.0D, cooldownHit.shieldDamage(), 0.0001D);
        assertEquals(0.0D, victim.getStatusSnapshot().getCurrentShield(), 0.0001D);
        assertEquals(100.0D, victim.getStatusSnapshot().getCurrentHp(), 0.0001D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 13. バスティオンストライクの実装契約 > ### 13.1 数値・対象・演出
     * 検証契約: 反撃が回避された場合は元のシールド破壊経路へ戻り、DoTはパッシブ経路を通らない。
     */
    @Test
    void missAndConditionDamageAreNotNegated() {
        StatusService statusService = activatedStatusService();
        SkillTargetingService targeting = mock(SkillTargetingService.class);
        SkillCombatService combat = mock(SkillCombatService.class);
        BastionStrikeSkillRuntimeService runtime = new BastionStrikeSkillRuntimeService(
                targeting,
                combat,
                mock(SkillEffectService.class),
                mock(SkillTaskService.class)
        );
        DamageService damageService = damageService(statusService);
        damageService.setBastionStrikeSkillRuntimeService(runtime);

        AstPlayer attacker = attacker();
        AstPlayer victim = shieldedVictim();
        AstEntity target = mock(AstEntity.class);
        Location targetLocation = victim.getBukkit().getLocation().clone().add(0.0D, 0.0D, 4.0D);
        when(target.location()).thenReturn(targetLocation);
        when(targeting.inLine(
                any(Player.class), any(Location.class), any(Vector.class), eq(6.0D), eq(0.0D), eq(1)
        )).thenReturn(List.of(target));
        when(combat.hit(any(), same(target), any(), any(), eq(1.875D)))
                .thenReturn(DamageResult.evaded(0.0D, 0.0D, 0.0D));
        runtime.activate(passiveContext(victim, 3000L));

        DamageResult missedCounterattack = attack(damageService, attacker, victim, DamageSource.NORMAL_ATTACK);

        assertTrue(missedCounterattack.shieldBroken());
        assertEquals(0.0D, victim.getStatusSnapshot().getCurrentShield(), 0.0001D);
        verify(combat, never()).recoverShield(any(), anyDouble());

        AstPlayer dotVictim = shieldedVictim();
        runtime.activate(passiveContext(dotVictim, 3000L));
        DamageResult dot = damageService.applyConditionDamage(
                null,
                AstEntity.player(dotVictim),
                5.0D,
                ConditionType.POISON
        );

        assertEquals(5.0D, dot.finalDamage(), 0.0001D);
        assertEquals(1.0D, dotVictim.getStatusSnapshot().getCurrentShield(), 0.0001D);
        assertEquals(95.0D, dotVictim.getStatusSnapshot().getCurrentHp(), 0.0001D);
    }

    private DamageService damageService(StatusService statusService) {
        return new DamageService(
                statusService,
                mock(MobService.class),
                mock(MobCombatService.class),
                mock(MobKnockbackService.class),
                mock(DisplayTextService.class),
                mock(PlayerSettingService.class),
                mock(ParticleDisplayService.class)
        );
    }

    private StatusService activatedStatusService() {
        StatusService statusService = new StatusService();
        PassiveSkillService passiveSkillService = mock(PassiveSkillService.class);
        when(passiveSkillService.isPassiveSkillActive(
                any(AstPlayer.class), eq(StatusService.SHIELD_ACTIVATE_SKILL_ID)
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
        doNothing().when(victimBukkitPlayer).playHurtAnimation(org.mockito.ArgumentMatchers.anyFloat());
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
                        BastionStrikeSkillRuntimeService.SKILL_ID,
                        BastionStrikeSkillRuntimeService.SKILL_ID,
                        "バスティオンストライク",
                        "攻撃を受け止めて返す近接反撃。",
                        "SOUL_CAMPFIRE",
                        List.of(),
                        cooldownTicks,
                        0.0D,
                        0L,
                        1,
                        null,
                        Map.of("range", 6.0D, "damageRatio", 1.875D),
                        List.of("passive", "melee", "defense"),
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
