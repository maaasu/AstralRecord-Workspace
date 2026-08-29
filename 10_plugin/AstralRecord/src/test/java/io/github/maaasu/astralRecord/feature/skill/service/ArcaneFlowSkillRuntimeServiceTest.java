package io.github.maaasu.astralRecord.feature.skill.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.model.PassiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
import io.github.maaasu.astralRecord.feature.skill.model.SkillResourceType;
import io.github.maaasu.astralRecord.feature.skill.executor.MageArcaneFlowSkillExecutor;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Location;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class ArcaneFlowSkillRuntimeServiceTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 6.4 アーケインフロー
     * 検証契約: 成功した直前スキルと異なる魔法スキルだけへ短縮率を適用し、成立時に紫色のリング粒子を表示する。
     */
    @Test
    void differentMagicSkillUsesLevelFiveReductionAndRendersActivation() {
        ParticleDisplayService particleDisplayService = mock(ParticleDisplayService.class);
        ArcaneFlowSkillRuntimeService runtime = new ArcaneFlowSkillRuntimeService(particleDisplayService);
        AstPlayer player = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.ADMIN);
        runtime.activate(new PassiveSkillContext(
                player,
                passiveDefinition(10.0D),
                Instant.EPOCH,
                0L
        ));

        SkillDefinition firstMagic = skillDefinition("mage_fireball", List.of("active", "magic"));
        SkillDefinition secondMagic = skillDefinition("mage_heal_aura", List.of("active", "magic"));
        SkillDefinition nonMagic = skillDefinition("swordsman_bastion_strike", List.of("active", "melee"));

        runtime.onSkillCast(player, firstMagic);
        assertEquals(10.0D, runtime.castTimeReductionPercent(player, secondMagic), 0.0001D);

        runtime.onSkillCast(player, secondMagic);
        assertEquals(0.0D, runtime.castTimeReductionPercent(player, secondMagic), 0.0001D);
        verify(particleDisplayService).spawnForNearbyViewers(
                any(Location.class),
                argThat((Collection<Location> locations) -> locations.size() == 16),
                eq(SharedParticleDefinitions.SKILL_MAGE_ARCANE_DUST)
        );

        runtime.onSkillCast(player, nonMagic);
        assertEquals(10.0D, runtime.castTimeReductionPercent(player, secondMagic), 0.0001D);
        runtime.onSkillCast(player, secondMagic);
        verify(particleDisplayService, times(2)).spawnForNearbyViewers(
                any(Location.class),
                argThat((Collection<Location> locations) -> locations.size() == 16),
                eq(SharedParticleDefinitions.SKILL_MAGE_ARCANE_DUST)
        );
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 6.4 アーケインフロー
     * 検証契約: 初回・同一スキル・ライフサイクル破棄後は短縮せず、Lv.1設定は5%を返す。
     */
    @Test
    void firstSameAndClearedSkillDoNotTriggerLevelOneReduction() {
        ArcaneFlowSkillRuntimeService runtime = new ArcaneFlowSkillRuntimeService(
                mock(ParticleDisplayService.class)
        );
        AstPlayer player = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.ADMIN);
        PassiveSkillContext context = new PassiveSkillContext(
                player,
                passiveDefinition(5.0D),
                Instant.EPOCH,
                0L
        );
        SkillDefinition magic = skillDefinition("mage_fireball", List.of("active", "magic"));
        runtime.activate(context);

        assertEquals(0.0D, runtime.castTimeReductionPercent(player, magic), 0.0001D);
        runtime.onSkillCast(player, magic);
        assertEquals(0.0D, runtime.castTimeReductionPercent(player, magic), 0.0001D);

        runtime.clearPreviousSkill(player.getBukkit().getUniqueId());
        assertEquals(0.0D, runtime.castTimeReductionPercent(player, magic), 0.0001D);
        runtime.deactivate(context);
        assertEquals(0.0D, runtime.castTimeReductionPercent(player, magic), 0.0001D);
    }

    private static SkillDefinition passiveDefinition(double reduction) {
        return new SkillDefinition(
                MageArcaneFlowSkillExecutor.ID,
                MageArcaneFlowSkillExecutor.ID,
                "アーケインフロー",
                null,
                "AMETHYST_SHARD",
                List.of(),
                0L,
                0.0D,
                0L,
                1,
                null,
                Map.of("castTimeReductionPercent", reduction),
                List.of("passive", "magic"),
                SkillKind.PASSIVE,
                true,
                SkillResourceType.MANA,
                0.0D
        );
    }

    private static SkillDefinition skillDefinition(String id, List<String> tags) {
        return new SkillDefinition(
                id,
                id,
                id,
                null,
                "FEATHER",
                List.of(),
                0L,
                0.0D,
                0L,
                1,
                null,
                Map.of(),
                tags,
                SkillKind.ACTIVE,
                true,
                SkillResourceType.MANA,
                0.0D
        );
    }
}
