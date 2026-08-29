package io.github.maaasu.astralRecord.feature.skill.executor.active.hunter;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.active.model.SkillBallisticProjectileSpec;
import io.github.maaasu.astralRecord.feature.skill.active.model.SkillProjectileTermination;
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
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastTrigger;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import io.github.maaasu.astralRecord.feature.skill.model.SkillResourceType;
import io.github.maaasu.astralRecord.feature.status.model.HealthRecoveryContext;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HunterHealArrowExecutorTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 19. ヒールアローの実装契約 > ### 19.1 数値・対象・終端
     * 検証契約: ヒールアローは必須paramsを要求し、敵Mobへのダメージ倍率を30%から変更できない。
     */
    @Test
    void validatesRequiredParamsAndFixedDamageRatio() {
        HunterHealArrowExecutor executor = new HunterHealArrowExecutor(activeSkillServices());

        assertDoesNotThrow(() -> executor.validateParams(definition(validParams())));

        Map<String, Object> invalidParams = new java.util.LinkedHashMap<>(validParams());
        invalidParams.put("damageRatio", 0.31D);
        SkillParameterException exception = org.junit.jupiter.api.Assertions.assertThrows(
                SkillParameterException.class,
                () -> executor.validateParams(definition(invalidParams))
        );
        assertEquals("damageRatio", exception.key());

        Map<String, Object> nonFiniteParams = new java.util.LinkedHashMap<>(validParams());
        nonFiniteParams.put("radius", Double.POSITIVE_INFINITY);
        SkillParameterException nonFiniteException = org.junit.jupiter.api.Assertions.assertThrows(
                SkillParameterException.class,
                () -> executor.validateParams(definition(nonFiniteParams))
        );
        assertEquals("radius", nonFiniteException.key());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 19. ヒールアローの実装契約 > ### 19.1 数値・対象・終端
     * 検証契約: 本番filebaseの実行paramsが欠落せず、実行器の検証を通過する。
     */
    @Test
    void productionFilebaseContainsValidatedParams() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(filebasePath().toFile());
        assertEquals(HunterHealArrowExecutor.ID, yaml.getString("id"));
        assertEquals(5, yaml.getInt("maxLevel"));
        assertEquals(200L, yaml.getLong("cooldownTicks"));

        ConfigurationSection rawParams = yaml.getConfigurationSection("params");
        if (rawParams == null) {
            throw new AssertionError("heal arrow params must be defined in filebase");
        }
        Map<String, Object> params = new LinkedHashMap<>();
        for (String key : rawParams.getKeys(false)) {
            params.put(key, rawParams.get(key));
        }

        new HunterHealArrowExecutor(activeSkillServices()).validateParams(definition(params));
        assertEquals(2.0D, ((Number) params.get("radius")).doubleValue(), 0.0001D);
        assertEquals(12.0D, ((Number) params.get("healAmount")).doubleValue(), 0.0001D);
        assertEquals(0.30D, ((Number) params.get("damageRatio")).doubleValue(), 0.0001D);
        assertEquals(1.25D, ((Number) params.get("projectileSpeed")).doubleValue(), 0.0001D);
        assertEquals(0.45D, ((Number) params.get("projectileHitRadius")).doubleValue(), 0.0001D);
        assertEquals(60, ((Number) params.get("areaDurationTicks")).intValue());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 19. ヒールアローの実装契約 > ### 19.1 数値・対象・終端
     * 検証契約: 発動時は初速1.25、重力0.14、Blockまで継続する貫通弾道を発射し、Mob命中へRANGED/NONEの30%を適用して回復エリアを作る。
     */
    @Test
    void launchesStrongGravityProjectileAndHealsEachPlayerOnlyOnce() {
        Fixture fixture = fixture();
        SkillCastResult result = fixture.executor.cast(fixture.context);

        ArgumentCaptor<SkillBallisticProjectileSpec> projectileCaptor =
                ArgumentCaptor.forClass(SkillBallisticProjectileSpec.class);
        ArgumentCaptor<BiConsumer<AstEntity, Location>> hitCaptor = biConsumerCaptor();
        verify(fixture.projectiles).launchBallisticWithTermination(
                same(fixture.player),
                any(Location.class),
                projectileCaptor.capture(),
                hitCaptor.capture(),
                any()
        );
        SkillBallisticProjectileSpec projectile = projectileCaptor.getValue();
        assertEquals(1.25D, projectile.initialVelocity().length(), 0.0001D);
        assertEquals(0.14D, projectile.gravityPerTick(), 0.0001D);
        assertEquals(80, projectile.maxTicks());
        assertEquals(48.0D, projectile.maxDistance(), 0.0001D);
        assertEquals(0.45D, projectile.hitRadius(), 0.0001D);
        assertTrue(projectile.piercing());
        assertEquals(Integer.MAX_VALUE, projectile.maxHits());
        assertEquals(SharedParticleDefinitions.HUNTER_HEAL_ARROW_TRAIL, projectile.trail());
        assertEquals(SharedParticleDefinitions.HUNTER_HEAL_ARROW_IMPACT, projectile.impact());
        assertTrue(result.success());

        AstEntity target = mock(AstEntity.class);
        when(target.location()).thenReturn(new Location(fixture.world, 4.0D, 64.0D, 0.0D));
        AstPlayer healedPlayer = mock(AstPlayer.class);
        Player healedBukkit = mock(Player.class);
        when(healedPlayer.getBukkit()).thenReturn(healedBukkit);
        when(healedBukkit.getUniqueId()).thenReturn(UUID.randomUUID());
        when(healedBukkit.getLocation()).thenReturn(new Location(fixture.world, 4.0D, 64.0D, 0.0D));
        when(fixture.targeting.playersInRadius(any(Location.class), eq(2.0D), eq(2.0D)))
                .thenReturn(List.of(healedPlayer));
        when(fixture.combat.recoverHp(
                same(healedPlayer), eq(12.0D), any(HealthRecoveryContext.class)
        )).thenReturn(12.0D);

        hitCaptor.getValue().accept(target, new Location(fixture.world, 4.0D, 65.0D, 0.0D));

        ArgumentCaptor<IntConsumer> taskCaptor = ArgumentCaptor.forClass(IntConsumer.class);
        verify(fixture.tasks).repeat(
                eq(fixture.player.getUniqueId()),
                anyString(),
                eq(0L),
                eq(1L),
                eq(60),
                taskCaptor.capture()
        );
        taskCaptor.getValue().accept(0);
        taskCaptor.getValue().accept(1);

        verify(fixture.combat).hit(
                any(AstEntity.class),
                same(target),
                eq(AttackType.RANGED),
                eq(DamageElement.NONE),
                eq(0.30D)
        );
        verify(fixture.combat, times(1)).recoverHp(
                same(healedPlayer), eq(12.0D), any(HealthRecoveryContext.class)
        );
        verify(fixture.effects, times(2)).ring(
                any(Location.class), eq(2.0D), eq(20), eq(SharedParticleDefinitions.HUNTER_HEAL_ARROW_AREA)
        );
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 19. ヒールアローの実装契約 > ### 19.1 数値・対象・終端
     * 検証契約: 矢はMob命中後も消えず、命中した各Mobへ一度だけダメージと回復エリアを適用する。
     */
    @Test
    void createsAreaForEachMobHitWhileProjectileContinues() {
        Fixture fixture = fixture();
        fixture.executor.cast(fixture.context);

        ArgumentCaptor<BiConsumer<AstEntity, Location>> hitCaptor = biConsumerCaptor();
        verify(fixture.projectiles).launchBallisticWithTermination(
                same(fixture.player), any(Location.class), any(SkillBallisticProjectileSpec.class), hitCaptor.capture(), any()
        );
        AstEntity firstTarget = mock(AstEntity.class);
        AstEntity secondTarget = mock(AstEntity.class);
        when(firstTarget.location()).thenReturn(new Location(fixture.world, 2.0D, 64.0D, 0.0D));
        when(secondTarget.location()).thenReturn(new Location(fixture.world, 4.0D, 64.0D, 0.0D));

        hitCaptor.getValue().accept(firstTarget, new Location(fixture.world, 2.0D, 65.0D, 0.0D));
        hitCaptor.getValue().accept(secondTarget, new Location(fixture.world, 4.0D, 65.0D, 0.0D));

        verify(fixture.combat).hit(any(AstEntity.class), same(firstTarget), eq(AttackType.RANGED), eq(DamageElement.NONE), eq(0.30D));
        verify(fixture.combat).hit(any(AstEntity.class), same(secondTarget), eq(AttackType.RANGED), eq(DamageElement.NONE), eq(0.30D));
        verify(fixture.tasks, times(2)).repeat(
                eq(fixture.player.getUniqueId()), anyString(), eq(0L), eq(1L), eq(60), any(IntConsumer.class)
        );
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 19. ヒールアローの実装契約 > ### 19.1 数値・対象・終端
     * 検証契約: RANGE終端では回復エリアを作らず、Block終端では正確な衝突地点へエリアを作る。
     */
    @Test
    void createsAreaOnlyForBlockAndNotRangeTermination() {
        Fixture fixture = fixture();
        fixture.executor.cast(fixture.context);

        ArgumentCaptor<Consumer<SkillProjectileTermination>> terminationCaptor = consumerCaptor();
        verify(fixture.projectiles).launchBallisticWithTermination(
                same(fixture.player), any(Location.class), any(SkillBallisticProjectileSpec.class), any(), terminationCaptor.capture()
        );
        Location rangeLocation = new Location(fixture.world, 20.0D, 40.0D, 0.0D);
        terminationCaptor.getValue().accept(new SkillProjectileTermination(
                SkillProjectileTermination.Type.RANGE, rangeLocation, rangeLocation
        ));
        verify(fixture.tasks, never()).repeat(
                any(UUID.class), anyString(), anyLong(), anyLong(), anyInt(), any(IntConsumer.class)
        );

        clearInvocations(fixture.effects, fixture.tasks);
        Location blockLocation = new Location(fixture.world, 5.0D, 64.0D, 0.0D);
        terminationCaptor.getValue().accept(new SkillProjectileTermination(
                SkillProjectileTermination.Type.BLOCK, blockLocation, blockLocation
        ));
        verify(fixture.tasks).repeat(
                eq(fixture.player.getUniqueId()), anyString(), eq(0L), eq(1L), eq(60), any(IntConsumer.class)
        );
        verify(fixture.effects).point(
                eq(blockLocation), eq(SharedParticleDefinitions.HUNTER_HEAL_ARROW_IMPACT)
        );
    }

    private static Fixture fixture() {
        SkillTargetingService targeting = mock(SkillTargetingService.class);
        SkillCombatService combat = mock(SkillCombatService.class);
        SkillEffectService effects = mock(SkillEffectService.class);
        SkillProjectileService projectiles = mock(SkillProjectileService.class);
        SkillTaskService tasks = mock(SkillTaskService.class);
        ActiveSkillServices services = new ActiveSkillServices(
                targeting,
                combat,
                effects,
                projectiles,
                mock(SkillMovementService.class),
                mock(TemporarySkillEffectService.class),
                tasks
        );
        Player player = mock(Player.class);
        World world = mock(World.class);
        UUID playerId = UUID.randomUUID();
        Location eye = new Location(world, 0.0D, 65.6D, 0.0D, 0.0F, 0.0F);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getEyeLocation()).thenReturn(eye);
        AstPlayer astPlayer = mock(AstPlayer.class);
        when(astPlayer.getBukkit()).thenReturn(player);
        SkillCastContext context = new SkillCastContext(
                definition(validParams()),
                new PlayerSkillCaster(astPlayer),
                null,
                List.of(),
                eye,
                mock(StatusSnapshot.class),
                SkillCastTrigger.PLAYER_COMMAND,
                Instant.EPOCH
        );
        return new Fixture(
                player,
                world,
                targeting,
                combat,
                effects,
                projectiles,
                tasks,
                new HunterHealArrowExecutor(services),
                context
        );
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
        return Map.of(
                "radius", 2.0D,
                "healAmount", 12.0D,
                "damageRatio", 0.30D,
                "projectileSpeed", 1.25D,
                "projectileHitRadius", 0.45D,
                "areaDurationTicks", 60
        );
    }

    private static Path filebasePath() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve("40_filebase/30.features.skill/v1.hunter_heal_arrow.yml");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new AssertionError("heal arrow filebase was not found from the test directory");
    }

    private static SkillDefinition definition(Map<String, Object> params) {
        return new SkillDefinition(
                HunterHealArrowExecutor.ID,
                HunterHealArrowExecutor.ID,
                "ヒールアロー",
                null,
                "SPECTRAL_ARROW",
                List.of(),
                200L,
                0.0D,
                0L,
                1,
                null,
                params,
                List.of("active", "ranged", "bow"),
                SkillKind.ACTIVE,
                true,
                SkillResourceType.ENERGY,
                14.0D,
                null,
                5
        );
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<BiConsumer<AstEntity, Location>> biConsumerCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(BiConsumer.class);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<Consumer<SkillProjectileTermination>> consumerCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(Consumer.class);
    }

    private record Fixture(
            Player player,
            World world,
            SkillTargetingService targeting,
            SkillCombatService combat,
            SkillEffectService effects,
            SkillProjectileService projectiles,
            SkillTaskService tasks,
            HunterHealArrowExecutor executor,
            SkillCastContext context
    ) {
    }
}
