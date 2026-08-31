package io.github.maaasu.astralRecord.feature.boss.service;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageComponent;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.combat.model.DamageSource;
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
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinition;
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
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
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
     * 検証契約: サンバードは残HP30%を含めて陽冠終焉を使う第2段階へ遷移する。
     */
    @Test
    void sunbirdFinalPhaseIncludesThirtyPercentBoundary() {
        BossMechanicProfile profile = BossMechanicProfile.find(BossMechanicProfile.MIDGARD_SAVANNA_SUNBIRD);

        assertEquals(1, profile.phaseForHealth(30.01D, 100.0D));
        assertEquals(2, profile.phaseForHealth(30.0D, 100.0D));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/26-boss/3-メソッド仕様/26_3-サービス.md
     * 章・見出し: # 26_3-サービス > ## 18. ボス固有ギミック
     * 検証契約: 旧30%必殺技の天陽崩落は、第1・第2段階とも通常攻撃と交互に予約する。
     */
    @Test
    void sunbirdSolarNovaBelongsToEveryPhaseRotation() {
        BossMechanicProfile profile = BossMechanicProfile.find(BossMechanicProfile.MIDGARD_SAVANNA_SUNBIRD);

        assertEquals(BossMechanicProfile.Mechanic.SUNBIRD_SOLAR_NOVA, profile.mechanic(1, 0));
        assertEquals(BossMechanicProfile.Mechanic.SUNBIRD_SOLAR_NOVA, profile.mechanic(1, 2));
        assertEquals(BossMechanicProfile.Mechanic.SUNBIRD_SOLAR_NOVA, profile.mechanic(1, 4));
        assertEquals(BossMechanicProfile.Mechanic.SUNBIRD_SOLAR_NOVA, profile.mechanic(2, 0));
        assertEquals(BossMechanicProfile.Mechanic.SUNBIRD_SOLAR_NOVA, profile.mechanic(2, 2));
        assertEquals(BossMechanicProfile.Mechanic.SUNBIRD_SOLAR_NOVA, profile.mechanic(2, 4));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/26-boss/3-メソッド仕様/26_3-サービス.md
     * 章・見出し: # 26_3-サービス > ## 18. ボス固有ギミック
     * 検証契約: 初回監視時に既にHP30%以下でもスポーン地点へ復帰し、陽冠終焉の予兆を開始する。
     */
    @Test
    void sunbirdBelowThresholdOnFirstTickQueuesFinalSkillAndStopCleansDisplays() throws Exception {
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
     * 検証契約: 陽冠終焉は80 tickの予兆後に発動し、専用行動状態とBlockDisplayを解放する。
     */
    @Test
    void sunbirdFinalSkillExecutesAfterTelegraphAndReleasesState() throws Exception {
        SunbirdHarness harness = sunbirdHarness();

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            for (BlockDisplay display : harness.displays()) {
                UUID displayId = display.getUniqueId();
                bukkit.when(() -> Bukkit.getEntity(displayId)).thenReturn(display);
            }
            invokeTick(harness.service());
            for (int index = 0; index < 16; index++) {
                invokeTick(harness.service());
            }
        }

        verifySingleDamage(harness, AttackType.MAGIC, DamageElement.FIRE, 1.95D);
        assertFalse(harness.boss().scriptedAction());
        harness.displays().forEach(display -> verify(display).remove());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/26-boss/3-メソッド仕様/26_3-サービス.md
     * 章・見出し: # 26_3-サービス > ## 18. ボス固有ギミック
     * 検証契約: HP30%より上でも、天陽崩落を通常ローテーションとして詠唱・発動する。
     */
    @Test
    void sunbirdRegularRotationCastsSolarNovaAboveThirtyPercent() throws Exception {
        SunbirdHarness harness = sunbirdHarness(10000.0D);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            for (BlockDisplay display : harness.displays()) {
                UUID displayId = display.getUniqueId();
                bukkit.when(() -> Bukkit.getEntity(displayId)).thenReturn(display);
            }
            for (int index = 0; index < 9; index++) {
                invokeTick(harness.service());
            }

            verify(harness.mobService()).resetPosition(harness.boss(), harness.spawnLocation());
            assertTrue(harness.boss().scriptedAction());

            for (int index = 0; index < 12; index++) {
                invokeTick(harness.service());
            }
        }

        assertFalse(harness.boss().scriptedAction());
        verifySingleDamage(harness, AttackType.MAGIC, DamageElement.FIRE, 1.45D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/26-boss/3-メソッド仕様/26_3-サービス.md
     * 章・見出し: # 26_3-サービス > ## 18. ボス固有ギミック
     * 検証契約: スポーン地点から水平10ブロックより外側のPlayerだけが、1秒周期の外周ダメージを受ける。
     */
    @Test
    void sunbirdArenaPulseDamagesOnlyPlayersOutsideTenBlocks() throws Exception {
        SunbirdHarness outside = sunbirdHarness(10000.0D);
        when(outside.player().getLocation()).thenReturn(outside.spawnLocation().clone().add(10.1D, 0.0D, 0.0D));
        invokeTick(outside.service());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Location>> boundaryLocations = ArgumentCaptor.forClass(Collection.class);
        verify(outside.particleDisplayService()).spawnForNearbyViewers(
            eq(outside.spawnLocation()),
            boundaryLocations.capture(),
            any(SharedParticleDefinition.class)
        );
        assertEquals(48, boundaryLocations.getValue().size());
        boundaryLocations.getValue().forEach(location -> assertEquals(
            10.0D,
            horizontalDistance(location, outside.spawnLocation()),
            0.0001D
        ));
        verifySingleDamage(outside, AttackType.MAGIC, DamageElement.FIRE, 0.18D);

        SunbirdHarness inside = sunbirdHarness(10000.0D);
        invokeTick(inside.service());
        verify(inside.damageService(), never()).attack(
            any(AstEntity.class),
            any(AstEntity.class),
            any(io.github.maaasu.astralRecord.feature.combat.model.AttackType.class),
            org.mockito.ArgumentMatchers.<List<DamageComponent>>any(),
            any(io.github.maaasu.astralRecord.feature.combat.model.DamageSource.class)
        );
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/26-boss/3-メソッド仕様/26_3-サービス.md
     * 章・見出し: # 26_3-サービス > ## 18. ボス固有ギミック
     * 検証契約: 境界外のサンバードは境界内Playerへ帰還タックルし、到達地点を水平9ブロック以内へ収める。
     */
    @Test
    void sunbirdOutsideArenaTacklesTowardSafePlayerAndReturnsInside() throws Exception {
        SunbirdHarness harness = sunbirdHarness(10000.0D);
        Location outsideBossLocation = harness.spawnLocation().clone().add(12.0D, 0.0D, 0.0D);
        harness.entityLocation().set(outsideBossLocation);

        invokeTick(harness.service());
        assertTrue(harness.boss().scriptedAction());
        invokeTick(harness.service());
        invokeTick(harness.service());

        ArgumentCaptor<Location> destinationCaptor = ArgumentCaptor.forClass(Location.class);
        verify(harness.mobService()).resetPosition(eq(harness.boss()), destinationCaptor.capture());
        assertTrue(horizontalDistance(destinationCaptor.getValue(), harness.spawnLocation()) <= 9.0D);
        verifySingleDamage(harness, AttackType.MELEE, DamageElement.FIRE, 1.00D);
        assertFalse(harness.boss().scriptedAction());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/26-boss/3-メソッド仕様/26_3-サービス.md
     * 章・見出し: # 26_3-サービス > ## 18. ボス固有ギミック
     * 検証契約: サンバードは専用行動中の転移を延期し、解除後はスポーン地点から水平7mへ転移する。
     */
    @Test
    void sunbirdPeriodicTeleportPausesDuringScriptedAction() throws Exception {
        SunbirdHarness harness = sunbirdHarness(10000.0D);
        harness.boss().scriptedAction(true);

        for (int index = 0; index < 49; index++) {
            invokeTick(harness.service());
        }
        verify(harness.mobService(), never()).resetPosition(eq(harness.boss()), any(Location.class));

        harness.boss().scriptedAction(false);
        invokeTick(harness.service());

        ArgumentCaptor<Location> destinationCaptor = ArgumentCaptor.forClass(Location.class);
        verify(harness.mobService()).resetPosition(eq(harness.boss()), destinationCaptor.capture());
        assertEquals(
            7.0D,
            horizontalDistance(destinationCaptor.getValue(), harness.spawnLocation()),
            0.0001D
        );
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/26-boss/3-メソッド仕様/26_3-サービス.md
     * 章・見出し: # 26_3-サービス > ## 18. ボス固有ギミック
     * 検証契約: 周囲32ブロックに管理対象Playerがいない間は転移を延期し、復帰後に1回だけ実行する。
     */
    @Test
    void sunbirdPeriodicTeleportWaitsForManagedPlayerToReturn() throws Exception {
        SunbirdHarness harness = sunbirdHarness(10000.0D);
        when(harness.world().getPlayers()).thenReturn(List.of());

        for (int index = 0; index < 49; index++) {
            invokeTick(harness.service());
        }
        verify(harness.mobService(), never()).resetPosition(eq(harness.boss()), any(Location.class));

        when(harness.world().getPlayers()).thenReturn(List.of(harness.player()));
        invokeTick(harness.service());

        verify(harness.mobService(), times(1)).resetPosition(eq(harness.boss()), any(Location.class));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/26-boss/3-メソッド仕様/26_3-サービス.md
     * 章・見出し: # 26_3-サービス > ## 18. ボス固有ギミック
     * 検証契約: HP30%の陽冠終焉と転移期限が重なる場合は陽冠終焉を優先し、80 tickの予兆完了まで転移を延期する。
     */
    @Test
    void sunbirdFinalSkillWinsTeleportDueTickAndTeleportWaitsForTelegraph() throws Exception {
        SunbirdHarness harness = sunbirdHarness(10000.0D);
        harness.boss().scriptedAction(true);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            for (BlockDisplay display : harness.displays()) {
                UUID displayId = display.getUniqueId();
                bukkit.when(() -> Bukkit.getEntity(displayId)).thenReturn(display);
            }
            for (int index = 0; index < 48; index++) {
                invokeTick(harness.service());
            }

            harness.boss().scriptedAction(false);
            harness.boss().currentHealth(3000.0D);
            invokeTick(harness.service());
            verify(harness.mobService(), times(1)).resetPosition(harness.boss(), harness.spawnLocation());
            assertTrue(harness.boss().scriptedAction());

            for (int index = 0; index < 15; index++) {
                invokeTick(harness.service());
            }
            verify(harness.mobService(), times(1)).resetPosition(eq(harness.boss()), any(Location.class));

            invokeTick(harness.service());
        }

        verify(harness.mobService(), times(2)).resetPosition(eq(harness.boss()), any(Location.class));
        assertFalse(harness.boss().scriptedAction());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/26-boss/3-メソッド仕様/26_3-サービス.md
     * 章・見出し: # 26_3-サービス > ## 18. ボス固有ギミック
     * 検証契約: 転移候補はスポーン地点の向きと高さを保ち、6回で同じ地点へ循環する。
     */
    @Test
    void sunbirdTeleportDestinationsPreserveSpawnPoseAndCycleEverySixUses() {
        Location spawn = new Location(null, 12.0D, 80.0D, -4.0D, 135.0F, -10.0F);

        Location first = BossMechanicService.sunbirdTeleportDestination(spawn, 0);
        Location seventh = BossMechanicService.sunbirdTeleportDestination(spawn, 6);

        assertEquals(first, seventh);
        assertEquals(80.0D, first.getY(), 0.0001D);
        assertEquals(135.0F, first.getYaw(), 0.0001F);
        assertEquals(-10.0F, first.getPitch(), 0.0001F);
        assertEquals(7.0D, horizontalDistance(first, spawn), 0.0001D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/26-boss/3-メソッド仕様/26_3-サービス.md
     * 章・見出し: # 26_3-サービス > ## 18. ボス固有ギミック
     * 検証契約: 帰還タックルの到達地点は対象が境界外でも水平9ブロック以内へ丸める。
     */
    @Test
    void sunbirdTackleDestinationStaysInsideArena() {
        Location spawn = new Location(null, 0.0D, 70.0D, 0.0D);
        Location destination = BossMechanicService.sunbirdTackleDestination(
            spawn,
            new Location(null, 20.0D, 68.0D, 0.0D)
        );

        assertEquals(9.0D, horizontalDistance(destination, spawn), 0.0001D);
        assertEquals(69.0D, destination.getY(), 0.0001D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/26-boss/3-メソッド仕様/26_3-サービス.md
     * 章・見出し: # 26_3-サービス > ## 18. ボス固有ギミック
     * 検証契約: 外周particleはスポーン地点から水平10ブロックの円周48地点へ表示する。
     */
    @Test
    void sunbirdArenaBoundaryParticleLocationsStayAtTenBlocks() {
        Location center = new Location(null, 20.0D, 70.0D, -10.0D);
        List<Location> locations = BossMechanicService.circleLocations(center, 10.0D, 48);

        assertEquals(48, locations.size());
        locations.forEach(location -> {
            assertEquals(10.0D, horizontalDistance(location, center), 0.0001D);
            assertEquals(70.15D, location.getY(), 0.0001D);
        });
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
        return sunbirdHarness(3000.0D);
    }

    private static SunbirdHarness sunbirdHarness(double currentHealth) {
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
        AtomicReference<Location> entityLocation = new AtomicReference<>(spawnLocation.clone());
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
        boss.currentHealth(currentHealth);

        when(mobService.getInstances()).thenReturn(List.of(boss));
        when(mobService.getInstance(bossId)).thenReturn(boss);
        when(mobService.entityController()).thenReturn(entityController);
        when(entityController.getEntity(boss)).thenReturn(entity);
        when(entity.isValid()).thenReturn(true);
        when(entity.isDead()).thenReturn(false);
        when(entity.getWorld()).thenReturn(world);
        when(entity.getLocation()).thenAnswer(ignored -> entityLocation.get().clone());
        when(entity.getFacing()).thenReturn(BlockFace.NORTH);
        doAnswer(invocation -> {
            entityLocation.set(invocation.<Location>getArgument(1).clone());
            return null;
        }).when(mobService).resetPosition(eq(boss), any(Location.class));

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
            particleDisplayService,
            boss,
            entity,
            world,
            spawnLocation,
            player,
            playerEntity,
            entityLocation,
            List.copyOf(displays)
        );
    }

    private static double horizontalDistance(Location left, Location right) {
        double x = left.getX() - right.getX();
        double z = left.getZ() - right.getZ();
        return Math.sqrt(x * x + z * z);
    }

    private static void verifySingleDamage(
        SunbirdHarness harness,
        AttackType attackType,
        DamageElement element,
        double ratio
    ) {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DamageComponent>> components = ArgumentCaptor.forClass(List.class);
        verify(harness.damageService()).attack(
            any(AstEntity.class),
            eq(harness.playerEntity()),
            eq(attackType),
            components.capture(),
            eq(DamageSource.SKILL)
        );
        DamageComponent component = components.getValue().getFirst();
        assertEquals(element, component.element());
        assertEquals(ratio, component.ratio(), 0.0001D);
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
        ParticleDisplayService particleDisplayService,
        MobInstance boss,
        Entity entity,
        World world,
        Location spawnLocation,
        Player player,
        AstEntity playerEntity,
        AtomicReference<Location> entityLocation,
        List<BlockDisplay> displays
    ) {
    }
}
