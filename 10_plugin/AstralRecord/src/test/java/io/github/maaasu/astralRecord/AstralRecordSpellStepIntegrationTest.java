package io.github.maaasu.astralRecord;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.guide.model.GuideConditionType;
import io.github.maaasu.astralRecord.feature.guide.service.GuideService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.DodgeService;
import io.github.maaasu.astralRecord.feature.skill.model.PassiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.PlayerSkillCaster;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastTrigger;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
import io.github.maaasu.astralRecord.feature.skill.model.SkillResourceType;
import io.github.maaasu.astralRecord.feature.skill.executor.SkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.registry.SkillRegistry;
import io.github.maaasu.astralRecord.feature.skill.repository.SkillRepository;
import io.github.maaasu.astralRecord.feature.skill.service.SkillService;
import io.github.maaasu.astralRecord.feature.skill.service.SpellStepSkillRuntimeService;
import io.github.maaasu.astralRecord.feature.hud.service.PlayerHudService;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AstralRecordSpellStepIntegrationTest extends MockBukkitTestBase {

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
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 20. スペルステップの実装契約
     * 検証契約: 本番初期化と同じ配線で、成功したrangedタグ付きスキルのSkillService通知が
     * registry定義解決・スペルステップ待機権・DodgeServiceの無料消費へ到達する。
     */
    @Test
    void productionWiringArmsFreeDodgeAfterSuccessfulRangedSkill() {
        PlayerMock bukkitPlayer = server().addPlayer();
        bukkitPlayer.teleport(new Location(bukkitPlayer.getWorld(), 0.0D, 64.0D, 0.0D));
        bukkitPlayer.getWorld().getBlockAt(0, 63, 0).setType(Material.STONE);
        AstPlayer player = DesignTestFixtures.astPlayer(bukkitPlayer, AccountMode.PLAYER);
        player.setStatusSnapshot(DesignTestFixtures.statusSnapshot(Map.of(), 100.0D, 100.0D, 0.0D));

        StatusService statusService = mock(StatusService.class);
        when(statusService.getStatus(player)).thenReturn(
            DesignTestFixtures.statusSnapshot(Map.of(), 100.0D, 100.0D, 0.0D)
        );
        AstralRecord plugin = mock(AstralRecord.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);

        DodgeService dodgeService = new DodgeService(
            plugin,
            statusService,
            mock(PlayerHudService.class),
            mock(ParticleDisplayService.class)
        );
        SpellStepSkillRuntimeService runtime = new SpellStepSkillRuntimeService();
        SkillRegistry registry = new SkillRegistry();
        SkillService skillService = new SkillService(mock(SkillRepository.class), registry, null);
        GuideService guideService = mock(GuideService.class);

        AstralRecord.configureSpellStepIntegration(skillService, dodgeService, runtime, guideService);

        SkillDefinition passive = definition(
            "hunter_spell_step",
            "hunter_spell_step",
            SkillKind.PASSIVE,
            Map.of("windowTicks", 20, "triggerSound", "block.beacon.power_select"),
            List.of("passive", "windwait")
        );
        SkillExecutor passiveExecutor = registry.getExecutor("hunter_spell_step");
        assertNotNull(passiveExecutor);
        passiveExecutor.onActivate(new PassiveSkillContext(player, passive, Instant.EPOCH, 0L));

        SkillDefinition rangedSkill = definition(
            "test_ranged_skill",
            "test_ranged_impl",
            SkillKind.ACTIVE,
            Map.of(),
            List.of("ranged")
        );
        registry.registerExecutor(new SuccessfulExecutor("test_ranged_impl"));
        registry.replaceDefinitions(Map.of(rangedSkill.getId(), rangedSkill));

        SkillCastResult castResult = skillService.castSkill(
            new PlayerSkillCaster(player),
            rangedSkill.getId(),
            SkillCastTrigger.SYSTEM,
            bukkitPlayer.getLocation(),
            null,
            List.of()
        );

        assertTrue(castResult.success());
        assertTrue(dodgeService.beginSneakWindow(player));
        dodgeService.tryTriggerOnSneakRelease(player);

        verify(statusService).consumeEnergy(player, 0.0D);
        verify(guideService).recordCondition(player, GuideConditionType.SKILL_CAST, rangedSkill.getId());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 20. スペルステップの実装契約
     * 検証契約: 本番のsetupFeatureが、テスト済みのスペルステップ統合配線を実際に呼び出す。
     */
    @Test
    void setupFeatureUsesTheTestedSpellStepIntegrationWiring() throws IOException {
        String source = Files.readString(ASTRAL_RECORD_SOURCE, StandardCharsets.UTF_8);
        int setupFeatureStart = source.indexOf("private void setupFeature()");

        assertTrue(setupFeatureStart >= 0, "setupFeature must exist");
        assertTrue(
            source.indexOf("configureSpellStepIntegration(", setupFeatureStart) >= 0,
            "setupFeature must call configureSpellStepIntegration"
        );
    }

    private static SkillDefinition definition(
        String id,
        String implementationId,
        SkillKind kind,
        Map<String, Object> params,
        List<String> tags
    ) {
        return new SkillDefinition(
            id,
            implementationId,
            id,
            null,
            "FEATHER",
            List.of(),
            0L,
            0.0D,
            0L,
            1,
            null,
            params,
            tags,
            kind,
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
