package io.github.maaasu.astralRecord.feature.skill.executor.active.mage;

import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillCombatService;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillEffectService;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillMovementService;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillProjectileService;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillTargetingService;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillTaskService;
import io.github.maaasu.astralRecord.feature.skill.active.service.TemporarySkillEffectService;
import io.github.maaasu.astralRecord.feature.skill.model.PlayerSkillCaster;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import io.github.maaasu.astralRecord.feature.skill.model.SkillResourceType;
import io.github.maaasu.astralRecord.feature.status.model.HealthRecoveryContext;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MageHealAuraExecutorTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 22. メイジ ヒールオーラの実装契約
     * 検証契約: ヒールオーラは正の半径・高さ・回復量を必須とする。
     */
    @Test
    void validatesRequiredParams() {
        MageHealAuraExecutor executor = new MageHealAuraExecutor(activeSkillServices());

        assertDoesNotThrow(() -> executor.validateParams(definition(validParams())));

        Map<String, Object> invalidParams = new LinkedHashMap<>(validParams());
        invalidParams.put("healAmount", 0.0D);
        SkillParameterException exception = org.junit.jupiter.api.Assertions.assertThrows(
                SkillParameterException.class,
                () -> executor.validateParams(definition(invalidParams))
        );
        assertEquals("healAmount", exception.key());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 22. メイジ ヒールオーラの実装契約
     * 検証契約: 本番filebaseは最大Lv.5、MP6、基礎2秒、半径4m・高さ3m・回復量5の実行paramsを定義する。
     */
    @Test
    void productionFilebaseContainsValidatedParams() {
        org.bukkit.configuration.file.YamlConfiguration yaml =
                org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(filebasePath().toFile());
        assertEquals(MageHealAuraExecutor.ID, yaml.getString("id"));
        assertEquals(5, yaml.getInt("maxLevel"));
        assertEquals(40L, yaml.getLong("cooldownTicks"));
        assertEquals(6.0D, yaml.getDouble("resourceCost"));

        org.bukkit.configuration.ConfigurationSection rawParams = yaml.getConfigurationSection("params");
        if (rawParams == null) {
            throw new AssertionError("heal aura params must be defined in filebase");
        }
        Map<String, Object> params = new LinkedHashMap<>();
        for (String key : rawParams.getKeys(false)) {
            params.put(key, rawParams.get(key));
        }

        new MageHealAuraExecutor(activeSkillServices()).validateParams(definition(params));
        assertEquals(4.0D, ((Number) params.get("radius")).doubleValue(), 0.0001D);
        assertEquals(3.0D, ((Number) params.get("height")).doubleValue(), 0.0001D);
        assertEquals(5.0D, ((Number) params.get("healAmount")).doubleValue(), 0.0001D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 22. メイジ ヒールオーラの実装契約
     * 検証契約: 発動位置の半径4m・上下3m内にいる全プレイヤーを即時回復し、範囲輪郭と実回復演出を表示する。
     */
    @Test
    void immediatelyHealsEveryPlayerInAuraAndDisplaysRange() {
        Fixture fixture = fixture();
        AstPlayer firstTarget = target(fixture.world, 1.0D);
        AstPlayer secondTarget = target(fixture.world, 2.0D);
        when(fixture.targeting.playersInRadius(any(Location.class), eq(4.0D), eq(3.0D)))
                .thenReturn(List.of(firstTarget, secondTarget));
        when(fixture.combat.recoverHp(same(firstTarget), eq(5.0D), any(HealthRecoveryContext.class)))
                .thenReturn(5.0D);
        when(fixture.combat.recoverHp(same(secondTarget), eq(5.0D), any(HealthRecoveryContext.class)))
                .thenReturn(2.0D);

        assertTrue(fixture.executor.cast(fixture.context).success());

        verify(fixture.targeting).playersInRadius(any(Location.class), eq(4.0D), eq(3.0D));
        verify(fixture.combat).recoverHp(same(firstTarget), eq(5.0D), any(HealthRecoveryContext.class));
        verify(fixture.combat).recoverHp(same(secondTarget), eq(5.0D), any(HealthRecoveryContext.class));
        verify(fixture.effects).ring(any(Location.class), eq(4.0D), eq(24),
                eq(SharedParticleDefinitions.MAGE_HEAL_AURA_RING));
        verify(fixture.effects).point(any(Location.class), eq(SharedParticleDefinitions.MAGE_HEAL_AURA_PULSE));
        verify(fixture.effects, times(2)).point(
                any(Location.class), eq(SharedParticleDefinitions.MAGE_HEAL_AURA_HEAL)
        );
    }

    private static Fixture fixture() {
        SkillTargetingService targeting = mock(SkillTargetingService.class);
        SkillCombatService combat = mock(SkillCombatService.class);
        SkillEffectService effects = mock(SkillEffectService.class);
        Player player = mock(Player.class);
        World world = mock(World.class);
        Location location = new Location(world, 0.0D, 64.0D, 0.0D);
        when(player.getLocation()).thenReturn(location);

        AstPlayer astPlayer = mock(AstPlayer.class);
        when(astPlayer.getBukkit()).thenReturn(player);
        SkillCastContext context = new SkillCastContext(
                definition(validParams()),
                new PlayerSkillCaster(astPlayer),
                null,
                List.of(),
                location,
                mock(StatusSnapshot.class),
                io.github.maaasu.astralRecord.feature.skill.model.SkillCastTrigger.PLAYER_COMMAND,
                Instant.EPOCH
        );
        return new Fixture(targeting, combat, effects, world,
                new MageHealAuraExecutor(new ActiveSkillServices(
                        targeting,
                        combat,
                        effects,
                        mock(SkillProjectileService.class),
                        mock(SkillMovementService.class),
                        mock(TemporarySkillEffectService.class),
                        mock(SkillTaskService.class)
                )), context);
    }

    private static AstPlayer target(World world, double x) {
        AstPlayer target = mock(AstPlayer.class);
        Player bukkit = mock(Player.class);
        when(target.getBukkit()).thenReturn(bukkit);
        when(bukkit.getUniqueId()).thenReturn(UUID.randomUUID());
        when(bukkit.getLocation()).thenReturn(new Location(world, x, 64.0D, 0.0D));
        return target;
    }

    private static ActiveSkillServices activeSkillServices() {
        return new ActiveSkillServices(
                mock(SkillTargetingService.class),
                mock(SkillCombatService.class),
                mock(SkillEffectService.class),
                mock(SkillProjectileService.class),
                mock(SkillMovementService.class),
                mock(TemporarySkillEffectService.class),
                mock(SkillTaskService.class)
        );
    }

    private static Map<String, Object> validParams() {
        return Map.of("radius", 4.0D, "height", 3.0D, "healAmount", 5.0D);
    }

    private static Path filebasePath() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve("40_filebase/30.features.skill/v1.mage_heal_aura.yml");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new AssertionError("heal aura filebase was not found from the test directory");
    }

    private static SkillDefinition definition(Map<String, Object> params) {
        return new SkillDefinition(
                MageHealAuraExecutor.ID,
                MageHealAuraExecutor.ID,
                "ヒールオーラ",
                null,
                "AMETHYST_CLUSTER",
                List.of(),
                40L,
                0.0D,
                0L,
                1,
                null,
                params,
                List.of("active", "magic", "support"),
                SkillKind.ACTIVE,
                true,
                SkillResourceType.MANA,
                6.0D,
                null,
                5
        );
    }

    private record Fixture(
            SkillTargetingService targeting,
            SkillCombatService combat,
            SkillEffectService effects,
            World world,
            MageHealAuraExecutor executor,
            SkillCastContext context
    ) {
    }
}
