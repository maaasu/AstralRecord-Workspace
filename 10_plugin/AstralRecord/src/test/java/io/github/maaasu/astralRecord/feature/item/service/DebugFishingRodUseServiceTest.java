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
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
     * 検証契約: 発射中の糸の中点は始点と終点を結ぶ直線上ではなく、下方へしなる二次ベジェ曲線上に配置される。
     */
    @Test
    void ropePointHasSlackBelowTheStraightMidpoint() {
        Location point = DebugFishingRodUseService.ropePoint(
            new Location(null, 0.0D, 0.0D, 0.0D),
            new Location(null, 5.0D, -2.0D, 0.0D),
            new Location(null, 10.0D, 0.0D, 0.0D),
            0.5D
        );

        assertEquals(5.0D, point.getX(), 0.0001D);
        assertEquals(-1.0D, point.getY(), 0.0001D);
        assertEquals(0.0D, point.getZ(), 0.0001D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### デバッグ釣り竿仮想キャスト
     * 検証契約: キャスト距離を100000 block相当へ設定しても糸表示のサイズは固定の小さい値を保持し、区間長によって引き延ばされない。
     */
    @Test
    void lineTransformKeepsFixedSmallDisplaySizeForLongSegment() {
        Transformation transformation = DebugFishingRodUseService.lineTransformation(
            new Location(null, 0.0D, 0.0D, 0.0D),
            new Location(null, 100_000.0D, 0.0D, 0.0D)
        );

        assertNotNull(transformation);
        assertEquals(DebugFishingRodUseService.LINE_DISPLAY_LENGTH, transformation.getScale().x, 0.0001F);
        assertEquals(DebugFishingRodUseService.LINE_THICKNESS, transformation.getScale().y, 0.0001F);
        assertEquals(DebugFishingRodUseService.LINE_THICKNESS, transformation.getScale().z, 0.0001F);
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
     * 検証契約: 回収開始時は回収音を1回だけ再生し、回収中の再クリックでは重複再生しない。
     */
    @Test
    void retractPlaysRetrieveSoundOnlyWhenRetractionStarts() {
        FishingFixture fixture = fishingFixture();
        fixture.service().cast(fixture.astPlayer());

        verify(fixture.bukkitPlayer(), times(0)).playSound(
            eq(fixture.playerLocation()),
            eq(DebugFishingRodUseService.RETRACT_SOUND),
            eq(SoundCategory.PLAYERS),
            anyFloat(),
            anyFloat()
        );

        fixture.service().retract(fixture.astPlayer());

        verify(fixture.bukkitPlayer(), times(1)).playSound(
            eq(fixture.playerLocation()),
            eq(DebugFishingRodUseService.RETRACT_SOUND),
            eq(SoundCategory.PLAYERS),
            anyFloat(),
            anyFloat()
        );

        fixture.service().retract(fixture.astPlayer());

        verify(fixture.bukkitPlayer(), times(1)).playSound(
            eq(fixture.playerLocation()),
            eq(DebugFishingRodUseService.RETRACT_SOUND),
            eq(SoundCategory.PLAYERS),
            anyFloat(),
            anyFloat()
        );
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### デバッグ釣り竿仮想キャスト
     * 検証契約: 発射中の針が固体blockへ到達した場合、そのblockの手前で停止し、保持状態へ遷移する。
     */
    @Test
    void outboundHookStopsAtSolidBlock() {
        World world = mock(World.class);
        Block solidBlock = mock(Block.class);
        when(solidBlock.getType()).thenReturn(Material.STONE);
        when(solidBlock.isPassable()).thenReturn(false);
        stubRayTrace(world, solidBlock, new Vector(1.0D, 64.5D, 0.5D));
        Location start = new Location(world, 0.5D, 64.5D, 0.5D);
        DebugFishingRodUseService.ActiveCast active = activeCast(start, new Location(world, 4.5D, 64.5D, 0.5D));

        assertFalse(DebugFishingRodUseService.advanceHook(active));
        assertEquals(DebugFishingRodUseService.CastPhase.HOLDING, active.phase);
        assertEquals(1.0D, active.currentHook.getX(), 0.0001D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### デバッグ釣り竿仮想キャスト
     * 検証契約: 水blockへの着水後は沈下状態へ遷移し、次のtickから水中方向へ少しずつ移動する。
     */
    @Test
    void waterImpactStartsGradualSinking() {
        World world = mock(World.class);
        Block waterBlock = mock(Block.class);
        when(waterBlock.getType()).thenReturn(Material.WATER);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(waterBlock);
        stubRayTrace(world, waterBlock, new Vector(1.0D, 64.5D, 0.5D));
        Location start = new Location(world, 0.5D, 64.5D, 0.5D);
        DebugFishingRodUseService.ActiveCast active = activeCast(start, new Location(world, 4.5D, 64.5D, 0.5D));

        assertFalse(DebugFishingRodUseService.advanceHook(active));
        assertEquals(DebugFishingRodUseService.CastPhase.SINKING, active.phase);
        double impactY = active.currentHook.getY();

        assertFalse(DebugFishingRodUseService.advanceHook(active));
        assertEquals(impactY - DebugFishingRodUseService.WATER_SINK_SPEED_PER_TICK, active.currentHook.getY(), 0.0001D);
        assertEquals(1, active.sinkTicks);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### デバッグ釣り竿仮想キャスト
     * 検証契約: キャスト距離は糸の最大長であり、最大長へ到達しても空中で保持状態へ遷移せず、重力で落下する。
     */
    @Test
    void outboundHookContinuesFallingAfterReachingMaxLineLength() {
        World world = mock(World.class);
        when(world.rayTraceBlocks(
            any(Location.class),
            any(Vector.class),
            anyDouble(),
            eq(FluidCollisionMode.ALWAYS),
            eq(false)
        )).thenReturn(null);
        Location rodTip = new Location(world, 0.5D, 64.5D, 0.5D);
        DebugFishingRodUseService.ActiveCast active = activeCast(
            rodTip,
            new Location(world, 4.5D, 64.5D, 0.5D)
        );

        for (int tick = 0; tick < 4; tick++) {
            assertFalse(DebugFishingRodUseService.advanceHook(active));
        }

        assertEquals(DebugFishingRodUseService.CastPhase.OUTBOUND, active.phase);
        assertTrue(active.currentHook.getY() < rodTip.getY());
        assertTrue(active.currentHook.distance(rodTip) <= 4.0001D);
        double yAfterFourTicks = active.currentHook.getY();

        assertFalse(DebugFishingRodUseService.advanceHook(active));
        assertTrue(active.currentHook.getY() < yAfterFourTicks);
        assertTrue(active.currentHook.distance(rodTip) <= 4.0001D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### デバッグ釣り竿仮想キャスト
     * 検証契約: 最大長まで伸びた糸が水中へ沈むとき、糸長を超えず、針は下方かつ竿先側へ移動する。
     */
    @Test
    void sinkingHookMovesTowardRodTipWhenLineIsFullyExtended() {
        World world = mock(World.class);
        Block waterBlock = mock(Block.class);
        when(waterBlock.getType()).thenReturn(Material.WATER);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(waterBlock);
        Location rodTip = new Location(world, 0.5D, 64.5D, 0.5D);
        Location maxLengthPoint = new Location(world, 4.5D, 64.5D, 0.5D);
        DebugFishingRodUseService.ActiveCast active = activeCast(rodTip, maxLengthPoint);
        active.currentHook = maxLengthPoint.clone();
        active.phase = DebugFishingRodUseService.CastPhase.SINKING;

        assertFalse(DebugFishingRodUseService.advanceHook(active));

        assertEquals(DebugFishingRodUseService.CastPhase.SINKING, active.phase);
        assertTrue(active.currentHook.getX() < maxLengthPoint.getX());
        assertTrue(active.currentHook.getY() < maxLengthPoint.getY());
        assertTrue(active.currentHook.distance(rodTip) <= 4.0001D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### デバッグ釣り竿仮想キャスト
     * 検証契約: 再右クリックで回収状態へ切り替わった針は、竿先へ向かって移動し、到達tickで回収条件を満たす。
     */
    @Test
    void retractingHookArrivesAtRodTip() {
        World world = mock(World.class);
        Location rodTip = new Location(world, 0.5D, 64.5D, 0.5D);
        DebugFishingRodUseService.ActiveCast active = activeCast(
            new Location(world, 4.5D, 64.5D, 0.5D),
            rodTip
        );
        active.phase = DebugFishingRodUseService.CastPhase.RETRACTING;

        assertFalse(DebugFishingRodUseService.advanceHook(active));
        assertEquals(2.5D, active.currentHook.getX(), 0.0001D);
        assertTrue(DebugFishingRodUseService.advanceHook(active));
        assertEquals(rodTip, active.currentHook);
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

    private static void stubRayTrace(World world, Block block, Vector hitPosition) {
        RayTraceResult hit = mock(RayTraceResult.class);
        when(hit.getHitBlock()).thenReturn(block);
        when(hit.getHitPosition()).thenReturn(hitPosition);
        when(world.rayTraceBlocks(
            any(Location.class),
            any(Vector.class),
            eq(1.5D),
            eq(FluidCollisionMode.ALWAYS),
            eq(false)
        )).thenReturn(hit);
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
        return new FishingFixture(service, astPlayer, bukkitPlayer, playerLocation);
    }

    private record FishingFixture(
        DebugFishingRodUseService service,
        AstPlayer astPlayer,
        Player bukkitPlayer,
        Location playerLocation
    ) {
    }
}
