package io.github.maaasu.astralRecord.feature.combat.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageComponent;
import io.github.maaasu.astralRecord.feature.combat.model.DamageSource;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.service.MobCombatService;
import io.github.maaasu.astralRecord.feature.mob.service.MobKnockbackService;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.playersetting.service.PlayerSettingService;
import io.github.maaasu.astralRecord.feature.skill.executor.PaladinDefenseConversionSkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.service.PassiveSkillService;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.shared.display.DisplayTextService;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DamageServiceDefenseConversionTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/14-combat/14_1-モデル定義.md
     * 章・見出し: # 14_1-モデル定義 > ## 3. scaling
     * 検証契約: 有効なディフェンスコンバージョンはATTACKER_STATUSのSKILLだけを防御力参照へ置換し、通常攻撃とFIXEDダメージを変更しない。
     */
    @Test
    void defenseConversionAppliesOnlyToAttackerStatusSkillDamage() {
        AstPlayer attacker = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.PLAYER);
        attacker.setStatusSnapshot(DesignTestFixtures.statusSnapshot(Map.of(
                StatusType.MAX_HEALTH, 100.0D,
                StatusType.ATTACK, 100.0D,
                StatusType.MELEE_ATTACK, 100.0D,
                StatusType.DEFENSE, 20.0D,
                StatusType.MELEE_DEFENSE, 5.0D,
                StatusType.STRENGTH, 50.0D,
                StatusType.ACCURACY, 100.0D
        ), 100.0D, 0.0D, 0.0D));

        StatusService statusService = mock(StatusService.class);
        when(statusService.getStatus(attacker)).thenReturn(attacker.getStatusSnapshot());
        PassiveSkillService passiveSkillService = mock(PassiveSkillService.class);
        when(passiveSkillService.isPassiveSkillActive(attacker, PaladinDefenseConversionSkillExecutor.ID)).thenReturn(true);
        DamageService service = new DamageService(
                statusService,
                mock(MobService.class),
                mock(MobCombatService.class),
                mock(MobKnockbackService.class),
                mock(DisplayTextService.class),
                mock(PlayerSettingService.class),
                mock(ParticleDisplayService.class),
                null,
                null,
                new DamageCalculator(() -> 100.0D, () -> 100.0D)
        );
        service.setPassiveSkillService(passiveSkillService);

        var skill = service.attack(
                AstEntity.player(attacker), AstEntity.mob(mob()), AttackType.MELEE,
                List.of(DamageComponent.defaultComponent()), DamageSource.SKILL, 1.0D
        );
        var normalAttack = service.attack(
                AstEntity.player(attacker), AstEntity.mob(mob()), AttackType.MELEE,
                List.of(DamageComponent.defaultComponent()), DamageSource.NORMAL_ATTACK, 1.0D
        );
        var fixed = service.applyDamage(AstEntity.player(attacker), AstEntity.mob(mob()), 11.0D, AttackType.MELEE);

        assertEquals(37.5D, skill.breakdown().resolvedAttackPower(), 0.0001D);
        assertEquals(300.0D, normalAttack.breakdown().resolvedAttackPower(), 0.0001D);
        assertEquals(11.0D, fixed.breakdown().resolvedAttackPower(), 0.0001D);
    }

    private MobInstance mob() {
        return DesignTestFixtures.mobInstance(1_000.0D, 0.0D, 0.0D);
    }
}
