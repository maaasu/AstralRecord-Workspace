package io.github.maaasu.astralRecord;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.executor.SkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.PassiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.PlayerSkillCaster;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastTrigger;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
import io.github.maaasu.astralRecord.feature.skill.model.SkillResourceType;
import io.github.maaasu.astralRecord.feature.skill.registry.SkillRegistry;
import io.github.maaasu.astralRecord.feature.skill.repository.SkillRepository;
import io.github.maaasu.astralRecord.feature.skill.service.ArcaneFlowSkillRuntimeService;
import io.github.maaasu.astralRecord.feature.skill.service.SkillService;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Location;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AstralRecordArcaneFlowIntegrationTest extends MockBukkitTestBase {

    private static final Path ASTRAL_RECORD_SOURCE = Path.of(
            "src",
            "main",
            "java",
            "io",
            "github",
            "maaasu",
            "astralRecord",
            "AstralRecord.java"
    );

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 24. アーケインフローの実装契約
     * 検証契約: 本番と同じ統合配線で、成功した異なる魔法スキルへ短縮条件と紫色粒子が到達する。
     */
    @Test
    void productionWiringTracksSuccessfulMagicSkillSwitch() {
        ParticleDisplayService particleDisplayService = mock(ParticleDisplayService.class);
        ArcaneFlowSkillRuntimeService runtime = new ArcaneFlowSkillRuntimeService(particleDisplayService);
        SkillRegistry registry = new SkillRegistry();
        SkillService skillService = new SkillService(mock(SkillRepository.class), registry, null);
        AstralRecord.configureArcaneFlowIntegration(skillService, runtime);

        AstPlayer player = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.ADMIN);
        SkillDefinition passive = passiveDefinition();
        SkillExecutor passiveExecutor = registry.getExecutor("mage_arcane_flow");
        assertNotNull(passiveExecutor);
        passiveExecutor.onActivate(new PassiveSkillContext(player, passive, Instant.EPOCH, 0L));

        SkillDefinition first = skillDefinition("first_magic", List.of("active", "magic"));
        SkillDefinition second = skillDefinition("second_magic", List.of("active", "magic"));
        registry.registerExecutor(new SuccessfulExecutor("first_impl"));
        registry.registerExecutor(new SuccessfulExecutor("second_impl"));
        registry.replaceDefinitions(Map.of(first.getId(), first, second.getId(), second));

        assertTrue(skillService.castSkill(
                new PlayerSkillCaster(player),
                first.getId(),
                SkillCastTrigger.SYSTEM,
                player.getBukkit().getLocation(),
                null,
                List.of()
        ).success());
        assertEquals(5.0D, runtime.castTimeReductionPercent(player, second), 0.0001D);

        assertTrue(skillService.castSkill(
                new PlayerSkillCaster(player),
                second.getId(),
                SkillCastTrigger.SYSTEM,
                player.getBukkit().getLocation(),
                null,
                List.of()
        ).success());
        verify(particleDisplayService).spawnForNearbyViewers(
                any(Location.class),
                argThat((Collection<Location> locations) -> locations.size() == 16),
                eq(SharedParticleDefinitions.SKILL_MAGE_ARCANE_DUST)
        );
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 24. アーケインフローの実装契約
     * 検証契約: setupFeatureがテスト済みのアーケインフロー統合配線を本番初期化で呼び出す。
     */
    @Test
    void setupFeatureUsesTheTestedArcaneFlowIntegrationWiring() throws IOException {
        String source = Files.readString(ASTRAL_RECORD_SOURCE, StandardCharsets.UTF_8);
        int setupFeatureStart = source.indexOf("private void setupFeature()");

        assertTrue(setupFeatureStart >= 0, "setupFeature must exist");
        assertTrue(
                source.indexOf("configureArcaneFlowIntegration(", setupFeatureStart) >= 0,
                "setupFeature must call configureArcaneFlowIntegration"
        );
    }

    private static SkillDefinition passiveDefinition() {
        return new SkillDefinition(
                "mage_arcane_flow",
                "mage_arcane_flow",
                "アーケインフロー",
                null,
                "AMETHYST_SHARD",
                List.of(),
                0L,
                0.0D,
                0L,
                1,
                null,
                Map.of("castTimeReductionPercent", 5.0D),
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
                id.replace("magic", "impl"),
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

    private static final class SuccessfulExecutor implements SkillExecutor {
        private final String implementationId;

        private SuccessfulExecutor(String implementationId) {
            this.implementationId = implementationId;
        }

        @Override
        public String implementationId() {
            return implementationId;
        }

        @Override
        public SkillCastResult cast(SkillCastContext context) {
            return SkillCastResult.succeeded();
        }
    }
}
