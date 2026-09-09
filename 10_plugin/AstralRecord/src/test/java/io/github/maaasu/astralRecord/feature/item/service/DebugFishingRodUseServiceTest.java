package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryEntryModel;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipment;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentSlot;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.model.ItemReference;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.masterdata.tag.MasterTagIds;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.SoundCategory;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.BoundingBox;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

class DebugFishingRodUseServiceTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### デバッグ釣り竿仮想キャスト
     * 検証契約: 釣り竿先端は視線の前方・右・上の基底オフセットから決まり、視線正面では右上前方へ移動する。
     */
    @Test
    void rodTipUsesForwardRightAndUpBasisFromEye() {
        Location tip = DebugFishingRodUseService.resolveRodTip(
            new Location(null, 0.0D, 10.0D, 0.0D),
            new Vector(0.0D, 0.0D, -1.0D)
        );

        assertNotNull(tip);
        assertEquals(0.35D, tip.getX(), 0.0001D);
        assertEquals(10.20D, tip.getY(), 0.0001D);
        assertEquals(-0.35D, tip.getZ(), 0.0001D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### デバッグ釣り竿仮想キャスト
     * 検証契約: 糸は表示ノードの曲線に沿う小さい黒色dustをまとめて送信し、長距離でも各区間4点以内とする。
     */
    @Test
    void renderRopeUsesPhysicalNodePositionsWithBoundedBlackDust() {
        assertEquals(1, DebugFishingRodUseService.LINE_PARTICLE_INTERVAL_TICKS);
        FishingFixture fixture = fishingFixture();
        World world = fixture.bukkitPlayer().getWorld();
        when(world.getPlayers()).thenReturn(List.of(fixture.bukkitPlayer()));
        Location rod = new Location(world, 0, 64, 0);
        DebugFishingRodUseService.ActiveCast active = activeCast(rod, rod);
        active.ropeNodes.set(1, new Location(world, 0, 60, 0));
        for (int index = 2; index < active.ropeNodes.size(); index++) {
            active.ropeNodes.set(index, new Location(world, 100_000, 60, 0));
        }
        fixture.service().renderRope(active);
        ArgumentCaptor<java.util.Collection<Location>> points = ArgumentCaptor.captor();
        verify(fixture.particles()).spawnForNearbyViewers(eq(rod), points.capture(),
            eq(SharedParticleDefinitions.FISHING_ROD_LINE));
        List<Location> rendered = List.copyOf(points.getValue());
        assertEquals(8, rendered.size());
        assertEquals(rod, rendered.getFirst());
        assertEquals(active.ropeNodes.get(1), rendered.get(4));
        for (int index = 0; index < 4; index++) {
            assertEquals(0, rendered.get(index).getX());
            assertEquals(60, rendered.get(index + 4).getY());
        }
        org.bukkit.Particle.DustOptions dust = (org.bukkit.Particle.DustOptions)
            SharedParticleDefinitions.FISHING_ROD_LINE.data();
        assertEquals(org.bukkit.Color.BLACK, dust.getColor());
        assertEquals(0.15F, dust.getSize());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### デバッグ釣り竿仮想キャスト
     * 検証契約: 距離ステータス35で35mへ発射し、1tick補間、回収音1回、回収後の全Entity・task解放と再発射を満たす。
     */
    @Test
    void retrievesToRodAndCleansUpBeforeRecasting() {
        FishingFixture fixture = fishingFixture();
        when(fixture.status().rollValue(StatusType.CAST_DISTANCE)).thenReturn(35.0D);
        fixture.service().cast(fixture.astPlayer());
        assertEquals(1, fixture.displays().size(), "針だけをBlockDisplayとして生成する");
        ArgumentCaptor<Runnable> runnable = ArgumentCaptor.forClass(Runnable.class);
        verify(fixture.scheduler()).runTaskTimer(any(), runnable.capture(), eq(1L), eq(1L));
        for (BlockDisplay display : fixture.displays()) {
            verify(display).setTeleportDuration(1);
            verify(display).setInterpolationDuration(1);
        }
        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class)) {
            cache.when(() -> AstPlayerCache.get(fixture.bukkitPlayer())).thenReturn(fixture.astPlayer());
            for (int tick = 0; tick < 24; tick++) {
                runnable.getValue().run();
            }
            ArgumentCaptor<Location> hookPositions = ArgumentCaptor.forClass(Location.class);
            verify(fixture.displays().getFirst(), times(25)).teleport(hookPositions.capture());
            Location tip = DebugFishingRodUseService.resolveRodTip(fixture.bukkitPlayer().getEyeLocation(),
                fixture.bukkitPlayer().getEyeLocation().getDirection());
            assertEquals(35.0D, hookPositions.getValue().distance(tip), 1.0E-6D);
            fixture.service().retract(fixture.astPlayer());
            fixture.service().retract(fixture.astPlayer());
            assertTrue(fixture.service().isCasting(fixture.astPlayer()));
            for (int tick = 0; tick < 24; tick++) {
                runnable.getValue().run();
            }
        }
        assertFalse(fixture.service().isCasting(fixture.astPlayer()));
        assertTrue(fixture.service().canCast(fixture.astPlayer()));
        for (BlockDisplay display : fixture.displays()) {
            verify(display).remove();
        }
        verify(fixture.task()).cancel();
        verify(fixture.bukkitPlayer()).playSound(any(Location.class),
            eq(Sound.ENTITY_FISHING_BOBBER_RETRIEVE), eq(SoundCategory.PLAYERS), anyFloat(), anyFloat());
        fixture.service().cast(fixture.astPlayer());
        assertTrue(fixture.service().isCasting(fixture.astPlayer()));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### デバッグ釣り竿仮想キャスト
     * 検証契約: キャスト成功時は投擲音をプレイヤーへ1回再生する。
     */
    @Test
    void castPlaysThrowSoundOnce() {
        FishingFixture fixture = fishingFixture();

        fixture.service().cast(fixture.astPlayer());

        verify(fixture.bukkitPlayer(), times(1)).playSound(
            eq(fixture.playerLocation()),
            eq(DebugFishingRodUseService.CAST_SOUND),
            eq(SoundCategory.PLAYERS),
            anyFloat(),
            anyFloat()
        );
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### デバッグ釣り竿仮想キャスト
     * 検証契約: 針の着水位置で音と水しぶきを一度だけ再生し、その後は水中を沈み、底の固体へ衝突する。
     */
    @Test
    void waterEntryPlaysSplashOnceAndSinkingStopsAtBottom() {
        FishingFixture fixture = fishingFixture();
        World world = scene(new BoundingBox(-100, -2, -100, 100, -1, 100), true);
        Location start = new Location(world, 0, 0.3D, 0);
        DebugFishingRodUseService.ActiveCast active = activeCast(start, start.clone().add(0, -4, 0));
        DebugFishingRodUseService.advanceHook(active);
        assertNotNull(active.pendingWaterImpact);
        Location impact = active.pendingWaterImpact.clone();
        assertEquals(0, impact.getY(), 0.001D);
        fixture.service.emitWaterImpact(active);
        assertNull(active.pendingWaterImpact);
        for (int tick = 0; tick < 40; tick++) {
            DebugFishingRodUseService.advanceHook(active);
            DebugFishingRodUseService.advanceRope(active);
            fixture.service.emitWaterImpact(active);
            assertTrue(active.currentHook.getY() >= -1.001D);
        }
        assertTrue(active.currentHook.getY() < -0.1D);
        verify(world, times(1)).playSound(eq(impact), eq(Sound.ENTITY_FISHING_BOBBER_SPLASH),
            eq(SoundCategory.PLAYERS), anyFloat(), anyFloat());
        verify(fixture.particles, times(1)).spawnForNearbyViewers(eq(impact),
            eq(SharedParticleDefinitions.FISHING_ROD_SPLASH));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### デバッグ釣り竿仮想キャスト
     * 検証契約: 1tickの移動が長くなっても途中の固体blockを飛び越えない。
     */
    @Test
    void fastHookChecksBeyondFirstRayChunk() {
        World world = scene(new BoundingBox(3, -100, -100, 4, 100, 100), false);
        Location start = new Location(world, 0, 10, 0);
        DebugFishingRodUseService.ActiveCast active = activeCast(start, start.clone().add(100, 0, 0));
        active.velocity = new Vector(10, 0, 0);
        DebugFishingRodUseService.advanceHook(active);
        assertEquals(2.999D, active.currentHook.getX(), 0.0001D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### デバッグ釣り竿仮想キャスト
     * 検証契約: 正の小さなキャスト距離でも針と糸へ最小長を上乗せしない。
     */
    @Test
    void tinyCastDoesNotAddArtificialMinimumLength() {
        World world = scene(null, false);
        Location start = new Location(world, 0, 10, 0);
        DebugFishingRodUseService.ActiveCast active = activeCast(start, start.clone().add(0.0005D, 0, 0));
        for (int tick = 0; tick < 10; tick++) {
            DebugFishingRodUseService.advanceHook(active);
            DebugFishingRodUseService.advanceRope(active);
            assertTrue(active.currentHook.distance(start) <= 0.000501D);
            assertTrue(DebugFishingRodUseService.polylineLength(active.ropeNodes) <= 0.000501D);
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### デバッグ釣り竿仮想キャスト
     * 検証契約: 標準4 blockキャストの自由空間更新は1tickの同期衝突探索を128回以内に抑える。
     */
    @Test
    void ordinaryCastHasBoundedCollisionQueries() {
        World world = scene(null, false);
        Location start = new Location(world, 0, 10, 0);
        DebugFishingRodUseService.ActiveCast active = activeCast(start, start.clone().add(4, 0, 0));
        for (int tick = 0; tick < 80; tick++) {
            clearInvocations(world);
            DebugFishingRodUseService.advanceHook(active);
            DebugFishingRodUseService.advanceRope(active);
            long queries = mockingDetails(world).getInvocations().stream()
                .filter(call -> call.getMethod().getName().equals("rayTraceBlocks")).count();
            assertTrue(queries <= 128, "tick=" + tick + ", queries=" + queries);
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### デバッグ釣り竿仮想キャスト
     * 検証契約: 水面の2m上から水平に35をキャストすると、針と糸を同時更新しても35m先まで届き、最大糸長を超えない。
     */
    @Test
    void castDistanceThirtyFiveReachesThirtyFiveMeters() {
        World world = scene(null, true);
        Location start = new Location(world, 0, 2, 0);
        DebugFishingRodUseService.ActiveCast active = activeCast(start, start.clone().add(35, 0, 0));
        double furthest = 0;
        for (int tick = 0; tick < 80; tick++) {
            DebugFishingRodUseService.advanceHook(active);
            DebugFishingRodUseService.advanceRope(active);
            furthest = Math.max(furthest, active.currentHook.getX());
            assertTrue(active.currentHook.distance(active.rodTip) <= 35.001D);
            assertTrue(DebugFishingRodUseService.polylineLength(active.ropeNodes) <= 35.001D);
            if (active.inWater) {
                break;
            }
        }
        assertTrue(furthest >= 34.99D, "35m指定の実到達距離=" + furthest);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### デバッグ釣り竿仮想キャスト
     * 検証契約: 地面・壁・水底に接触した針は、竿先が範囲内で動いても回収するまでその位置を保持する。
     */
    @Test
    void solidContactPinsHookUntilRetrieval() {
        for (boolean water : List.of(false, true)) {
            World world = scene(new BoundingBox(-100, -2, -100, 100, -1, 100), water);
            Location rod = new Location(world, 0, 0.3D, 0);
            DebugFishingRodUseService.ActiveCast active = activeCast(rod, rod.clone().add(0, -4, 0));
            for (int tick = 0; tick < 40; tick++) {
                DebugFishingRodUseService.advanceHook(active);
                DebugFishingRodUseService.advanceRope(active);
            }
            assertEquals(DebugFishingRodUseService.CastPhase.GROUNDED, active.phase);
            Location landed = active.currentHook.clone();
            active.rodTip.add(0.3, 0.1, 0.2);
            for (int tick = 0; tick < 100; tick++) {
                DebugFishingRodUseService.advanceHook(active);
                DebugFishingRodUseService.advanceRope(active);
                assertEquals(landed, active.currentHook);
            }
            active.phase = DebugFishingRodUseService.CastPhase.RETRACTING;
            assertTrue(DebugFishingRodUseService.advanceHook(active));
            assertEquals(active.rodTip, active.currentHook);
        }
        World wallWorld = scene(new BoundingBox(2, -100, -100, 3, 100, 100), false);
        Location rod = new Location(wallWorld, 0, 10, 0);
        DebugFishingRodUseService.ActiveCast active = activeCast(rod, rod.clone().add(4, 0, 0));
        for (int tick = 0; tick < 10; tick++) {
            DebugFishingRodUseService.advanceHook(active);
        }
        Location landed = active.currentHook.clone();
        assertEquals(DebugFishingRodUseService.CastPhase.GROUNDED, active.phase);
        assertEquals(1.999D, landed.getX(), 0.0001D);
        for (int tick = 0; tick < 100; tick++) {
            DebugFishingRodUseService.advanceHook(active);
            assertEquals(landed, active.currentHook);
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### デバッグ釣り竿仮想キャスト
     * 検証契約: 着水後の沈下は以前の到達距離で止まらず、真下へCAST_DISTANCEまで進んで停止し、水平位置を変えない。
     */
    @Test
    void sinkingUsesFullCastDistanceAndStopsWithoutLateralDrift() {
        World world = scene(null, true);
        Location rod = new Location(world, 0, 1, 0);
        DebugFishingRodUseService.ActiveCast active = new DebugFishingRodUseService.ActiveCast(
            "rod", rod, new Location(world, 3, 0, 0), new Location(world, 3, -8, 0),
            5, true, mock(BlockDisplay.class));
        for (int tick = 0; tick < 200; tick++) {
            DebugFishingRodUseService.advanceHook(active);
            DebugFishingRodUseService.advanceRope(active);
            assertEquals(3, active.currentHook.getX(), 1.0E-10);
            assertEquals(0, active.currentHook.getZ(), 1.0E-10);
            assertTrue(active.currentHook.distance(rod) <= 5.0D + 1.0E-10);
            assertTrue(DebugFishingRodUseService.polylineLength(active.ropeNodes) <= 5.0D + 1.0E-10);
        }
        assertEquals(-3, active.currentHook.getY(), 1.0E-8);
        assertEquals(DebugFishingRodUseService.CastPhase.SINKING, active.phase);
        Location stopped = active.currentHook.clone();
        DebugFishingRodUseService.advanceHook(active);
        assertEquals(stopped.getY(), active.currentHook.getY(), 1.0E-10);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### デバッグ釣り竿仮想キャスト
     * 検証契約: 投擲・落下中の移動は全方向と大きな初速で最大距離を超えず、描画曲線も同じ上限以内に収まる。
     */
    @Test
    void airborneHookNeverExceedsCastDistance() {
        for (Vector direction : List.of(new Vector(1, 0, 0), new Vector(0, 1, 0),
            new Vector(0, -1, 0), new Vector(-1, 2, 3), new Vector(1, -2, -3))) {
            World world = scene(null, false);
            Location rod = new Location(world, 0, 10, 0);
            DebugFishingRodUseService.ActiveCast active = activeCast(rod,
                rod.clone().add(direction.clone().normalize().multiply(4)));
            active.velocity = direction.clone().multiply(10);
            for (int tick = 0; tick < 100; tick++) {
                DebugFishingRodUseService.advanceHook(active);
                DebugFishingRodUseService.advanceRope(active);
                assertTrue(active.currentHook.distance(rod) <= 4.0D + 1.0E-10);
                assertTrue(DebugFishingRodUseService.polylineLength(active.ropeNodes) <= 4.0D + 1.0E-10);
            }
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### デバッグ釣り竿仮想キャスト
     * 検証契約: 糸は両端から作る安定した浅い曲線で、過去のノード位置や経過tickに影響されず針を引かない。
     */
    @Test
    void lineIsStableShallowCurveAndDoesNotMoveHook() {
        World world = scene(null, false);
        Location rod = new Location(world, 0, 10, 0);
        DebugFishingRodUseService.ActiveCast active = activeCast(rod, rod.clone().add(10, 0, 0));
        active.currentHook = rod.clone().add(4, 0, 0);
        Location hook = active.currentHook.clone();
        DebugFishingRodUseService.advanceRope(active);
        List<Location> expected = active.ropeNodes.stream().map(Location::clone).toList();
        assertTrue(expected.get(10).getY() < rod.getY());
        for (int tick = 0; tick < 100; tick++) {
            active.ropeNodes.set(7, new Location(world, 100, -50, 100));
            DebugFishingRodUseService.advanceRope(active);
            assertEquals(expected, active.ropeNodes);
            assertEquals(hook, active.currentHook);
            for (int index = 0; index < expected.size(); index++) {
                assertEquals(index * 0.2D, expected.get(index).getX(), 1.0E-10);
                assertEquals(0, expected.get(index).getZ(), 1.0E-10);
                assertTrue(expected.get(index).getY() >= rod.getY() - 0.35D);
            }
        }
        active.currentHook = rod.clone().add(10, 0, 0);
        DebugFishingRodUseService.advanceRope(active);
        for (Location node : active.ropeNodes) {
            assertEquals(10, node.getY(), 1.0E-10);
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### デバッグ釣り竿仮想キャスト
     * 検証契約: プレイヤー移動で現在の竿先から針までが最大距離を超えた場合は、針を動かさずキャストと描画taskを終了する。
     */
    @Test
    void movingOutsideRangeCancelsCastAndRemovesHook() {
        FishingFixture fixture = fishingFixture();
        fixture.service().cast(fixture.astPlayer());
        ArgumentCaptor<Runnable> runnable = ArgumentCaptor.forClass(Runnable.class);
        verify(fixture.scheduler()).runTaskTimer(any(), runnable.capture(), eq(1L), eq(1L));
        Location movedEye = fixture.bukkitPlayer().getEyeLocation().clone().add(10, 0, 0);
        when(fixture.bukkitPlayer().getEyeLocation()).thenReturn(movedEye);
        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class)) {
            cache.when(() -> AstPlayerCache.get(fixture.bukkitPlayer())).thenReturn(fixture.astPlayer());
            runnable.getValue().run();
        }
        assertFalse(fixture.service().isCasting(fixture.astPlayer()));
        verify(fixture.task()).cancel();
        verify(fixture.displays().getFirst()).remove();
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### デバッグ釣り竿仮想キャスト
     * 検証契約: 衝突探索の予算切れは固体着地と区別し、未検査区間へ進まず次tickで移動を継続する。
     */
    @Test
    void exhaustedRayBudgetDoesNotGroundHookInMidair() {
        World world = scene(new BoundingBox(100, -100, -100, 101, 100, 100), false);
        Location rod = new Location(world, 0, 10, 0);
        DebugFishingRodUseService.ActiveCast active = activeCast(rod, rod.clone().add(200, 0, 0));
        active.velocity = new Vector(1000, 0, 0);
        DebugFishingRodUseService.advanceHook(active);
        assertEquals(66, active.currentHook.getX(), 1.0E-8);
        assertEquals(DebugFishingRodUseService.CastPhase.OUTBOUND, active.phase);
        assertEquals(1000, active.velocity.getX(), 1.0E-8);
        DebugFishingRodUseService.advanceHook(active);
        assertEquals(99.999D, active.currentHook.getX(), 1.0E-8);
        assertEquals(DebugFishingRodUseService.CastPhase.GROUNDED, active.phase);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### デバッグ釣り竿仮想キャスト
     * 検証契約: 空中で距離上限へ達した針は、上限内で落下を続けて地面へ着地する。
     */
    @Test
    void airborneLimitStillAllowsFallingAndLanding() {
        World world = scene(new BoundingBox(-100, -1, -100, 100, 0, 100), false);
        Location rod = new Location(world, 0, 2, 0);
        DebugFishingRodUseService.ActiveCast active = activeCast(rod, rod.clone().add(4, 0, 0));
        active.currentHook = rod.clone().add(4, 0, 0);
        active.velocity.zero();
        for (int tick = 0; tick < 100; tick++) {
            DebugFishingRodUseService.advanceHook(active);
            assertTrue(active.currentHook.distance(rod) <= 4.0D + 1.0E-10);
        }
        assertEquals(DebugFishingRodUseService.CastPhase.GROUNDED, active.phase);
        assertEquals(0.001D, active.currentHook.getY(), 1.0E-8);
    }

    /** 幾何学的な固体boxと任意の水面を持つworldを、移動区間に応じて応答させます。 */
    private static World scene(BoundingBox solid, boolean water) {
        World world = mock(World.class);
        Block air = mock(Block.class);
        when(air.getType()).thenReturn(Material.AIR);
        when(air.isPassable()).thenReturn(true);
        Block stone = mock(Block.class);
        when(stone.getType()).thenReturn(Material.STONE);
        when(stone.isPassable()).thenReturn(false);
        when(stone.getBoundingBox()).thenReturn(solid);
        Block liquid = mock(Block.class);
        when(liquid.getType()).thenReturn(Material.WATER);
        when(liquid.isPassable()).thenReturn(true);
        BoundingBox waterBox = new BoundingBox(-100, -100, -100, 100, 0, 100);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(call -> {
            Vector point = new Vector((int) call.getArgument(0) + 0.5D,
                (int) call.getArgument(1) + 0.5D, (int) call.getArgument(2) + 0.5D);
            return solid != null && solid.contains(point) ? stone
                : water && waterBox.contains(point) ? liquid : air;
        });
        when(world.getBlockAt(any(Location.class))).thenAnswer(call -> {
            Location location = call.getArgument(0);
            return world.getBlockAt(location.getBlockX(), location.getBlockY(), location.getBlockZ());
        });
        when(world.rayTraceBlocks(any(Location.class), any(Vector.class), anyDouble(),
            any(FluidCollisionMode.class), eq(false))).thenAnswer(call -> {
                Vector origin = ((Location) call.getArgument(0)).toVector();
                Vector direction = call.getArgument(1);
                double distance = call.getArgument(2);
                RayTraceResult hit = solid == null ? null : traceBox(solid, origin, direction, distance);
                Block hitBlock = stone;
                if (water && call.getArgument(3) != FluidCollisionMode.NEVER && !waterBox.contains(origin)) {
                    RayTraceResult waterHit = traceBox(waterBox, origin, direction, distance);
                    if (waterHit != null && (hit == null || origin.distanceSquared(waterHit.getHitPosition())
                        < origin.distanceSquared(hit.getHitPosition()))) {
                        hit = waterHit;
                        hitBlock = liquid;
                    }
                }
                return hit == null ? null : new RayTraceResult(hit.getHitPosition(), hitBlock, hit.getHitBlockFace());
            });
        return world;
    }

    /** 軸と平行かつ面上のrayでもゼロ除算せず、箱の進入・退出位置を求めます。 */
    private static RayTraceResult traceBox(BoundingBox box, Vector origin, Vector direction, double distance) {
        double[] minimum = {box.getMinX(), box.getMinY(), box.getMinZ()};
        double[] maximum = {box.getMaxX(), box.getMaxY(), box.getMaxZ()};
        double[] position = {origin.getX(), origin.getY(), origin.getZ()};
        double[] velocity = {direction.getX(), direction.getY(), direction.getZ()};
        BlockFace[] negative = {BlockFace.WEST, BlockFace.DOWN, BlockFace.NORTH};
        BlockFace[] positive = {BlockFace.EAST, BlockFace.UP, BlockFace.SOUTH};
        double near = Double.NEGATIVE_INFINITY;
        double far = Double.POSITIVE_INFINITY;
        BlockFace entryFace = null;
        BlockFace exitFace = null;
        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(velocity[axis]) <= 1.0E-12D) {
                if (position[axis] < minimum[axis] || position[axis] > maximum[axis]) {
                    return null;
                }
                continue;
            }
            double first = (minimum[axis] - position[axis]) / velocity[axis];
            double second = (maximum[axis] - position[axis]) / velocity[axis];
            if (Math.min(first, second) > near) {
                near = Math.min(first, second);
                entryFace = first < second ? negative[axis] : positive[axis];
            }
            if (Math.max(first, second) < far) {
                far = Math.max(first, second);
                exitFace = first < second ? positive[axis] : negative[axis];
            }
            if (near > far) {
                return null;
            }
        }
        double hit = near >= 0 ? near : far;
        return hit < 0 || hit > distance || !Double.isFinite(hit) ? null
            : new RayTraceResult(origin.clone().add(direction.clone().multiply(hit)), near >= 0 ? entryFace : exitFace);
    }

    private static DebugFishingRodUseService.ActiveCast activeCast(Location start, Location target) {
        BlockDisplay hookDisplay = mock(BlockDisplay.class);
        return new DebugFishingRodUseService.ActiveCast(
            "test-equipment-instance",
            start.clone(),
            start.clone(),
            target.clone(),
            false,
            hookDisplay
        );
    }

    private static FishingFixture fishingFixture() {
        AstralRecord plugin = mock(AstralRecord.class);
        InventoryService inventoryService = mock(InventoryService.class);
        ItemService itemService = mock(ItemService.class);
        StatusService statusService = mock(StatusService.class);
        ParticleDisplayService particleDisplayService = mock(ParticleDisplayService.class);
        Player bukkitPlayer = mock(Player.class);
        AstPlayer astPlayer = mock(AstPlayer.class);
        AccountModel account = mock(AccountModel.class);
        World world = scene(null, false);
        Location eyeLocation = new Location(world, 0.0D, 64.0D, 0.0D, 0.0F, 0.0F);
        Location playerLocation = new Location(world, 0.0D, 64.0D, 0.0D);
        UUID playerId = UUID.randomUUID();

        when(astPlayer.getBukkit()).thenReturn(bukkitPlayer);
        when(astPlayer.getAccount()).thenReturn(account);
        when(account.getMode()).thenReturn(AccountMode.PLAYER);
        when(bukkitPlayer.getUniqueId()).thenReturn(playerId);
        when(bukkitPlayer.isOnline()).thenReturn(true);
        when(bukkitPlayer.isDead()).thenReturn(false);
        when(bukkitPlayer.getEyeLocation()).thenReturn(eyeLocation);
        when(bukkitPlayer.getLocation()).thenReturn(playerLocation);
        when(bukkitPlayer.getWorld()).thenReturn(world);

        InventoryEntryModel entry = mock(InventoryEntryModel.class);
        ItemReference reference = new ItemReference("debug-fishing-rod", "EQUIPMENT", "rod-instance");
        ItemModel model = mock(ItemModel.class);
        ItemEquipment equipment = mock(ItemEquipment.class);
        EquipmentInstance instance = mock(EquipmentInstance.class);
        when(inventoryService.getHotbarEntryInHand(astPlayer, org.bukkit.inventory.EquipmentSlot.HAND))
            .thenReturn(entry);
        when(inventoryService.getItemReferenceInHand(astPlayer, org.bukkit.inventory.EquipmentSlot.HAND))
            .thenReturn(reference);
        when(itemService.findLoadedById(reference.itemId())).thenReturn(model);
        when(itemService.findEquipmentInstanceById(reference.equipmentInstanceId())).thenReturn(instance);
        when(model.getEquipment()).thenReturn(equipment);
        when(equipment.getSlot()).thenReturn(ItemEquipmentSlot.TOOL);
        when(equipment.getTag()).thenReturn(MasterTagIds.Equipment.FISHING_ROD);
        when(equipment.getRequiredLevel()).thenReturn(0);
        when(equipment.getRequiredClasses()).thenReturn(List.of());
        when(instance.getEquipmentInstanceId()).thenReturn(reference.equipmentInstanceId());

        StatusSnapshot status = mock(StatusSnapshot.class);
        when(statusService.getStatus(astPlayer)).thenReturn(status);
        when(status.rollValue(StatusType.CAST_DISTANCE)).thenReturn(4.0D);

        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(server.getPlayer(playerId)).thenReturn(bukkitPlayer);
        BukkitTask task = mock(BukkitTask.class);
        when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), eq(1L), eq(1L)))
            .thenReturn(task);

        List<BlockDisplay> displays = new ArrayList<>();
        when(world.spawn(
            any(Location.class),
            eq(BlockDisplay.class),
            ArgumentMatchers.<Consumer<BlockDisplay>>any()
        )).thenAnswer(invocation -> {
            BlockDisplay display = mock(BlockDisplay.class);
            when(display.isValid()).thenReturn(true);
            Consumer<BlockDisplay> initializer = invocation.getArgument(2);
            initializer.accept(display);
            displays.add(display);
            return display;
        });

        DebugFishingRodUseService service = new DebugFishingRodUseService(
            plugin,
            inventoryService,
            itemService,
            statusService,
            particleDisplayService
        );
        return new FishingFixture(service, astPlayer, bukkitPlayer, playerLocation, particleDisplayService,
            scheduler, displays, task, status);
    }

    private record FishingFixture(
        DebugFishingRodUseService service,
        AstPlayer astPlayer,
        Player bukkitPlayer,
        Location playerLocation,
        ParticleDisplayService particles,
        BukkitScheduler scheduler,
        List<BlockDisplay> displays,
        BukkitTask task,
        StatusSnapshot status
    ) {
    }
}
