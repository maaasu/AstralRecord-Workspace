package io.github.maaasu.astralRecord.feature.world.event;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.world.gui.AdminWorldTeleportGui;
import io.github.maaasu.astralRecord.feature.world.model.WorldMasterData;
import io.github.maaasu.astralRecord.feature.world.model.WorldSpawnLocation;
import io.github.maaasu.astralRecord.feature.world.model.WorldType;
import io.github.maaasu.astralRecord.feature.world.service.AdminWorldTeleportItemService;
import io.github.maaasu.astralRecord.feature.world.service.WorldService;
import io.github.maaasu.astralRecord.shared.interaction.InputFamily;
import io.github.maaasu.astralRecord.shared.interaction.InputSource;
import io.github.maaasu.astralRecord.shared.interaction.InputClaimPolicy;
import io.github.maaasu.astralRecord.shared.interaction.InteractionTier;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInputCandidate;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInputContext;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInteractionRayTrace;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInteractionSnapshot;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminWorldTeleportItemEventHandlerTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/28-player-interaction/3-メソッド仕様/28_3-サービス.md
     * 章・見出し: # 28_3-サービス > ## 2. 候補解決
     * 検証契約: 管理者用テレポートアイテムの右クリックは ITEM_USE の CLAIM_AND_CANCEL 候補になる。
     */
    @Test
    void markedItemProducesRightClickCandidate() {
        Player player = server().addPlayer();
        AdminWorldTeleportItemService itemService = new AdminWorldTeleportItemService();
        player.getInventory().setItemInMainHand(itemService.createItem());
        AdminWorldTeleportItemEventHandler handler = handler(mock(WorldService.class), itemService);

        PlayerInputCandidate candidate = handler.resolve(context(player)).stream().findFirst().orElseThrow();

        assertEquals("admin-world-teleport-item", candidate.id());
        assertEquals(InteractionTier.ITEM_USE, candidate.tier());
        assertEquals(InputClaimPolicy.CLAIM_AND_CANCEL, candidate.claimPolicy());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/17-world/17_4-統合フロー.md
     * 章・見出し: # 17_4-統合フロー > ## 6. 管理者用ワールドテレポートアイテム > ### 処理要点
     * 検証契約: 管理者権限を失ったプレイヤーはアイテムを持っていてもGUIを開けず、ワールド一覧を取得しない。
     */
    @Test
    void nonAdminCannotOpenTeleportGui() {
        Player player = server().addPlayer();
        AstPlayer astPlayer = mock(AstPlayer.class);
        when(astPlayer.hasAdminPermission()).thenReturn(false);
        WorldService worldService = mock(WorldService.class);
        PlayerMessageService messageService = mock(PlayerMessageService.class);
        AdminWorldTeleportGui gui = new AdminWorldTeleportGui();
        AdminWorldTeleportItemEventHandler handler = new AdminWorldTeleportItemEventHandler(
                mock(AstralRecord.class),
                worldService,
                new AdminWorldTeleportItemService(),
                gui
        );

        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class);
             MockedStatic<PlayerMessageService> messages = mockStatic(PlayerMessageService.class)) {
            cache.when(() -> AstPlayerCache.get(player)).thenReturn(astPlayer);
            messages.when(PlayerMessageService::getInstance).thenReturn(messageService);

            assertFalse(handler.open(player));

            verify(messageService).send(astPlayer, PlayerMsgId.P_5707);
        }

        verify(worldService, never()).getAll();
        assertFalse(gui.isInventory(player.getOpenInventory().getTopInventory()));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/17-world/17_4-統合フロー.md
     * 章・見出し: # 17_4-統合フロー > ## 6. 管理者用ワールドテレポートアイテム > ### 処理要点
     * 検証契約: GUIのワールド選択はスポーン地点を解決してから WorldService の非同期転送へ委譲する。
     */
    @Test
    void destinationClickDelegatesToWorldServiceTeleport() {
        Player player = server().addPlayer();
        WorldMasterData world = world("admin_target", "管理者テスト先", WorldType.DUNGEON);
        WorldService worldService = mock(WorldService.class);
        when(worldService.getAll()).thenReturn(List.of(world));
        when(worldService.getById(world.id())).thenReturn(world);
        when(worldService.resolveOrLoadSpawnLocation(world)).thenReturn(
                new Location(player.getWorld(), 10.0D, 64.0D, 10.0D)
        );
        when(worldService.teleportToSpawnAsync(player, world)).thenReturn(new CompletableFuture<>());
        AdminWorldTeleportGui gui = new AdminWorldTeleportGui();
        AdminWorldTeleportItemEventHandler handler = new AdminWorldTeleportItemEventHandler(
                mock(AstralRecord.class),
                worldService,
                new AdminWorldTeleportItemService(),
                gui
        );
        AstPlayer astPlayer = mock(AstPlayer.class);
        when(astPlayer.hasAdminPermission()).thenReturn(true);
        gui.open(player, List.of(world));
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        when(event.getView()).thenReturn(player.getOpenInventory());
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getRawSlot()).thenReturn(0);

        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class)) {
            cache.when(() -> AstPlayerCache.get(player)).thenReturn(astPlayer);

            handler.onInventoryClick(event);
        }

        verify(event).setCancelled(true);
        verify(worldService).resolveOrLoadSpawnLocation(world);
        verify(worldService).teleportToSpawnAsync(player, world);
    }

    private AdminWorldTeleportItemEventHandler handler(
            WorldService worldService,
            AdminWorldTeleportItemService itemService
    ) {
        return new AdminWorldTeleportItemEventHandler(
                mock(AstralRecord.class),
                worldService,
                itemService,
                handlerGui()
        );
    }

    private AdminWorldTeleportGui handlerGui() {
        return new AdminWorldTeleportGui();
    }

    private PlayerInputContext<PlayerInteractionSnapshot> context(Player player) {
        PlayerInteractionSnapshot snapshot = new PlayerInteractionSnapshot(
                player,
                mock(Event.class),
                EquipmentSlot.HAND,
                Action.RIGHT_CLICK_AIR,
                null,
                null,
                null,
                false,
                Objects.requireNonNull(PlayerInteractionRayTrace.create(
                        new org.bukkit.util.Vector(0.0D, 64.0D, 0.0D),
                        new org.bukkit.util.Vector(0.0D, 0.0D, 1.0D),
                        8.0D
                )),
                8.0D
        );
        return new PlayerInputContext<>(
                player.getUniqueId(),
                1L,
                InputFamily.RIGHT_CLICK,
                InputSource.SYNTHETIC,
                snapshot
        );
    }

    private WorldMasterData world(String id, String displayName, WorldType type) {
        return new WorldMasterData(
                1,
                id,
                displayName,
                type,
                id,
                "world_instances",
                false,
                false,
                0,
                false,
                false,
                false,
                true,
                WorldSpawnLocation.defaultLocation(),
                "管理者用テストワールド",
                null,
                null,
                null
        );
    }
}
