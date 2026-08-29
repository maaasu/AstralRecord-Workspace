package io.github.maaasu.astralRecord.feature.boss.service;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.DamageComponent;
import io.github.maaasu.astralRecord.feature.combat.service.DamageService;
import io.github.maaasu.astralRecord.feature.dungeon.service.DungeonService;
import io.github.maaasu.astralRecord.feature.mob.model.MobCategory;
import io.github.maaasu.astralRecord.feature.mob.model.MobEquipmentConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobIdleConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.model.MobInteractionsConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobShieldConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobTemplate;
import io.github.maaasu.astralRecord.feature.mob.model.MobVariantConfig;
import io.github.maaasu.astralRecord.feature.mob.service.MobEntityController;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import org.bukkit.Bukkit;
import org.bukkit.block.BlockFace;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BossMechanicServiceTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/26-boss/3-メソッド仕様/26_3-サービス.md
     * 章・見出し: # 26_3-サービス > ## 18. ボス固有ギミック
     * 検証契約: 最大HP比70%以下を第2段階、35%以下を第3段階として境界値を含めて判定する。
     */
    @Test
    void healthThresholdsIncludeSeventyAndThirtyFivePercentBoundaries() {
        assertEquals(1, BossMechanicProfile.phaseFor(70.01D, 100.0D));
        assertEquals(2, BossMechanicProfile.phaseFor(70.0D, 100.0D));
        assertEquals(2, BossMechanicProfile.phaseFor(35.01D, 100.0D));
        assertEquals(3, BossMechanicProfile.phaseFor(35.0D, 100.0D));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/26-boss/3-メソッド仕様/26_3-サービス.md
     * 章・見出し: # 26_3-サービス > ## 18. ボス固有ギミック
     * 検証契約: サンバードは残HP30%を含めて第2段階へ遷移する。
     */
    @Test
    void sunbirdUltimatePhaseIncludesThirtyPercentBoundary() {
        BossMechanicProfile profile = BossMechanicProfile.find(BossMechanicProfile.MIDGARD_SAVANNA_SUNBIRD);

        assertEquals(1, profile.phaseForHealth(30.01D, 100.0D));
        assertEquals(2, profile.phaseForHealth(30.0D, 100.0D));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/26-boss/3-メソッド仕様/26_3-サービス.md
     * 章・見出し: # 26_3-サービス > ## 18. ボス固有ギミック
     * 検証契約: 初回監視時に既にHP30%以下でもスポーン地点へ復帰し、必殺技予兆を開始する。
     */
    @Test
    void sunbirdBelowThresholdOnFirstTickQueuesUltimateAndStopCleansDisplays() throws Exception {
        SunbirdHarness harness = sunbirdHarness();

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            for (BlockDisplay display : harness.displays()) {
                UUID displayId = display.getUniqueId();
                bukkit.when(() -> Bukkit.getEntity(displayId)).thenReturn(display);
            }
            invokeTick(harness.service());

            verify(harness.mobService()).resetPosition(harness.boss(), harness.spawnLocation());
            assertTrue(harness.boss().scriptedAction());
            verify(harness.world(), times(8)).spawn(
                any(Location.class),
                eq(BlockDisplay.class),
                org.mockito.ArgumentMatchers.<Consumer<? super BlockDisplay>>any()
            );
            harness.service().stop();
        }

        assertFalse(harness.boss().scriptedAction());
        harness.displays().forEach(display -> verify(display).remove());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/26-boss/3-メソッド仕様/26_3-サービス.md
     * 章・見出し: # 26_3-サービス > ## 18. ボス固有ギミック
     * 検証契約: 必殺技は60 tickの予兆後に発動し、専用行動状態とBlockDisplayを解放する。
     */
    @Test
    void sunbirdUltimateExecutesAfterTelegraphAndReleasesState() throws Exception {
        SunbirdHarness harness = sunbirdHarness();

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            for (BlockDisplay display : harness.displays()) {
                UUID displayId = display.getUniqueId();
                bukkit.when(() -> Bukkit.getEntity(displayId)).thenReturn(display);
            }
            invokeTick(harness.service());
            for (int index = 0; index < 12; index++) {
                invokeTick(harness.service());
            }
        }

        verify(harness.damageService()).attack(
            any(AstEntity.class),
            eq(harness.playerEntity()),
            eq(io.github.maaasu.astralRecord.feature.combat.model.AttackType.MAGIC),
            org.mockito.ArgumentMatchers.<List<DamageComponent>>any(),
            eq(io.github.maaasu.astralRecord.feature.combat.model.DamageSource.SKILL)
        );
        assertFalse(harness.boss().scriptedAction());
        harness.displays().forEach(display -> verify(display).remove());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/26-boss/3-メソッド仕様/26_3-サービス.md
     * 章・見出し: # 26_3-サービス > ## 18. ボス固有ギミック
     * 検証契約: Dungeon worldでは対象Bossの地形破壊を拒否し、Dungeon外では対象Bossだけを許可する。
     */
    @Test
    void terrainPermissionRejectsDungeonAndUnknownBosses() {
        assertTrue(BossTerrainPolicy.mayBreak(BossMechanicProfile.TWILIGHT_COLOSSUS, false));
        assertFalse(BossTerrainPolicy.mayBreak(BossMechanicProfile.TWILIGHT_COLOSSUS, true));
        assertFalse(BossTerrainPolicy.mayBreak("unknown_boss", false));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/26-boss/3-メソッド仕様/26_3-サービス.md
     * 章・見出し: # 26_3-サービス > ## 18. ボス固有ギミック
     * 検証契約: 自然地形を許可し、コンテナ・基盤ブロック・機能ブロックを破壊対象から除外する。
     */
    @Test
    void terrainWhitelistExcludesProtectedAndFunctionalBlocks() {
        assertTrue(BossTerrainPolicy.isBreakable(Material.STONE));
        assertTrue(BossTerrainPolicy.isBreakable(Material.DIRT));
        assertFalse(BossTerrainPolicy.isBreakable(Material.BEDROCK));
        assertFalse(BossTerrainPolicy.isBreakable(Material.CHEST));
        assertFalse(BossTerrainPolicy.isBreakable(Material.COMMAND_BLOCK));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/26-boss/3-メソッド仕様/26_3-サービス.md
     * 章・見出し: # 26_3-サービス > ## 18. ボス固有ギミック
     * 検証契約: 線形予兆は前方の長さと幅の内側だけを命中範囲として扱う。
     */
    @Test
    void lineAreaIncludesOnlyForwardPointsWithinLengthAndWidth() {
        Location origin = new Location(null, 0.0D, 64.0D, 0.0D);
        Vector direction = new Vector(0.0D, 0.0D, 1.0D);

        assertTrue(BossMechanicService.insideLine(
            new Location(null, 1.4D, 64.0D, 8.0D), origin, direction, 12.0D, 1.5D
        ));
        assertFalse(BossMechanicService.insideLine(
            new Location(null, 1.6D, 64.0D, 8.0D), origin, direction, 12.0D, 1.5D
        ));
        assertFalse(BossMechanicService.insideLine(
            new Location(null, 0.0D, 64.0D, -1.0D), origin, direction, 12.0D, 1.5D
        ));
        assertFalse(BossMechanicService.insideLine(
            new Location(null, 0.0D, 64.0D, 12.1D), origin, direction, 12.0D, 1.5D
        ));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/26-boss/3-メソッド仕様/26_3-サービス.md
     * 章・見出し: # 26_3-サービス > ## 18. ボス固有ギミック
     * 検証契約: 交差ルーンの表示地点は、命中帯の半幅1ブロックに一致する中心線と両境界線を含む。
     */
    @Test
    void runeLanesParticleLocationsIncludeDamageWidthBoundaries() {
        List<Location> locations = BossMechanicService.crossParticleLocations(
            new Location(null, 0.0D, 64.0D, 0.0D),
            new Vector(0.0D, 0.0D, 1.0D),
            32.0D,
            1.0D
        );

        assertTrue(locations.contains(new Location(null, 0.0D, 64.15D, 8.0D)));
        assertTrue(locations.contains(new Location(null, 1.0D, 64.15D, 8.0D)));
        assertTrue(locations.contains(new Location(null, -1.0D, 64.15D, 8.0D)));
        assertTrue(locations.contains(new Location(null, 8.0D, 64.15D, 1.0D)));
        assertTrue(locations.contains(new Location(null, 8.0D, 64.15D, -1.0D)));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/26-boss/3-メソッド仕様/26_3-サービス.md
     * 章・見出し: # 26_3-サービス > ## 18. ボス固有ギミック
     * 検証契約: 交差ルーンはレイトレースした壁の地点で止まり、壁なし時は32ブロックを超えず、非通過ブロックを無視しない。
     */
    @Test
    void runeLanesResolveWallHitAndSafeMaximumThroughRayTrace() {
        World world = mock(World.class);
        Location origin = new Location(world, 0.0D, 64.0D, 0.0D);
        Vector direction = new Vector(0.0D, 0.0D, 8.0D);
        RayTraceResult hit = mock(RayTraceResult.class);
        when(hit.getHitPosition()).thenReturn(new Vector(0.0D, 64.0D, 9.5D));
        when(world.rayTraceBlocks(
            any(Location.class),
            any(Vector.class),
            eq(32.0D),
            eq(FluidCollisionMode.NEVER),
            eq(true)
        )).thenReturn(hit);

        assertEquals(9.5D, BossMechanicService.wallLimitedLength(origin, direction, 48.0D), 0.0001D);

        when(world.rayTraceBlocks(
            any(Location.class),
            any(Vector.class),
            eq(32.0D),
            eq(FluidCollisionMode.NEVER),
            eq(true)
        )).thenReturn(null);
        assertEquals(32.0D, BossMechanicService.wallLimitedLength(origin, direction, 48.0D), 0.0001D);

        ArgumentCaptor<Vector> directionCaptor = ArgumentCaptor.forClass(Vector.class);
        verify(world, times(2)).rayTraceBlocks(
            eq(origin),
            directionCaptor.capture(),
            eq(32.0D),
            eq(FluidCollisionMode.NEVER),
            eq(true)
        );
        directionCaptor.getAllValues().forEach(captured -> {
            assertEquals(0.0D, captured.getX(), 0.0001D);
            assertEquals(0.0D, captured.getY(), 0.0001D);
            assertEquals(1.0D, captured.getZ(), 0.0001D);
        });
    }

    private static SunbirdHarness sunbirdHarness() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        MobService mobService = mock(MobService.class);
        MobEntityController entityController = mock(MobEntityController.class);
        DamageService damageService = mock(DamageService.class);
        DungeonService dungeonService = mock(DungeonService.class);
        ParticleDisplayService particleDisplayService = mock(ParticleDisplayService.class);
        Entity entity = mock(Entity.class);
        World world = mock(World.class);
        Player player = mock(Player.class);
        AstEntity playerEntity = mock(AstEntity.class);
        UUID bossId = UUID.randomUUID();
        Location spawnLocation = new Location(world, 20.0D, 70.0D, -10.0D);
        MobTemplate template = new MobTemplate(
            1,
            BossMechanicProfile.MIDGARD_SAVANNA_SUNBIRD,
            MobCategory.BOSS,
            "Sunbird",
            null,
            10,
            EntityType.PARROT,
            true,
            null,
            List.of(),
            List.of(),
            null,
            MobVariantConfig.DEFAULT,
            MobEquipmentConfig.EMPTY,
            List.of(),
            MobShieldConfig.EMPTY,
            MobIdleConfig.defaults(),
            false,
            MobInteractionsConfig.EMPTY,
            null,
            null,
            null
        );
        MobInstance boss = new MobInstance(bossId, template, spawnLocation);
        boss.maxHealth(10000.0D);
        boss.currentHealth(3000.0D);

        when(mobService.getInstances()).thenReturn(List.of(boss));
        when(mobService.getInstance(bossId)).thenReturn(boss);
        when(mobService.entityController()).thenReturn(entityController);
        when(entityController.getEntity(boss)).thenReturn(entity);
        when(entity.isValid()).thenReturn(true);
        when(entity.isDead()).thenReturn(false);
        when(entity.getWorld()).thenReturn(world);
        when(entity.getLocation()).thenReturn(spawnLocation);
        when(entity.getFacing()).thenReturn(BlockFace.NORTH);

        when(player.isValid()).thenReturn(true);
        when(player.getLocation()).thenReturn(spawnLocation.clone().add(2.0D, 0.0D, 0.0D));
        when(player.getVelocity()).thenReturn(new Vector());
        when(world.getPlayers()).thenReturn(List.of(player));
        when(damageService.resolveEntity(player)).thenReturn(playerEntity);
        when(playerEntity.isPlayer()).thenReturn(true);

        List<BlockDisplay> displays = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            BlockDisplay display = mock(BlockDisplay.class);
            when(display.getUniqueId()).thenReturn(UUID.randomUUID());
            when(display.isValid()).thenReturn(true);
            displays.add(display);
        }
        AtomicInteger displayIndex = new AtomicInteger();
        when(world.spawn(
            any(Location.class),
            eq(BlockDisplay.class),
            org.mockito.ArgumentMatchers.<Consumer<? super BlockDisplay>>any()
        )).thenAnswer(ignored -> displays.get(displayIndex.getAndIncrement()));

        BossMechanicService service = new BossMechanicService(
            plugin,
            mobService,
            damageService,
            dungeonService,
            particleDisplayService
        );
        return new SunbirdHarness(
            service,
            mobService,
            damageService,
            boss,
            world,
            spawnLocation,
            playerEntity,
            List.copyOf(displays)
        );
    }

    private static void invokeTick(BossMechanicService service) throws Exception {
        Method tick = BossMechanicService.class.getDeclaredMethod("tick");
        tick.setAccessible(true);
        tick.invoke(service);
    }

    private record SunbirdHarness(
        BossMechanicService service,
        MobService mobService,
        DamageService damageService,
        MobInstance boss,
        World world,
        Location spawnLocation,
        AstEntity playerEntity,
        List<BlockDisplay> displays
    ) {
    }
}
