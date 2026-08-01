package io.github.maaasu.astralRecord.feature.spawner.event;

import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.spawner.service.MobSpawnerService;
import io.github.maaasu.astralRecord.shared.interaction.InputClaimPolicy;
import io.github.maaasu.astralRecord.shared.interaction.InputFamily;
import io.github.maaasu.astralRecord.shared.interaction.InputSource;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInputContext;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInteractionSnapshot;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInteractionRayTrace;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MobSpawnerBlockEventHandlerTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/12_1-モデル定義.md
     * 章・見出し: # 12_1-モデル定義 > ## 22. Mob スポナー座標 > ### Mob spawner 削除認可
     * 検証契約: user.permission=99でもaccount modeがPLAYERなら左クリック候補をclaim/cancelしない。
     */
    @Test
    void leavesLeftClickUnclaimedWhenAdminPermissionUsesPlayerAccountMode() {
        MobSpawnerService spawnerService = mock(MobSpawnerService.class);
        Player player = mock(Player.class);
        AstPlayer astPlayer = mock(AstPlayer.class);
        PlayerInteractionSnapshot snapshot = new PlayerInteractionSnapshot(
            player,
            mock(org.bukkit.event.Event.class),
            EquipmentSlot.HAND,
            null,
            null,
            null,
            null,
            false,
            Objects.requireNonNull(PlayerInteractionRayTrace.create(new Vector(), new Vector(1, 0, 0), 8.0D)),
            8.0D
        );
        when(spawnerService.canRemoveSpawner(astPlayer)).thenReturn(false);

        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class)) {
            cache.when(() -> AstPlayerCache.get(player)).thenReturn(astPlayer);

            var candidates = new MobSpawnerBlockEventHandler(spawnerService).resolve(new PlayerInputContext<>(
                UUID.randomUUID(),
                0L,
                InputFamily.LEFT_CLICK,
                InputSource.PLAYER_INTERACT,
                snapshot
            ));

            assertTrue(candidates.isEmpty());
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/12-mob/12_0-概要.md
     * 章・見出し: # 12_0-概要 > ## 3. 構成要素（実装単位）
     * 検証契約: 専用Mob spawner item配置をcancelし実blockを置かずitemも消費せず座標だけ登録する。
     */
    @Test
    void cancelsVanillaPlacementWithoutConsumingMobSpawnerItem() {
        MobSpawnerService spawnerService = mock(MobSpawnerService.class);
        BlockPlaceEvent event = mock(BlockPlaceEvent.class);
        Block placedBlock = mock(Block.class);
        Location location = mock(Location.class);
        World world = mock(World.class);
        Player player = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        AstPlayer astPlayer = mock(AstPlayer.class);
        PlayerMessageService messageService = mock(PlayerMessageService.class);
        when(event.getBlockPlaced()).thenReturn(placedBlock);
        when(event.getPlayer()).thenReturn(player);
        when(placedBlock.getLocation()).thenReturn(location);
        when(location.getWorld()).thenReturn(world);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(player.getInventory()).thenReturn(inventory);
        when(spawnerService.readSpawnerId(any())).thenReturn("test_spawner");
        when(spawnerService.isAdminMode(astPlayer)).thenReturn(true);
        when(spawnerService.registerLocation("test_spawner", location)).thenReturn(true);
        PlayerInteractionSnapshot snapshot = new PlayerInteractionSnapshot(
            player,
            event,
            EquipmentSlot.HAND,
            null,
            null,
            placedBlock,
            null,
            false,
            Objects.requireNonNull(PlayerInteractionRayTrace.create(new Vector(), new Vector(1, 0, 0), 8.0D)),
            8.0D
        );

        var candidates = new MobSpawnerBlockEventHandler(spawnerService).resolve(new PlayerInputContext<>(
            UUID.randomUUID(),
            0L,
            InputFamily.BLOCK_MUTATION,
            InputSource.BLOCK_PLACE,
            snapshot
        ));

        assertEquals(1, candidates.size());
        var candidate = candidates.iterator().next();
        assertEquals(InputClaimPolicy.CLAIM_AND_CANCEL, candidate.claimPolicy());
        assertTrue(candidate.claimPolicy().isCancelRequested());
        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class);
             MockedStatic<PlayerMessageService> messages = mockStatic(PlayerMessageService.class)) {
            cache.when(() -> AstPlayerCache.get(player)).thenReturn(astPlayer);
            messages.when(PlayerMessageService::getInstance).thenReturn(messageService);

            assertTrue(candidate.executeIfValid());
        }
        verify(spawnerService).registerLocation("test_spawner", location);
        verify(inventory, never()).getItem(any(EquipmentSlot.class));
    }
}
