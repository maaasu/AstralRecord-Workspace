package io.github.maaasu.astralRecord.feature.skill.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.executor.SkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastTrigger;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
import io.github.maaasu.astralRecord.feature.skill.model.PlayerSkillCaster;
import io.github.maaasu.astralRecord.feature.skill.model.SkillResourceType;
import io.github.maaasu.astralRecord.feature.skill.registry.SkillRegistry;
import io.github.maaasu.astralRecord.feature.skill.repository.SkillRepository;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SkillServiceCastTimeReductionTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-サービス.md
     * 章・見出し: # 13_3-サービス > ## 6.4 アーケインフロー
     * 検証契約: プレイヤーの追加短縮resolverは既存の共通詠唱計算へ適用され、10tickを50%短縮して5tickで完了する。
     */
    @Test
    void playerCastTimeReductionResolverShortensScheduledCast() {
        PlayerMock bukkitPlayer = server().addPlayer();
        AstPlayer player = DesignTestFixtures.astPlayer(bukkitPlayer, AccountMode.ADMIN);
        SkillRegistry registry = new SkillRegistry();
        CapturingExecutor executor = new CapturingExecutor("arcane_cast_impl");
        SkillDefinition definition = new SkillDefinition(
                "arcane_cast",
                "arcane_cast_impl",
                "アーケインテスト",
                null,
                "AMETHYST_SHARD",
                List.of(),
                0L,
                0.0D,
                10L,
                1,
                null,
                Map.of(),
                List.of("active", "magic"),
                SkillKind.ACTIVE,
                true,
                SkillResourceType.MANA,
                0.0D
        );
        registry.registerExecutor(executor);
        registry.replaceDefinitions(Map.of(definition.getId(), definition));

        AstralRecord plugin = mock(AstralRecord.class);
        when(plugin.isEnabled()).thenReturn(true);
        SkillService service = new SkillService(mock(SkillRepository.class), registry, plugin);
        service.setPlayerCastTimeReductionResolver((ignored, skill) -> 50.0D);

        SkillCastResult result = service.castSkill(
                new PlayerSkillCaster(player),
                definition.getId(),
                SkillCastTrigger.SYSTEM,
                bukkitPlayer.getLocation(),
                null,
                List.of()
        );

        assertTrue(result.success());
        assertNull(executor.context);
        server().getScheduler().performTicks(4L);
        assertNull(executor.context);
        server().getScheduler().performTicks(1L);
        assertSame(definition, executor.context.skill());
    }

    private static final class CapturingExecutor implements SkillExecutor {
        private final String implementationId;
        private SkillCastContext context;

        private CapturingExecutor(String implementationId) {
            this.implementationId = implementationId;
        }

        @Override
        public String implementationId() {
            return implementationId;
        }

        @Override
        public SkillCastResult cast(SkillCastContext context) {
            this.context = context;
            return SkillCastResult.succeeded();
        }
    }
}
