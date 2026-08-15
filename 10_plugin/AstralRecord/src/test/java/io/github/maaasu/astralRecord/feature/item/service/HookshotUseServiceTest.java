package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryEntryModel;
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
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
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
     * 検証契約: 牽引は距離に応じて強くアンカー方向へ加速し、横方向の慣性を自然に減衰する。
     */
    @Test
    void bendsVelocityTowardAnchorWithDistanceScaledAcceleration() {
        Vector velocity = HookshotUseService.calculatePullVelocity(
            new Vector(0.60D, 0.0D, 0.0D),
            new Vector(0.0D, 0.0D, HookshotUseService.MAX_RANGE)
        );

        assertTrue(velocity.getZ() >= HookshotUseService.MIN_PULL_ACCELERATION);
        assertTrue(velocity.getX() > 0.0D && velocity.getX() < 0.60D);
        assertTrue(velocity.length() <= HookshotUseService.MAX_PULL_SPEED);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### フックショット
     * 検証契約: 合成velocityは設計上限2.05を超えない。
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
     * 検証契約: 右クリック装填は30 tick完了時だけhook1個とloaded metadataを同時確定し、耐久を消費しない。
     */
    @Test
    void completesLoadingWithOneHookAndNoDurabilityCost() {
        Fixture fixture = createFixture(null);
        when(fixture.inventoryService().consumeNormalItemAndUpdateHotbarEquipmentMetadata(
            eq(fixture.astPlayer()),
            eq(EquipmentSlot.HAND),
            eq(fixture.instanceId()),
            eq(null),
            anyString(),
            eq(HookshotCostService.HOOK_ITEM_ID),
            eq(HookshotCostService.HOOK_AMOUNT_PER_LOAD)
        )).thenReturn(true);

        fixture.service().startLoading(fixture.astPlayer());
        Runnable tick = captureTick(fixture);
        for (int index = 0; index < HookshotUseService.LOAD_DURATION_TICKS; index++) {
            tick.run();
        }

        verify(fixture.inventoryService()).consumeNormalItemAndUpdateHotbarEquipmentMetadata(
            eq(fixture.astPlayer()),
            eq(EquipmentSlot.HAND),
            eq(fixture.instanceId()),
            eq(null),
            anyString(),
            eq(HookshotCostService.HOOK_ITEM_ID),
            eq(HookshotCostService.HOOK_AMOUNT_PER_LOAD)
        );
        verify(fixture.itemService(), never()).updateEquipmentDurability(anyString(), anyInt(), anyString());
        verify(fixture.inventoryService()).refreshManagedInventoryUi(fixture.astPlayer());
        verify(fixture.movementSpeed()).addTransientModifier(any());
        verify(fixture.task()).cancel();
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### フックショット
     * 検証契約: 装填済みフックショットの有効な発射だけが耐久とloaded状態を消費し、velocityで牽引した後に表示とtaskを回収する。
     */
    @Test
    void pullsLoadedHookshotTowardSolidAnchorAndReclaimsDisplayAndTask() {
        String loadedMetadata = "{\"hookshot\":{\"loaded\":true}}";
        Fixture fixture = createFixture(solidBlockHit(), loadedMetadata);
        EquipmentInstance reduced = fixture.equipmentInstance(199);
        when(fixture.itemService().updateEquipmentDurability(
            fixture.instanceId(),
            199,
            fixture.accountId().toString()
        )).thenReturn(reduced);
        when(fixture.inventoryService().updateHotbarEquipmentMetadata(
            fixture.astPlayer(),
            EquipmentSlot.HAND,
            fixture.instanceId(),
            loadedMetadata,
            null
        )).thenReturn(true);

        fixture.service().fire(fixture.astPlayer());
        Runnable tick = captureTick(fixture);
        tick.run();

        ArgumentCaptor<Vector> velocity = ArgumentCaptor.forClass(Vector.class);
        verify(fixture.player()).setVelocity(velocity.capture());
        assertTrue(velocity.getValue().getZ() > 0.0D);
        assertTrue(velocity.getValue().length() <= HookshotUseService.MAX_PULL_SPEED);
        verify(fixture.player(), never()).teleport(any(Location.class));
        verify(fixture.inventoryService()).updateHotbarEquipmentMetadata(
            fixture.astPlayer(),
            EquipmentSlot.HAND,
            fixture.instanceId(),
            loadedMetadata,
            null
        );
        verify(fixture.inventoryService(), never()).consumeNormalItem(any(), anyString(), anyLong());

        when(fixture.player().getLocation()).thenReturn(new Location(fixture.world(), 0.0D, 64.0D, 8.0D));
        tick.run();

        verify(fixture.task()).cancel();
        verify(fixture.anchorDisplay()).remove();
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### フックショット
     * 検証契約: 装填済みでも無効照準では耐久・loaded状態・表示・taskを変更しない。
     */
    @Test
    void preservesLoadedStateWhenAimHitsNoSolidBlock() {
        Fixture fixture = createFixture(null, "{\"hookshot\":{\"loaded\":true}}");

        fixture.service().fire(fixture.astPlayer());

        verify(fixture.itemService(), never()).updateEquipmentDurability(anyString(), anyInt(), anyString());
        verify(fixture.inventoryService(), never()).updateHotbarEquipmentMetadata(
            any(), any(), anyString(), any(), any()
        );
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
        return createFixture(hit, null);
    }

    private Fixture createFixture(RayTraceResult hit, String metadataJson) {
        UUID playerId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID instanceUuid = UUID.randomUUID();
        String instanceId = instanceUuid.toString();
        AstralRecord plugin = mock(AstralRecord.class);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        BukkitTask task = mock(BukkitTask.class);
        World world = mock(World.class);
        Player player = mock(Player.class);
        BlockDisplay anchorDisplay = mock(BlockDisplay.class);
        AttributeInstance movementSpeed = mock(AttributeInstance.class);
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
        InventoryEntryModel entry = inventoryEntry(instanceUuid, metadataJson, accountId);

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
        when(player.getAttribute(Attribute.MOVEMENT_SPEED)).thenReturn(movementSpeed);
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
        when(inventoryService.getHotbarEntryInHand(astPlayer, EquipmentSlot.HAND)).thenReturn(entry);
        when(inventoryService.getItemReferenceInHand(astPlayer, EquipmentSlot.HAND)).thenReturn(reference);
        when(itemService.findLoadedById("hookshot")).thenReturn(model);
        when(itemService.findEquipmentInstanceById(instanceId)).thenReturn(current);
        AstPlayerCache.put(astPlayer);

        return new Fixture(
            plugin,
            scheduler,
            task,
            world,
            player,
            anchorDisplay,
            movementSpeed,
            inventoryService,
            itemService,
            astPlayer,
            accountId,
            instanceId,
            new HookshotUseService(plugin, inventoryService, itemService, particleDisplayService)
        );
    }

    private InventoryEntryModel inventoryEntry(UUID instanceId, String metadataJson, UUID accountId) {
        LocalDateTime now = LocalDateTime.of(2026, 8, 15, 0, 0);
        return new InventoryEntryModel(
            UUID.randomUUID(),
            UUID.randomUUID(),
            1,
            "EQUIPMENT",
            null,
            "EQUIPMENT",
            instanceId,
            1L,
            metadataJson,
            now,
            now,
            accountId,
            accountId,
            false
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
        AttributeInstance movementSpeed,
        InventoryService inventoryService,
        ItemService itemService,
        AstPlayer astPlayer,
        UUID accountId,
        String instanceId,
        HookshotUseService service
    ) {
        private EquipmentInstance equipmentInstance(int durabilityValue) {
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
    }
}
