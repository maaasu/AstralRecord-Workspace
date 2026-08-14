package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipment;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentDurability;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentSlot;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.model.ItemReference;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.masterdata.tag.MasterTagIds;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.bukkit.util.RayTraceResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HookshotUseServiceTest {

    @AfterEach
    void clearPlayerCache() {
        AstPlayerCache.clear();
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### フックショット
     * 検証契約: 牽引はアンカー方向へ加速するvelocityを返し、プレイヤー座標を直接変更しない。
     */
    @Test
    void addsAccelerationTowardAnchor() {
        Vector velocity = HookshotUseService.calculatePullVelocity(
            new Vector(0.0D, 0.0D, 0.0D),
            new Vector(3.0D, 2.0D, 0.0D)
        );

        assertTrue(velocity.getX() > 0.0D);
        assertTrue(velocity.getY() > 0.0D);
        assertTrue(velocity.length() <= HookshotUseService.MAX_PULL_SPEED);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### フックショット
     * 検証契約: 合成velocityは設計上限1.20を超えない。
     */
    @Test
    void capsResultingVelocityAtConfiguredMaximum() {
        Vector velocity = HookshotUseService.calculatePullVelocity(
            new Vector(20.0D, 0.0D, 0.0D),
            new Vector(1.0D, 0.0D, 0.0D)
        );

        assertEquals(HookshotUseService.MAX_PULL_SPEED, velocity.length(), 1.0E-9D);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### フックショット
     * 検証契約: 固体blockに命中した有効な射出はhookと耐久を消費し、velocityで牽引した後に表示とtaskを回収する。
     */
    @Test
    void pullsTowardSolidAnchorAndReclaimsDisplayAndTaskAtEndpoint() {
        Fixture fixture = createFixture(solidBlockHit());

        fixture.service().fire(fixture.astPlayer());

        InOrder launchOrder = org.mockito.Mockito.inOrder(
            fixture.itemService(),
            fixture.inventoryService(),
            fixture.scheduler()
        );
        launchOrder.verify(fixture.itemService()).updateEquipmentDurability(
            fixture.instanceId(),
            199,
            fixture.accountId().toString()
        );
        launchOrder.verify(fixture.inventoryService()).consumeNormalItem(
            fixture.accountId(),
            HookshotCostService.HOOK_ITEM_ID,
            HookshotCostService.HOOK_AMOUNT_PER_LAUNCH
        );
        launchOrder.verify(fixture.scheduler()).runTaskTimer(
            eq(fixture.plugin()),
            any(Runnable.class),
            eq(1L),
            eq(1L)
        );
        Runnable tick = captureTick(fixture);

        tick.run();

        ArgumentCaptor<Vector> velocity = ArgumentCaptor.forClass(Vector.class);
        verify(fixture.player()).setVelocity(velocity.capture());
        assertTrue(velocity.getValue().getZ() > 0.0D);
        assertTrue(velocity.getValue().length() <= HookshotUseService.MAX_PULL_SPEED);
        verify(fixture.player(), never()).teleport(any(Location.class));

        when(fixture.player().getLocation()).thenReturn(new Location(fixture.world(), 0.0D, 64.0D, 8.0D));
        tick.run();

        verify(fixture.task()).cancel();
        verify(fixture.anchorDisplay()).remove();
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### フックショット
     * 検証契約: 固体blockへ命中しない照準は表示・taskを生成せず、hookも耐久も消費しない。
     */
    @Test
    void doesNotConsumeCostWhenAimHitsNoSolidBlock() {
        Fixture fixture = createFixture(null);

        fixture.service().fire(fixture.astPlayer());

        verify(fixture.itemService(), never()).updateEquipmentDurability(anyString(), anyInt(), anyString());
        verify(fixture.inventoryService(), never()).consumeNormalItem(any(), anyString(), anyLong());
        verify(fixture.world(), never()).spawn(
            any(Location.class),
            eq(BlockDisplay.class),
            org.mockito.ArgumentMatchers.<Consumer<? super BlockDisplay>>any()
        );
        verify(fixture.scheduler(), never()).runTaskTimer(
            eq(fixture.plugin()),
            any(Runnable.class),
            anyLong(),
            anyLong()
        );
    }

    private Runnable captureTick(Fixture fixture) {
        ArgumentCaptor<Runnable> runnable = ArgumentCaptor.forClass(Runnable.class);
        verify(fixture.scheduler()).runTaskTimer(
            eq(fixture.plugin()),
            runnable.capture(),
            eq(1L),
            eq(1L)
        );
        return runnable.getValue();
    }

    private RayTraceResult solidBlockHit() {
        Block block = mock(Block.class);
        RayTraceResult hit = mock(RayTraceResult.class);
        when(block.getType()).thenReturn(Material.STONE);
        when(hit.getHitBlock()).thenReturn(block);
        when(hit.getHitPosition()).thenReturn(new Vector(0.0D, 64.0D, 8.0D));
        return hit;
    }

    private Fixture createFixture(RayTraceResult hit) {
        UUID playerId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        String instanceId = UUID.randomUUID().toString();
        AstralRecord plugin = mock(AstralRecord.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        BukkitTask task = mock(BukkitTask.class);
        World world = mock(World.class);
        Player player = mock(Player.class);
        BlockDisplay anchorDisplay = mock(BlockDisplay.class);
        InventoryService inventoryService = mock(InventoryService.class);
        ItemService itemService = mock(ItemService.class);
        ParticleDisplayService particleDisplayService = mock(ParticleDisplayService.class);
        AstPlayer astPlayer = mock(AstPlayer.class);
        AccountModel account = mock(AccountModel.class);
        ItemModel model = mock(ItemModel.class);
        ItemEquipment equipment = mock(ItemEquipment.class);
        ItemEquipmentDurability durability = mock(ItemEquipmentDurability.class);
        ItemReference reference = new ItemReference("hookshot", "EQUIPMENT", instanceId, null);
        EquipmentInstance current = equipmentInstance(instanceId, accountId, 200);
        EquipmentInstance reduced = equipmentInstance(instanceId, accountId, 199);

        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(server.getPlayer(playerId)).thenReturn(player);
        when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), eq(1L), eq(1L))).thenReturn(task);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.isOnline()).thenReturn(true);
        when(player.isDead()).thenReturn(false);
        when(player.getWorld()).thenReturn(world);
        when(player.getEyeLocation()).thenReturn(new Location(world, 0.0D, 64.0D, 0.0D, 0.0F, 0.0F));
        when(player.getLocation()).thenReturn(new Location(world, 0.0D, 64.0D, 0.0D));
        when(player.getVelocity()).thenReturn(new Vector());
        when(world.rayTraceBlocks(
            any(Location.class),
            any(Vector.class),
            eq(HookshotUseService.MAX_RANGE),
            eq(FluidCollisionMode.NEVER),
            eq(true)
        )).thenReturn(hit);
        when(world.spawn(
            any(Location.class),
            eq(BlockDisplay.class),
            org.mockito.ArgumentMatchers.<Consumer<? super BlockDisplay>>any()
        )).thenReturn(anchorDisplay);
        when(anchorDisplay.isValid()).thenReturn(true);
        when(astPlayer.getBukkit()).thenReturn(player);
        when(astPlayer.getAccount()).thenReturn(account);
        when(account.getUuid()).thenReturn(accountId);
        when(account.getMode()).thenReturn(AccountMode.PLAYER);
        when(model.getEquipment()).thenReturn(equipment);
        when(equipment.getSlot()).thenReturn(ItemEquipmentSlot.TOOL);
        when(equipment.getTag()).thenReturn(MasterTagIds.Equipment.HOOKSHOT);
        when(equipment.getRequiredClasses()).thenReturn(List.of());
        when(equipment.getDurability()).thenReturn(durability);
        when(durability.getConsume()).thenReturn(1);
        when(inventoryService.getItemReferenceInHand(astPlayer, EquipmentSlot.HAND)).thenReturn(reference);
        when(itemService.findLoadedById("hookshot")).thenReturn(model);
        when(itemService.findEquipmentInstanceById(instanceId)).thenReturn(current);
        when(itemService.updateEquipmentDurability(instanceId, 199, accountId.toString())).thenReturn(reduced);
        when(inventoryService.consumeNormalItem(
            accountId,
            HookshotCostService.HOOK_ITEM_ID,
            HookshotCostService.HOOK_AMOUNT_PER_LAUNCH
        )).thenReturn(true);
        AstPlayerCache.put(astPlayer);

        return new Fixture(
            plugin,
            scheduler,
            task,
            world,
            player,
            anchorDisplay,
            inventoryService,
            itemService,
            astPlayer,
            accountId,
            instanceId,
            new HookshotUseService(plugin, inventoryService, itemService, particleDisplayService)
        );
    }

    private EquipmentInstance equipmentInstance(String instanceId, UUID accountId, int durabilityValue) {
        return new EquipmentInstance(
            instanceId,
            accountId.toString(),
            "hookshot",
            0,
            0,
            0,
            200,
            durabilityValue,
            "2026-08-14T00:00:00Z",
            "2026-08-14T00:00:00Z",
            List.of(),
            List.of(),
            List.of()
        );
    }

    private record Fixture(
        AstralRecord plugin,
        BukkitScheduler scheduler,
        BukkitTask task,
        World world,
        Player player,
        BlockDisplay anchorDisplay,
        InventoryService inventoryService,
        ItemService itemService,
        AstPlayer astPlayer,
        UUID accountId,
        String instanceId,
        HookshotUseService service
    ) {
    }
}
