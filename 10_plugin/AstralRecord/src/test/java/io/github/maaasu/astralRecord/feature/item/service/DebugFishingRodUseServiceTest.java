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
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.BoundingBox;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
     * 検証契約: 縦・横・斜めと長距離の糸表示は、変換後の両端の中心が物理ノードの両端へ一致し、太さを保持する。
     */
    @Test
    void lineTransformConnectsBothSegmentEndpointsInEveryDirection() {
        Location start = new Location(null, 3.0D, 8.0D, -2.0D);
        for (Vector delta : List.of(new Vector(4, 0, 0), new Vector(-4, 0, 0),
            new Vector(0, 4, 0), new Vector(0, -4, 0), new Vector(0, 0, 4),
            new Vector(2, -3, -5), new Vector(100_000, 0, 0))) {
            Transformation transformation = DebugFishingRodUseService.lineTransformation(
                start, start.clone().add(delta)
            );
            assertNotNull(transformation);
            Matrix4f matrix = new Matrix4f().translate(transformation.getTranslation())
                .rotate(transformation.getLeftRotation()).scale(transformation.getScale())
                .rotate(transformation.getRightRotation());
            Vector3f first = matrix.transformPosition(new Vector3f(0, 0.5F, 0.5F));
            Vector3f last = matrix.transformPosition(new Vector3f(1, 0.5F, 0.5F));
            assertEquals(0, first.length(), 0.0001F);
            assertEquals(delta.getX(), last.x, 0.001D);
            assertEquals(delta.getY(), last.y, 0.001D);
            assertEquals(delta.getZ(), last.z, 0.001D);
            assertEquals(delta.length(), transformation.getScale().x, 0.001D);
            assertEquals(DebugFishingRodUseService.LINE_THICKNESS, transformation.getScale().y, 0.0001F);
            assertEquals(DebugFishingRodUseService.LINE_THICKNESS, transformation.getScale().z, 0.0001F);
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### デバッグ釣り竿仮想キャスト
     * 検証契約: 糸表示はベジェ曲線を再計算せず、物理ノードの位置を毎tickの描画位置へ使用する。
     */
    @Test
    void renderRopeUsesPhysicalNodePositions() {
        FishingFixture fixture = fishingFixture();
        World world = mock(World.class);
        Location rodTip = new Location(world, 0.5D, 64.5D, 0.5D);
        Location hook = new Location(world, 4.5D, 64.5D, 0.5D);
        List<BlockDisplay> ropeDisplays = new ArrayList<>(DebugFishingRodUseService.ROPE_SEGMENT_COUNT);
        for (int index = 0; index < DebugFishingRodUseService.ROPE_SEGMENT_COUNT; index++) {
            ropeDisplays.add(mock(BlockDisplay.class));
        }
        DebugFishingRodUseService.ActiveCast active = new DebugFishingRodUseService.ActiveCast(
            "test-equipment-instance",
            rodTip,
            rodTip,
            hook,
            4.0D,
            false,
            mock(BlockDisplay.class),
            ropeDisplays
        );
        Location expectedPosition = new Location(world, 1.25D, 63.75D, 0.5D);
        active.ropeNodes.set(1, expectedPosition);

        fixture.service().renderRope(active);

        verify(ropeDisplays.get(1)).teleport(eq(expectedPosition));
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
     * 検証契約: 空中の針は最大糸長でも重力を受け、竿先の移動後も針と糸の総長がキャスト距離を超えない。
     */
    @Test
    void movingRodPullsHookWithoutExtendingLine() {
        World world = scene(null, false);
        Location start = new Location(world, 0, 12, 0);
        DebugFishingRodUseService.ActiveCast active = activeCast(start, start.clone().add(4, 0, 0));
        double previousDeployed = 0;
        for (int tick = 0; tick < 100; tick++) {
            if (tick >= 30) {
                active.rodTip.add(-0.12D, 0, 0.04D);
            }
            DebugFishingRodUseService.advanceHook(active);
            DebugFishingRodUseService.advanceRope(active);
            assertTrue(active.deployedLineLength + 1.0E-8 >= previousDeployed);
            assertTrue(active.deployedLineLength <= 4.0001D);
            assertTrue(active.currentHook.distance(active.rodTip) <= 4.001D);
            assertTrue(DebugFishingRodUseService.polylineLength(active.ropeNodes) <= 4.001D);
            assertEquals(active.currentHook, active.ropeNodes.get(active.ropeNodes.size() - 1));
            previousDeployed = active.deployedLineLength;
        }
        assertTrue(active.currentHook.getY() < start.getY());
        assertTrue(active.currentHook.getX() < 0, "移動した竿先へ針が引かれる");
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### デバッグ釣り竿仮想キャスト
     * 検証契約: 竿先が針に近づいても繰り出した糸長は減らず、余った糸がたるむ。
     */
    @Test
    void approachingRodPreservesDeployedLengthAndCreatesSlack() {
        World world = scene(new BoundingBox(-100, -10, -100, 100, 0, 100), false);
        Location start = new Location(world, 0, 2, 0);
        DebugFishingRodUseService.ActiveCast active = activeCast(start, start.clone().add(4, 0, 0));
        for (int tick = 0; tick < 80; tick++) {
            DebugFishingRodUseService.advanceHook(active);
            DebugFishingRodUseService.advanceRope(active);
        }
        double deployed = active.deployedLineLength;
        Vector towardHook = active.currentHook.toVector().subtract(active.rodTip.toVector()).multiply(0.6);
        for (int tick = 0; tick < 20; tick++) {
            active.rodTip.add(towardHook.clone().multiply(0.05));
            DebugFishingRodUseService.advanceHook(active);
            DebugFishingRodUseService.advanceRope(active);
        }
        assertTrue(active.deployedLineLength >= deployed - 1.0E-8);
        assertTrue(active.rodTip.distance(active.currentHook) < active.deployedLineLength - 0.1D);
        double path = DebugFishingRodUseService.polylineLength(active.ropeNodes);
        assertTrue(path > active.rodTip.distance(active.currentHook) + 0.01D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### デバッグ釣り竿仮想キャスト
     * 検証契約: 固体面へ衝突した針と糸は貫通せず、竿先が離れると接触位置に永久固定されず引かれる。
     */
    @Test
    void solidCollisionDoesNotPermanentlyPinHookOrRope() {
        World world = scene(new BoundingBox(2, -100, -100, 3, 100, 100), false);
        Location start = new Location(world, 0, 10, 0);
        DebugFishingRodUseService.ActiveCast active = activeCast(start, start.clone().add(4, 0, 0));
        for (int tick = 0; tick < 12; tick++) {
            DebugFishingRodUseService.advanceHook(active);
            DebugFishingRodUseService.advanceRope(active);
            assertTrue(active.currentHook.getX() <= 2.00001D);
            for (Location node : active.ropeNodes) {
                assertTrue(node.getX() <= 2.00001D);
            }
        }
        for (int tick = 0; tick < 40; tick++) {
            active.rodTip.add(-0.2D, 0, 0);
            DebugFishingRodUseService.advanceHook(active);
            DebugFishingRodUseService.advanceRope(active);
        }
        assertTrue(active.currentHook.getX() < 0);
        assertTrue(DebugFishingRodUseService.polylineLength(active.ropeNodes) <= 4.001D);
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
     * 検証契約: 障害物へたるんだ糸はノードだけでなく隣接ノード間の描画区間も固体内部を通らない。
     */
    @Test
    void saggingSegmentsDoNotCutThroughObstacle() {
        BoundingBox obstacle = new BoundingBox(-0.5, -10, -0.5, 0.5, 0, 0.5);
        World world = scene(obstacle, false);
        Location start = new Location(world, -2, 1, 0);
        DebugFishingRodUseService.ActiveCast active = activeCast(start, start.clone().add(6, 0, 0));
        for (int tick = 0; tick < 100; tick++) {
            DebugFishingRodUseService.advanceHook(active);
            DebugFishingRodUseService.advanceRope(active);
            assertTrue(DebugFishingRodUseService.polylineLength(active.ropeNodes) <= 6.001D);
            for (int index = 0; index < active.ropeNodes.size() - 1; index++) {
                Vector from = active.ropeNodes.get(index).toVector();
                Vector delta = active.ropeNodes.get(index + 1).toVector().subtract(from);
                double length = delta.length();
                if (length < 0.0001D) {
                    continue;
                }
                // 表面との接触を除き、線分内部に固体への入口がないことを確認する。
                BoundingBox interior = obstacle.clone().expand(-0.0001D);
                assertNull(interior.rayTrace(from, delta.multiply(1.0D / length), length),
                    "tick=" + tick + ", segment=" + index);
            }
        }
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
     * 検証契約: 糸の張力による針の水面通過も沈下状態と一度限りの着水演出へ接続する。
     */
    @Test
    void ropeConstraintWaterEntryUpdatesHookState() {
        World world = scene(null, true);
        DebugFishingRodUseService.ActiveCast active = new DebugFishingRodUseService.ActiveCast(
            "rod", new Location(world, 0, 0, 0), new Location(world, 0, 1, 0),
            new Location(world, 0, 2, 0), 2, false, mock(BlockDisplay.class), List.of()
        );
        active.deployedLineLength = 2;
        active.rodTip = new Location(world, 0, -3, 0);
        DebugFishingRodUseService.advanceRope(active);
        assertTrue(active.inWater, "hook=" + active.currentHook + ", impact=" + active.pendingWaterImpact);
        assertEquals(DebugFishingRodUseService.CastPhase.SINKING, active.phase);
        assertNotNull(active.pendingWaterImpact);
        assertEquals(0, active.pendingWaterImpact.getY(), 0.001D);
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

    /** 幾何学的な固体boxと任意の水面を持つworldを、移動区間に応じて応答させます。 */
    private static World scene(BoundingBox solid, boolean water) {
        World world = mock(World.class);
        Block air = mock(Block.class);
        when(air.getType()).thenReturn(Material.AIR);
        when(air.isPassable()).thenReturn(true);
        Block stone = mock(Block.class);
        when(stone.getType()).thenReturn(Material.STONE);
        when(stone.isPassable()).thenReturn(false);
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
                RayTraceResult hit = solid == null ? null : solid.rayTrace(origin, direction, distance);
                Block hitBlock = stone;
                if (water && call.getArgument(3) != FluidCollisionMode.NEVER && !waterBox.contains(origin)) {
                    RayTraceResult waterHit = waterBox.rayTrace(origin, direction, distance);
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

    private static DebugFishingRodUseService.ActiveCast activeCast(Location start, Location target) {
        BlockDisplay hookDisplay = mock(BlockDisplay.class);
        BlockDisplay ropeDisplay = mock(BlockDisplay.class);
        return new DebugFishingRodUseService.ActiveCast(
            "test-equipment-instance",
            start.clone(),
            start.clone(),
            target.clone(),
            false,
            hookDisplay,
            List.of(ropeDisplay)
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
        World world = mock(World.class);
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
        when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), eq(1L), eq(1L)))
            .thenReturn(mock(BukkitTask.class));

        BlockDisplay display = mock(BlockDisplay.class);
        when(display.isValid()).thenReturn(true);
        when(world.spawn(
            any(Location.class),
            eq(BlockDisplay.class),
            ArgumentMatchers.<Consumer<BlockDisplay>>any()
        )).thenReturn(display);

        DebugFishingRodUseService service = new DebugFishingRodUseService(
            plugin,
            inventoryService,
            itemService,
            statusService,
            particleDisplayService
        );
        return new FishingFixture(service, astPlayer, bukkitPlayer, playerLocation, particleDisplayService);
    }

    private record FishingFixture(
        DebugFishingRodUseService service,
        AstPlayer astPlayer,
        Player bukkitPlayer,
        Location playerLocation,
        ParticleDisplayService particles
    ) {
    }
}
