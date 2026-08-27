package io.github.maaasu.astralRecord.feature.teleporter.service;

import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.teleporter.event.TeleporterGuiEventHandler;
import io.github.maaasu.astralRecord.feature.teleporter.gui.TeleporterGui;
import io.github.maaasu.astralRecord.feature.teleporter.model.WaystoneDefinition;
import io.github.maaasu.astralRecord.feature.teleporter.repository.AccountWaystoneRepository;
import io.github.maaasu.astralRecord.feature.teleporter.repository.WaystoneDefinitionRepository;
import io.github.maaasu.astralRecord.feature.teleporter.view.WaystonePacketView;
import io.github.maaasu.astralRecord.feature.user.model.UserModel;
import io.github.maaasu.astralRecord.feature.world.service.WorldService;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TeleporterServicePersistenceTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/25-teleporter/3-メソッド仕様/25_3-GUI・View.md
     * 章・見出し: # 25_3-GUI・View > ## ウェイストーンからの GUI 表示音
     * 検証契約: ウェイストーン導線で GUI を開けた場合だけ開く音を一度再生する。
     */
    @Test
    void openGuiPlaysOpenSoundAfterHandlerOpensWaystoneGui() {
        TeleporterService service = service(mock(WaystoneDefinitionRepository.class));
        TeleporterGui gui = mock(TeleporterGui.class);
        InventoryService inventoryService = mock(InventoryService.class);
        TeleporterGuiEventHandler handler = new TeleporterGuiEventHandler(gui, service, inventoryService);
        service.setRuntimeServices(
                inventoryService,
                mock(WorldService.class),
                mock(WaystonePacketView.class),
                gui,
                handler,
                mock(ParticleDisplayService.class)
        );
        Player player = mock(Player.class);
        AstPlayer astPlayer = mock(AstPlayer.class);
        Location location = mock(Location.class);
        WaystoneDefinition source = definition("source", "Source");
        when(player.getLocation()).thenReturn(location);
        doAnswer(invocation -> {
            invocation.getArgument(4, Runnable.class).run();
            return null;
        }).when(gui).open(any(), any(), any(), anyInt(), any(Runnable.class));

        service.openGui(player, astPlayer, source, 0);

        verify(gui).open(eq(player), eq(astPlayer), eq(source), eq(0), any(Runnable.class));
        verify(player).playSound(location, Sound.BLOCK_CHEST_OPEN, SoundCategory.PLAYERS, 0.6F, 1.28F);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/25-teleporter/3-メソッド仕様/25_3-GUI・View.md
     * 章・見出し: # 25_3-GUI・View > ## ウェイストーンからの GUI 表示音
     * 検証契約: GUI遷移が中止され表示完了callbackが呼ばれない場合、開く音を再生しない。
     */
    @Test
    void openGuiDoesNotPlayOpenSoundWhenGuiOpenIsCancelled() {
        TeleporterService service = service(mock(WaystoneDefinitionRepository.class));
        TeleporterGui gui = mock(TeleporterGui.class);
        InventoryService inventoryService = mock(InventoryService.class);
        TeleporterGuiEventHandler handler = new TeleporterGuiEventHandler(gui, service, inventoryService);
        service.setRuntimeServices(
                inventoryService,
                mock(WorldService.class),
                mock(WaystonePacketView.class),
                gui,
                handler,
                mock(ParticleDisplayService.class)
        );
        Player player = mock(Player.class);

        service.openGui(player, mock(AstPlayer.class), definition("source", "Source"), 0);

        verify(gui).open(any(), any(), any(), anyInt(), any(Runnable.class));
        verify(player, never()).playSound(any(Location.class), any(Sound.class), any(SoundCategory.class), anyFloat(), anyFloat());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/25-teleporter/3-メソッド仕様/25_3-サービス.md
     * 章・見出し: # 25_3-サービス > ## ウェイストーン登録
     * 検証契約: 定義追加後の全件保存が失敗した場合は追加cacheを削除し例外を伝播する。
     */
    @Test
    void createWaystoneRollsBackCacheWhenPersistenceFails() {
        WaystoneDefinitionRepository repository = mock(WaystoneDefinitionRepository.class);
        TeleporterService service = service(repository);
        AstPlayer astPlayer = mock(AstPlayer.class);
        Player player = mock(Player.class);
        UserModel user = mock(UserModel.class);
        Location location = mock(Location.class);
        World world = mock(World.class);
        when(astPlayer.getBukkit()).thenReturn(player);
        when(astPlayer.getUser()).thenReturn(user);
        when(user.getUuid()).thenReturn(UUID.randomUUID());
        when(player.getLocation()).thenReturn(location);
        when(location.getWorld()).thenReturn(world);
        when(location.getBlockX()).thenReturn(10);
        when(location.getBlockY()).thenReturn(64);
        when(location.getBlockZ()).thenReturn(-4);
        when(location.getYaw()).thenReturn(90.0F);
        when(location.getPitch()).thenReturn(5.0F);
        when(world.getName()).thenReturn("world");
        doThrow(new IllegalStateException("save failed"))
                .when(repository).saveAll(any());

        assertThrows(
                IllegalStateException.class,
                () -> service.createWaystone(astPlayer, "North Gate", true, 100L, null)
        );

        assertTrue(service.getAll().isEmpty());
        verify(repository).saveAll(any());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/25-teleporter/3-メソッド仕様/25_3-サービス.md
     * 章・見出し: # 25_3-サービス > ## ウェイストーン削除
     * 検証契約: 削除後の保存が失敗した場合は変更前cacheを元の順序ごと復元し例外を伝播する。
     */
    @Test
    void removeWaystoneRestoresCacheAndOrderWhenPersistenceFails() {
        WaystoneDefinitionRepository repository = mock(WaystoneDefinitionRepository.class);
        TeleporterService service = service(repository);
        WaystoneDefinition first = definition("first", "First");
        WaystoneDefinition second = definition("second", "Second");
        service.replaceDefinitionSnapshot(List.of(first, second));
        doThrow(new IllegalStateException("save failed"))
                .when(repository).saveAll(any());

        assertThrows(IllegalStateException.class, () -> service.removeWaystone(first.id()));

        assertEquals(List.of(first, second), List.copyOf(service.getAll()));
        verify(repository).saveAll(any());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/25-teleporter/3-メソッド仕様/25_3-サービス.md
     * 章・見出し: # 25_3-サービス > ## ウェイストーンテレポート
     * 検証契約: WorldServiceの成功callback後に、移動先ウェイストーンIDを通知する。
     */
    @Test
    void teleportToWaystoneNotifiesSuccessListenerAfterSuccessfulTeleport() {
        TeleporterService service = service(mock(WaystoneDefinitionRepository.class));
        InventoryService inventoryService = mock(InventoryService.class);
        WorldService worldService = mock(WorldService.class);
        service.setRuntimeServices(
                inventoryService,
                worldService,
                mock(WaystonePacketView.class),
                mock(TeleporterGui.class),
                mock(TeleporterGuiEventHandler.class),
                mock(ParticleDisplayService.class)
        );

        Player player = mock(Player.class);
        AstPlayer astPlayer = mock(AstPlayer.class);
        WaystoneDefinition source = definition("source", "Source");
        WaystoneDefinition target = definition("target-waystone", "Target");
        World world = mock(World.class);
        @SuppressWarnings("unchecked")
        BiConsumer<AstPlayer, String> listener = mock(BiConsumer.class);
        CompletableFuture<Boolean> teleportResult = new CompletableFuture<>();

        PlayerMessageService messageService = mock(PlayerMessageService.class);
        try (var bukkit = org.mockito.Mockito.mockStatic(Bukkit.class);
             var messages = org.mockito.Mockito.mockStatic(PlayerMessageService.class)) {
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
            Location location = target.toLocation();
            when(worldService.teleportPlayerAsync(eq(player), eq(location), any(Runnable.class)))
                    .thenAnswer(invocation -> {
                        invocation.getArgument(2, Runnable.class).run();
                        return teleportResult;
                    });
            service.setTeleportSuccessListener(listener);
            messages.when(PlayerMessageService::getInstance).thenReturn(messageService);

            service.teleportToWaystone(player, astPlayer, source, target);

            verify(listener, times(1)).accept(astPlayer, "target-waystone");
            verify(messageService).send(astPlayer, PlayerMsgId.P_5953, "Target");
        }
    }

    private TeleporterService service(WaystoneDefinitionRepository repository) {
        return new TeleporterService(
                mock(Plugin.class),
                repository,
                mock(AccountWaystoneRepository.class)
        );
    }

    private WaystoneDefinition definition(String id, String name) {
        return new WaystoneDefinition(
                id,
                name,
                "world",
                0.0D,
                64.0D,
                0.0D,
                0.0F,
                0.0F,
                false,
                0L,
                null,
                Instant.EPOCH,
                "test"
        );
    }
}
