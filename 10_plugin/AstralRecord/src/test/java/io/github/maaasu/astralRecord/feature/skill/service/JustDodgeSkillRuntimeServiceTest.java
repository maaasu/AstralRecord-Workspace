package io.github.maaasu.astralRecord.feature.skill.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.DamageResult;
import io.github.maaasu.astralRecord.feature.combat.model.DamageSource;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.executor.AdministratorJustDodgeSkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.PassiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import io.github.maaasu.astralRecord.feature.skill.model.SkillResourceType;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Location;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class JustDodgeSkillRuntimeServiceTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 6.2 ドッジ連動パッシブ
     * 検証契約: 有効時間内のNORMAL_ATTACKとSKILLは回数制限なく無効化し、energyRecoveryAmountは同じドッジ中に1回だけ回復する。
     */
    @Test
    void directHitsHaveNoCountLimitAndEnergyRecoversOncePerDodge() {
        StatusService statusService = mock(StatusService.class);
        ParticleDisplayService particleDisplayService = mock(ParticleDisplayService.class);
        JustDodgeSkillRuntimeService runtime = new JustDodgeSkillRuntimeService(
                statusService,
                particleDisplayService
        );
        AstPlayer player = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.ADMIN);
        PassiveSkillContext context = new PassiveSkillContext(
                player,
                definition(Map.of("invulnerabilityTicks", 8, "energyRecoveryAmount", 10.0D)),
                Instant.EPOCH,
                0L
        );
        runtime.activate(context);
        runtime.onDodge(player);

        AstEntity victim = AstEntity.player(player);
        DamageResult hit = new DamageResult(5.0D);
        assertTrue(runtime.tryNegateDirectDamage(victim, DamageSource.NORMAL_ATTACK, hit));
        assertTrue(runtime.tryNegateDirectDamage(victim, DamageSource.SKILL, hit));
        assertFalse(runtime.tryNegateDirectDamage(victim, DamageSource.OTHER, hit));
        verify(statusService).recoverEnergy(player, 10.0D);
        verify(particleDisplayService, times(2)).spawnForNearbyViewers(
                any(Location.class),
                eq(SharedParticleDefinitions.DODGE_CLOUD.withCount(6))
        );
        verify(particleDisplayService).spawnForNearbyViewers(
                any(Location.class),
                eq(SharedParticleDefinitions.JUST_DODGE_ENERGY_ABSORB_END_ROD)
        );

        runtime.onDodge(player);
        assertTrue(runtime.tryNegateDirectDamage(victim, DamageSource.NORMAL_ATTACK, hit));
        verify(statusService, times(2)).recoverEnergy(player, 10.0D);
        verify(particleDisplayService, times(2)).spawnForNearbyViewers(
                any(Location.class),
                eq(SharedParticleDefinitions.JUST_DODGE_ENERGY_ABSORB_END_ROD)
        );
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 6.2 ドッジ連動パッシブ
     * 検証契約: ドッジ連動パッシブは設定されたinvulnerabilityTicksの終了後に直接攻撃を無効化しない。
     */
    @Test
    void configuredWindowExpiresAtConfiguredTick() {
        JustDodgeSkillRuntimeService runtime = new JustDodgeSkillRuntimeService(
                mock(StatusService.class),
                mock(ParticleDisplayService.class)
        );
        AstPlayer player = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.ADMIN);
        PassiveSkillContext context = new PassiveSkillContext(
                player,
                definition(Map.of("invulnerabilityTicks", 3, "energyRecoveryAmount", 10.0D)),
                Instant.EPOCH,
                0L
        );
        runtime.activate(context);
        runtime.onDodge(player);

        assertTrue(runtime.tryNegateDirectDamage(
                AstEntity.player(player),
                DamageSource.NORMAL_ATTACK,
                new DamageResult(1.0D)
        ));
        server().getScheduler().performTicks(3);
        assertFalse(runtime.tryNegateDirectDamage(
                AstEntity.player(player),
                DamageSource.NORMAL_ATTACK,
                new DamageResult(1.0D)
        ));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 6.2 ドッジ連動パッシブ
     * 検証契約: 死亡・ワールド移動相当の短命状態破棄後は設定を保持して次のドッジで再利用でき、退出相当の全破棄後は再利用できない。
     */
    @Test
    void stateAndConfigurationHaveSeparateLifecycleCleanup() {
        JustDodgeSkillRuntimeService runtime = new JustDodgeSkillRuntimeService(
                mock(StatusService.class),
                mock(ParticleDisplayService.class)
        );
        AstPlayer player = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.ADMIN);
        PassiveSkillContext context = new PassiveSkillContext(
                player,
                definition(Map.of("invulnerabilityTicks", 8, "energyRecoveryAmount", 10.0D)),
                Instant.EPOCH,
                0L
        );
        AstEntity victim = AstEntity.player(player);
        DamageResult hit = new DamageResult(1.0D);
        runtime.activate(context);

        runtime.onDodge(player);
        runtime.clearDodgeState(player.getBukkit().getUniqueId());
        assertFalse(runtime.tryNegateDirectDamage(victim, DamageSource.NORMAL_ATTACK, hit));

        runtime.onDodge(player);
        assertTrue(runtime.tryNegateDirectDamage(victim, DamageSource.NORMAL_ATTACK, hit));

        runtime.clearPlayer(player.getBukkit().getUniqueId());
        runtime.onDodge(player);
        assertFalse(runtime.tryNegateDirectDamage(victim, DamageSource.NORMAL_ATTACK, hit));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 6.2 ドッジ連動パッシブ
     * 検証契約: ドッジ連動パッシブの設定値は正数として検証され、0以下のinvulnerabilityTicksまたはenergyRecoveryAmountを受け付けない。
     */
    @Test
    void invalidParametersAreRejected() {
        JustDodgeSkillRuntimeService runtime = new JustDodgeSkillRuntimeService(
                mock(StatusService.class),
                mock(ParticleDisplayService.class)
        );
        AdministratorJustDodgeSkillExecutor executor = new AdministratorJustDodgeSkillExecutor(runtime);

        assertThrows(
                SkillParameterException.class,
                () -> executor.validateParams(definition(Map.of("invulnerabilityTicks", 0, "energyRecoveryAmount", 10.0D)))
        );
        assertThrows(
                SkillParameterException.class,
                () -> executor.validateParams(definition(Map.of("invulnerabilityTicks", 8, "energyRecoveryAmount", 0.0D)))
        );
    }

    private static SkillDefinition definition(Map<String, Object> params) {
        return new SkillDefinition(
                AdministratorJustDodgeSkillExecutor.ID,
                AdministratorJustDodgeSkillExecutor.ID,
                "ジャスト回避",
                "ドッジの瞬間に攻撃を見切る防御パッシブ。",
                "FEATHER",
                List.of(),
                0L,
                0.0D,
                0L,
                1,
                null,
                params,
                List.of("passive", "defense"),
                SkillKind.PASSIVE,
                true,
                SkillResourceType.MANA,
                0.0D
        );
    }
}
