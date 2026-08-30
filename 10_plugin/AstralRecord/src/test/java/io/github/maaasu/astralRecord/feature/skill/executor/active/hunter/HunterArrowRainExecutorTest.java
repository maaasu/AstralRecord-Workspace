package io.github.maaasu.astralRecord.feature.skill.executor.active.hunter;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.active.model.SkillBallisticProjectileLaunch;
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
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.PlayerSkillCaster;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastTrigger;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
import io.github.maaasu.astralRecord.feature.skill.model.SkillResourceType;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HunterArrowRainExecutorTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 17. ハンター アローレインの実装契約
     * 検証契約: 初弾は重力弾道で84%RANGEDを与え、entity/block着弾時だけ45本を3本/tickで降らせ、初弾着弾Y以上のBlockを貫通して各36%RANGEDを与える。
     */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void castLaunchesOpeningArrowThenThreePerTickRainVolleyAtImpact() {
        SkillTargetingService targeting = mock(SkillTargetingService.class);
        SkillCombatService combat = mock(SkillCombatService.class);
        SkillEffectService effects = mock(SkillEffectService.class);
        SkillProjectileService projectiles = mock(SkillProjectileService.class);
        ActiveSkillServices services = new ActiveSkillServices(
                targeting,
                combat,
                effects,
                projectiles,
                mock(SkillMovementService.class),
                mock(TemporarySkillEffectService.class),
                mock(SkillTaskService.class)
        );
        World world = mock(World.class);
        Player player = mock(Player.class);
        UUID playerId = UUID.randomUUID();
        Location eye = new Location(world, 4.0D, 70.0D, -2.0D, 0.0F, 0.0F);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getEyeLocation()).thenReturn(eye);
        when(player.getLocation()).thenReturn(eye);
        when(player.getWorld()).thenReturn(world);
        when(player.isOnline()).thenReturn(true);
        AstPlayer astPlayer = mock(AstPlayer.class);
        StatusSnapshot status = DesignTestFixtures.statusSnapshot(Map.of(), 100.0D, 58.0D, 160.0D);
        when(astPlayer.getBukkit()).thenReturn(player);
        when(astPlayer.getStatusSnapshot()).thenReturn(status);
        when(targeting.groundAt(any(Location.class), anyInt(), anyInt()))
                .thenAnswer(invocation -> ((Location) invocation.getArgument(0)).clone());
        HunterArrowRainExecutor executor = new HunterArrowRainExecutor(services, new Random(12345L));
        SkillDefinition definition = definition();

        assertTrue(executor.cast(new SkillCastContext(
                definition,
                new PlayerSkillCaster(astPlayer),
                null,
                List.of(),
                eye,
                status,
                SkillCastTrigger.SYSTEM,
                Instant.now()
        )).success());

        ArgumentCaptor<SkillBallisticProjectileSpec> openingSpec =
                ArgumentCaptor.forClass(SkillBallisticProjectileSpec.class);
        ArgumentCaptor<BiConsumer<AstEntity, Location>> openingHit = ArgumentCaptor.forClass(BiConsumer.class);
        ArgumentCaptor<Consumer<SkillProjectileTermination>> openingTermination =
                ArgumentCaptor.forClass(Consumer.class);
        verify(projectiles).launchBallisticWithTermination(
                same(player), any(Location.class), openingSpec.capture(), openingHit.capture(), openingTermination.capture()
        );
        assertEquals(0.05D, openingSpec.getValue().gravityPerTick(), 0.0001D);
        assertEquals(18.0D, openingSpec.getValue().maxDistance(), 0.0001D);

        AstEntity target = AstEntity.bukkit(mock(Entity.class));
        Location impact = new Location(world, 7.0D, 64.0D, 8.0D);
        openingHit.getValue().accept(target, impact);
        verify(combat).hit(
                any(AstEntity.class), same(target), eq(AttackType.RANGED), eq(DamageElement.NONE), eq(0.84D)
        );

        openingTermination.getValue().accept(new SkillProjectileTermination(
                SkillProjectileTermination.Type.RANGE, impact, impact
        ));
        verify(projectiles, never()).launchBallisticVolley(
                any(), any(), anyInt(), anyDouble(), any(), any()
        );

        openingTermination.getValue().accept(new SkillProjectileTermination(
                SkillProjectileTermination.Type.ENTITY, impact, impact
        ));
        ArgumentCaptor<List<SkillBallisticProjectileLaunch>> volley = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<BiConsumer<AstEntity, Location>> rainHit = ArgumentCaptor.forClass(BiConsumer.class);
        verify(projectiles).launchBallisticVolley(
                same(player), volley.capture(), eq(3), eq(impact.getY()), rainHit.capture(), any()
        );
        assertEquals(45, volley.getValue().size());
        assertTrue(volley.getValue().stream().allMatch(launch -> launch.spec().gravityPerTick() > 0.0D));

        rainHit.getValue().accept(target, impact);
        verify(combat).hit(
                any(AstEntity.class), same(target), eq(AttackType.RANGED), eq(DamageElement.NONE), eq(0.36D)
        );

        Location blockFace = new Location(world, 12.0D, 66.0D, 8.0D);
        Location blockEffectCenter = new Location(world, 11.9D, 65.9D, 8.0D);
        openingTermination.getValue().accept(new SkillProjectileTermination(
                SkillProjectileTermination.Type.BLOCK, blockFace, blockEffectCenter
        ));
        verify(projectiles, org.mockito.Mockito.times(2)).launchBallisticVolley(
                same(player), any(), eq(3), anyDouble(), any(), any()
        );
        verify(projectiles).launchBallisticVolley(
                same(player), any(), eq(3), eq(blockFace.getY()), any(), any()
        );
        verify(effects).sound(same(blockEffectCenter), eq(org.bukkit.Sound.ITEM_CROSSBOW_SHOOT), eq(1.25F), eq(0.65F));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 17. ハンター アローレインの実装契約 > ### 17.2 弾道
     * 検証契約: 雨矢の離散初速は毎tick移動後に重力を加える更新順で指定終点へ到達する。
     */
    @Test
    void ballisticVelocityReachesTargetAfterConfiguredTicks() {
        World world = mock(World.class);
        Location origin = new Location(world, -3.0D, 75.0D, 2.0D);
        Location target = new Location(world, 6.0D, 64.0D, -5.0D);
        int ticks = 12;
        double gravity = 0.14D;
        Vector velocity = HunterArrowRainExecutor.solveBallisticVelocity(origin, target, ticks, gravity);
        Location actual = origin.clone();

        for (int tick = 0; tick < ticks; tick++) {
            actual.add(velocity);
            velocity.add(new Vector(0.0D, -gravity, 0.0D));
        }

        assertEquals(target.getX(), actual.getX(), 0.0001D);
        assertEquals(target.getY(), actual.getY(), 0.0001D);
        assertEquals(target.getZ(), actual.getZ(), 0.0001D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 17. ハンター アローレインの実装契約 > ### 17.2 弾道
     * 検証契約: 深い地表へ向かう雨矢も全step長から最大移動距離を決め、固定上限で終点前に終了しない。
     */
    @Test
    void rainVolleyAllowsFullTrajectoryToDeepGround() {
        SkillTargetingService targeting = mock(SkillTargetingService.class);
        ActiveSkillServices services = new ActiveSkillServices(
                targeting,
                mock(SkillCombatService.class),
                mock(SkillEffectService.class),
                mock(SkillProjectileService.class),
                mock(SkillMovementService.class),
                mock(TemporarySkillEffectService.class),
                mock(SkillTaskService.class)
        );
        World world = mock(World.class);
        Player player = mock(Player.class);
        Location eye = new Location(world, 0.0D, 100.0D, 0.0D, 0.0F, 0.0F);
        Location deepGround = new Location(world, 0.0D, 51.05D, 18.0D);
        when(player.getEyeLocation()).thenReturn(eye);
        when(player.getWorld()).thenReturn(world);
        when(targeting.groundAt(any(Location.class), eq(6), eq(32))).thenReturn(deepGround);
        AstPlayer astPlayer = mock(AstPlayer.class);
        StatusSnapshot status = DesignTestFixtures.statusSnapshot(Map.of(), 100.0D, 58.0D, 160.0D);
        when(astPlayer.getBukkit()).thenReturn(player);
        when(astPlayer.getStatusSnapshot()).thenReturn(status);
        PlayerSkillCaster caster = new PlayerSkillCaster(astPlayer);
        PlayerActiveSkillContext context = new PlayerActiveSkillContext(
                new SkillCastContext(
                        definition(), caster, null, List.of(), eye, status,
                        SkillCastTrigger.SYSTEM, Instant.now()
                ),
                caster,
                services
        );

        SkillBallisticProjectileLaunch launch = new HunterArrowRainExecutor(services, new Random(20260828L))
                .createRainVolley(context, new Location(world, 0.0D, 82.0D, 18.0D), 3.0D, 1, 0.75D)
                .getFirst();
        SkillBallisticProjectileSpec spec = launch.spec();
        double requiredDistance = HunterArrowRainExecutor.ballisticPathLength(
                spec.initialVelocity(), spec.maxTicks(), spec.gravityPerTick()
        );
        Location actual = launch.origin();
        Vector velocity = spec.initialVelocity();
        for (int tick = 0; tick < spec.maxTicks(); tick++) {
            actual.add(velocity);
            velocity.add(new Vector(0.0D, -spec.gravityPerTick(), 0.0D));
        }

        assertTrue(requiredDistance > 48.0D, Double.toString(requiredDistance));
        assertEquals(requiredDistance + HunterArrowRainExecutor.RAIN_PATH_DISTANCE_MARGIN,
                spec.maxDistance(), 1.0E-9D);
        assertEquals(deepGround.getX(), actual.getX(), 1.0E-9D);
        assertEquals(deepGround.getY(), actual.getY(), 1.0E-9D);
        assertEquals(deepGround.getZ(), actual.getZ(), 1.0E-9D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/13_6-発動スキル追加ガイド.md
     * 章・見出し: # 13_6-発動スキル追加ガイド > ## 17. ハンター アローレインの実装契約 > ### 17.3 バランス・テスト契約
     * 検証契約: 頭上から収束する実弾道の全線分を用い、3倍化後も中心静止Mobへの距離別期待命中が想定帯に収まる。
     */
    @Test
    void deterministicRainTrajectoriesMatchBalancedSingleTargetHitBand() {
        SkillTargetingService targeting = mock(SkillTargetingService.class);
        SkillCombatService combat = mock(SkillCombatService.class);
        SkillEffectService effects = mock(SkillEffectService.class);
        SkillProjectileService projectiles = mock(SkillProjectileService.class);
        ActiveSkillServices services = new ActiveSkillServices(
                targeting, combat, effects, projectiles,
                mock(SkillMovementService.class),
                mock(TemporarySkillEffectService.class),
                mock(SkillTaskService.class)
        );
        World world = mock(World.class);
        Player player = mock(Player.class);
        Location eye = new Location(world, 0.0D, 65.0D, 0.0D, 0.0F, 0.0F);
        when(player.getEyeLocation()).thenReturn(eye);
        when(player.getWorld()).thenReturn(world);
        AstPlayer astPlayer = mock(AstPlayer.class);
        StatusSnapshot status = DesignTestFixtures.statusSnapshot(Map.of(), 100.0D, 58.0D, 160.0D);
        when(astPlayer.getBukkit()).thenReturn(player);
        when(astPlayer.getStatusSnapshot()).thenReturn(status);
        when(targeting.groundAt(any(Location.class), anyInt(), anyInt())).thenAnswer(invocation -> {
            Location ground = ((Location) invocation.getArgument(0)).clone();
            ground.setY(64.05D);
            return ground;
        });
        PlayerSkillCaster caster = new PlayerSkillCaster(astPlayer);
        PlayerActiveSkillContext context = new PlayerActiveSkillContext(
                new SkillCastContext(
                        definition(), caster, null, List.of(), eye, status,
                        SkillCastTrigger.SYSTEM, Instant.now()
                ),
                caster,
                services
        );

        List<Double> levelOne = expectedHitsByDistance(
                new HunterArrowRainExecutor(services, new Random(20260828L)), context, 3.0D, 45
        );
        List<Double> levelFive = expectedHitsByDistance(
                new HunterArrowRainExecutor(services, new Random(20260828L)), context, 5.0D, 81
        );

        assertTrue(levelOne.stream().allMatch(value -> value >= 10.5D && value <= 17.4D), levelOne.toString());
        assertTrue(levelFive.stream().allMatch(value -> value >= 6.0D && value <= 15.6D), levelFive.toString());
    }

    private static List<Double> expectedHitsByDistance(
            HunterArrowRainExecutor executor,
            PlayerActiveSkillContext context,
            double radius,
            int arrowCount
    ) {
        List<Double> averages = new java.util.ArrayList<>();
        for (double distance : List.of(3.0D, 6.0D, 12.0D, 18.0D)) {
            int hits = 0;
            int repetitions = 250;
            Location center = new Location(context.player().getWorld(), 0.0D, 65.0D, distance);
            BoundingBox target = new BoundingBox(
                    -0.30D, 64.0D, distance - 0.30D,
                    0.30D, 65.8D, distance + 0.30D
            ).expand(0.75D);
            for (int repetition = 0; repetition < repetitions; repetition++) {
                for (SkillBallisticProjectileLaunch launch : executor.createRainVolley(
                        context, center, radius, arrowCount, 0.75D
                )) {
                    if (intersectsTrajectory(launch, target)) {
                        hits++;
                    }
                }
            }
            averages.add((double) hits / repetitions);
        }
        return List.copyOf(averages);
    }

    private static boolean intersectsTrajectory(
            SkillBallisticProjectileLaunch launch,
            BoundingBox target
    ) {
        Location current = launch.origin();
        Vector velocity = launch.spec().initialVelocity();
        for (int tick = 0; tick < launch.spec().maxTicks(); tick++) {
            double distance = velocity.length();
            if (distance > 1.0E-8D && target.rayTrace(
                    current.toVector(), velocity.clone().normalize(), distance
            ) != null) {
                return true;
            }
            current.add(velocity);
            velocity.add(new Vector(0.0D, -launch.spec().gravityPerTick(), 0.0D));
        }
        return false;
    }

    private static SkillDefinition definition() {
        return new SkillDefinition(
                HunterArrowRainExecutor.ID,
                HunterArrowRainExecutor.ID,
                "アローレイン",
                null,
                "SPECTRAL_ARROW",
                List.of(),
                240L,
                8.0D,
                40L,
                1,
                null,
                Map.of(
                        "range", 18.0D,
                        "radius", 3.0D,
                        "arrowCount", 45,
                        "damageRatios", List.of(0.84D, 0.36D),
                        "openingSpeed", 1.60D,
                        "openingHitRadius", 0.45D,
                        "rainHitRadius", 0.75D
                ),
                List.of("active", "ranged", "hunter"),
                SkillKind.ACTIVE,
                true,
                SkillResourceType.ENERGY,
                16.0D
        );
    }
}
