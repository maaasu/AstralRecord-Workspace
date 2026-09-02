package io.github.maaasu.astralRecord.feature.skill.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.combat.model.DamageResult;
import io.github.maaasu.astralRecord.feature.combat.model.DamageSource;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillCombatService;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillEffectService;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillTargetingService;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillTaskService;
import io.github.maaasu.astralRecord.feature.skill.model.PassiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
import io.github.maaasu.astralRecord.feature.skill.model.SkillResourceType;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BastionStrikeSkillRuntimeServiceTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 13. バスティオンストライクの実装契約
     * 検証契約: シールド破壊時に視線ラインの最寄り対象へ反撃し、反撃ヒット時だけShield回復・通知・攻撃無効化を成立させる。
     */
    @Test
    void successfulCounterattackNegatesDamageAndRestoresMissingShield() {
        Fixture fixture = fixture(new DamageResult(30.0D), List.of(mockTarget()));
        PlayerMessageService messageService = mock(PlayerMessageService.class);

        try (MockedStatic<PlayerMessageService> messages = mockStatic(PlayerMessageService.class)) {
            messages.when(PlayerMessageService::getInstance).thenReturn(messageService);

            assertTrue(fixture.runtime.tryNegateShieldBreakingDirectDamage(
                    AstEntity.player(fixture.player), DamageSource.NORMAL_ATTACK
            ));
        }

        verify(fixture.targeting).inLine(
                same(fixture.player.getBukkit()),
                any(Location.class),
                any(Vector.class),
                eq(6.0D),
                eq(0.0D),
                eq(1)
        );
        verify(fixture.combat).hit(
                any(AstEntity.class),
                same(fixture.target),
                eq(AttackType.MELEE),
                eq(DamageElement.NONE),
                eq(1.875D)
        );
        verify(fixture.combat).recoverShield(any(AstEntity.class), eq(75.0D));
        verify(messageService).send(fixture.player.getBukkit(), PlayerMsgId.P_5880);
        verify(fixture.effects).sound(any(Location.class), eq(Sound.ITEM_SHIELD_BLOCK), eq(0.9F), eq(0.85F));
        verify(fixture.effects).sound(any(Location.class), eq(Sound.BLOCK_BEACON_ACTIVATE), eq(0.9F), eq(1.15F));
        verify(fixture.effects).sound(any(Location.class), eq(Sound.ITEM_TRIDENT_THUNDER), eq(0.7F), eq(1.30F));
        verify(fixture.tasks).repeat(
                eq(fixture.player.getBukkit().getUniqueId()),
                eq(BastionStrikeSkillRuntimeService.SKILL_ID + ":soul-bastion"),
                eq(0L),
                eq(2L),
                eq(4),
                any()
        );

        assertFalse(fixture.runtime.tryNegateShieldBreakingDirectDamage(
                AstEntity.player(fixture.player), DamageSource.SKILL
        ));
        verify(fixture.combat).hit(
                any(AstEntity.class),
                same(fixture.target),
                eq(AttackType.MELEE),
                eq(DamageElement.NONE),
                eq(1.875D)
        );
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 13. バスティオンストライクの実装契約 > ### 13.1 数値・対象・演出
     * 検証契約: 対象なし、回避、0ダメージ、非直接攻撃では反撃・回復・演出を発生させない。
     */
    @Test
    void onlyAHitOnTheViewLineCanActivateThePassive() {
        Fixture noTarget = fixture(new DamageResult(30.0D), List.of());
        assertFalse(noTarget.runtime.tryNegateShieldBreakingDirectDamage(
                AstEntity.player(noTarget.player), DamageSource.NORMAL_ATTACK
        ));
        verify(noTarget.combat, never()).hit(any(), any(), any(), any(), eq(1.875D));
        verify(noTarget.combat, never()).recoverShield(any(), any(Double.class));

        Fixture evaded = fixture(DamageResult.evaded(0.0D, 0.0D, 0.0D), List.of(mockTarget()));
        assertFalse(evaded.runtime.tryNegateShieldBreakingDirectDamage(
                AstEntity.player(evaded.player), DamageSource.NORMAL_ATTACK
        ));
        verify(evaded.combat, never()).recoverShield(any(), any(Double.class));

        Fixture zeroDamage = fixture(new DamageResult(0.0D), List.of(mockTarget()));
        assertFalse(zeroDamage.runtime.tryNegateShieldBreakingDirectDamage(
                AstEntity.player(zeroDamage.player), DamageSource.NORMAL_ATTACK
        ));
        verify(zeroDamage.combat, never()).recoverShield(any(), any(Double.class));

        Fixture otherSource = fixture(new DamageResult(30.0D), List.of(mockTarget()));
        assertFalse(otherSource.runtime.tryNegateShieldBreakingDirectDamage(
                AstEntity.player(otherSource.player), DamageSource.OTHER
        ));
        verify(otherSource.targeting, never()).inLine(any(), any(), any(), any(Double.class), any(Double.class), any(Integer.class));
    }

    private Fixture fixture(DamageResult counterattack, List<AstEntity> targets) {
        SkillTargetingService targeting = mock(SkillTargetingService.class);
        SkillCombatService combat = mock(SkillCombatService.class);
        SkillEffectService effects = mock(SkillEffectService.class);
        SkillTaskService tasks = mock(SkillTaskService.class);
        AstPlayer player = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.ADMIN);
        player.setStatusSnapshot(DesignTestFixtures.statusSnapshot(Map.of(
                StatusType.MAX_HEALTH, 100.0D,
                StatusType.MAX_SHIELD, 100.0D,
                StatusType.EVASION, 0.0D
        ), 100.0D, 0.0D, 0.0D).withCurrentShield(25.0D));
        Player bukkitPlayer = player.getBukkit();
        when(targeting.inLine(
                any(Player.class),
                any(Location.class),
                any(Vector.class),
                eq(6.0D),
                eq(0.0D),
                eq(1)
        )).thenReturn(targets);
        if (!targets.isEmpty()) {
            when(combat.hit(
                    any(AstEntity.class),
                    same(targets.get(0)),
                    eq(AttackType.MELEE),
                    eq(DamageElement.NONE),
                    eq(1.875D)
            )).thenReturn(counterattack);
        }
        when(combat.recoverShield(any(AstEntity.class), any(Double.class))).thenReturn(75.0D);

        BastionStrikeSkillRuntimeService runtime = new BastionStrikeSkillRuntimeService(
                targeting,
                combat,
                effects,
                tasks
        );
        runtime.activate(new PassiveSkillContext(
                player,
                definition(),
                Instant.EPOCH,
                0L
        ));
        AstEntity target = targets.isEmpty() ? mockTarget() : targets.get(0);
        return new Fixture(player, target, targeting, combat, effects, tasks, runtime);
    }

    private AstEntity mockTarget() {
        AstEntity target = mock(AstEntity.class);
        when(target.location()).thenReturn(new Location(null, 0.0D, 64.0D, 4.0D));
        return target;
    }

    private static SkillDefinition definition() {
        return new SkillDefinition(
                BastionStrikeSkillRuntimeService.SKILL_ID,
                BastionStrikeSkillRuntimeService.SKILL_ID,
                "バスティオンストライク",
                "攻撃を受け止めて返す近接反撃。",
                "SOUL_CAMPFIRE",
                List.of(),
                3000L,
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
        );
    }

    private record Fixture(
            AstPlayer player,
            AstEntity target,
            SkillTargetingService targeting,
            SkillCombatService combat,
            SkillEffectService effects,
            SkillTaskService tasks,
            BastionStrikeSkillRuntimeService runtime
    ) {
    }
}
