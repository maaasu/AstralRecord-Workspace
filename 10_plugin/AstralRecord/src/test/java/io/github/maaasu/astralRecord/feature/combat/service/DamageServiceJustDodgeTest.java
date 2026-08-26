package io.github.maaasu.astralRecord.feature.combat.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageResult;
import io.github.maaasu.astralRecord.feature.combat.model.DamageSource;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionType;
import io.github.maaasu.astralRecord.feature.mob.service.MobCombatService;
import io.github.maaasu.astralRecord.feature.mob.service.MobKnockbackService;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.playersetting.service.PlayerSettingService;
import io.github.maaasu.astralRecord.feature.skill.model.PassiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
import io.github.maaasu.astralRecord.feature.skill.model.SkillResourceType;
import io.github.maaasu.astralRecord.feature.skill.service.JustDodgeSkillRuntimeService;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.shared.display.DisplayTextService;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class DamageServiceJustDodgeTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 6.2 ドッジ連動パッシブ
     * 検証契約: DamageServiceはNORMAL_ATTACKの計算済み直接hitを無効化するが、applyConditionDamageの状態異常DoTは無効化せず適用する。
     */
    @Test
    void directDamageIsNegatedButConditionDotIsNot() {
        StatusService statusService = new StatusService();
        ParticleDisplayService particleDisplayService = mock(ParticleDisplayService.class);
        DamageService damageService = new DamageService(
                statusService,
                mock(MobService.class),
                mock(MobCombatService.class),
                mock(MobKnockbackService.class),
                mock(DisplayTextService.class),
                mock(PlayerSettingService.class),
                particleDisplayService
        );
        JustDodgeSkillRuntimeService runtime = new JustDodgeSkillRuntimeService(
                statusService,
                particleDisplayService
        );
        damageService.setJustDodgeSkillRuntimeService(runtime);

        AstPlayer attacker = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.ADMIN);
        attacker.setStatusSnapshot(DesignTestFixtures.statusSnapshot(Map.of(
                StatusType.MAX_HEALTH, 100.0D,
                StatusType.ATTACK, 10.0D,
                StatusType.ACCURACY, 100.0D,
                StatusType.FINAL_DAMAGE_MULTIPLIER, 100.0D
        ), 100.0D, 0.0D, 0.0D));
        PlayerMock victimBukkitPlayer = spy(server().addPlayer());
        doNothing().when(victimBukkitPlayer).playHurtAnimation(anyFloat());
        AstPlayer victim = DesignTestFixtures.astPlayer(victimBukkitPlayer, AccountMode.ADMIN);
        victim.setStatusSnapshot(DesignTestFixtures.statusSnapshot(Map.of(
                StatusType.MAX_HEALTH, 100.0D,
                StatusType.MAX_ENERGY, 100.0D,
                StatusType.EVASION, 0.0D
        ), 100.0D, 0.0D, 0.0D));

        PassiveSkillContext context = new PassiveSkillContext(
                victim,
                new SkillDefinition(
                        "administrator_just_dodge",
                        "administrator_just_dodge",
                        "ジャスト回避",
                        "ドッジの瞬間に攻撃を見切る防御パッシブ。",
                        "FEATHER",
                        List.of(),
                        0L,
                        0.0D,
                        0L,
                        1,
                        null,
                        Map.of("invulnerabilityTicks", 8, "energyRecoveryAmount", 10.0D),
                        List.of("passive", "defense"),
                        SkillKind.PASSIVE,
                        true,
                        SkillResourceType.MANA,
                        0.0D
                ),
                Instant.EPOCH,
                0L
        );
        runtime.activate(context);
        runtime.onDodge(victim);

        DamageResult direct = damageService.attack(
                AstEntity.player(attacker),
                AstEntity.player(victim),
                AttackType.MELEE,
                List.of(io.github.maaasu.astralRecord.feature.combat.model.DamageComponent.defaultComponent()),
                DamageSource.NORMAL_ATTACK
        );

        assertEquals(0.0D, direct.finalDamage(), 0.0001D);
        assertEquals(100.0D, victim.getStatusSnapshot().getCurrentHp(), 0.0001D);
        assertEquals(10.0D, victim.getStatusSnapshot().getCurrentEnergy(), 0.0001D);

        DamageResult dot = damageService.applyConditionDamage(
                null,
                AstEntity.player(victim),
                5.0D,
                ConditionType.POISON
        );

        assertEquals(5.0D, dot.finalDamage(), 0.0001D);
        assertEquals(95.0D, victim.getStatusSnapshot().getCurrentHp(), 0.0001D);
        runtime.onDodge(victim);
        DamageResult skill = damageService.attack(
                AstEntity.player(attacker),
                AstEntity.player(victim),
                AttackType.MAGIC,
                List.of(io.github.maaasu.astralRecord.feature.combat.model.DamageComponent.defaultComponent()),
                DamageSource.SKILL
        );

        assertEquals(0.0D, skill.finalDamage(), 0.0001D);
        assertEquals(95.0D, victim.getStatusSnapshot().getCurrentHp(), 0.0001D);
        assertEquals(20.0D, victim.getStatusSnapshot().getCurrentEnergy(), 0.0001D);
        verify(particleDisplayService, times(2)).spawnForNearbyViewers(
                org.mockito.ArgumentMatchers.any(org.bukkit.Location.class),
                org.mockito.ArgumentMatchers.eq(SharedParticleDefinitions.DODGE_CLOUD.withCount(6))
        );
        verify(particleDisplayService, times(2)).spawnForNearbyViewers(
                org.mockito.ArgumentMatchers.any(org.bukkit.Location.class),
                org.mockito.ArgumentMatchers.eq(SharedParticleDefinitions.JUST_DODGE_ENERGY_ABSORB_END_ROD)
        );
        String recoverySoundKey = Registry.SOUND_EVENT.getKeyOrThrow(Sound.ENTITY_EXPERIENCE_ORB_PICKUP).getKey();
        assertEquals(2L, victimBukkitPlayer.getHeardSounds().stream()
                .filter(heardSound -> recoverySoundKey.equals(heardSound.getSound()))
                .count());
    }
}
