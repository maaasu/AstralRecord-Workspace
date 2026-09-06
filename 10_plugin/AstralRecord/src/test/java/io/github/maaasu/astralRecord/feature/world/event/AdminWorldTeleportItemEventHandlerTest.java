package io.github.maaasu.astralRecord.feature.world.event;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.player.service.StoneButtonReachService;
import io.github.maaasu.astralRecord.feature.world.gui.AdminWorldTeleportGui;
import io.github.maaasu.astralRecord.feature.world.model.WorldMasterData;
import io.github.maaasu.astralRecord.feature.world.model.WorldSpawnLocation;
import io.github.maaasu.astralRecord.feature.world.model.WorldType;
import io.github.maaasu.astralRecord.feature.world.service.AdminWorldTeleportItemService;
import io.github.maaasu.astralRecord.feature.world.service.WorldService;
import io.github.maaasu.astralRecord.shared.interaction.InputClaimPolicy;
import io.github.maaasu.astralRecord.shared.interaction.InputFamily;
import io.github.maaasu.astralRecord.shared.interaction.InputSource;
import io.github.maaasu.astralRecord.shared.interaction.InteractionCandidateOrder;
import io.github.maaasu.astralRecord.shared.interaction.InteractionTier;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInputCandidate;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInputContext;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInputDispatcher;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInteractionGatewayEventHandler;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInteractionRayTrace;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInteractionSnapshot;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminWorldTeleportItemEventHandlerTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/28-player-interaction/3-メソッド仕様/28_3-サービス.md
     * 章・見出し: # 28_3-サービス > ## 2. 候補解決
     * 検証契約: 管理者用テレポートアイテムの右クリックは INPUT_LOCK の CLAIM_AND_CANCEL 候補になり、
     * プレイヤーモードのブロッククリックguardより先に評価される。
     */
    @Test
    void markedItemProducesRightClickCandidate() {
        Player player = server().addPlayer();
        AdminWorldTeleportItemService itemService = new AdminWorldTeleportItemService();
        player.getInventory().setItemInMainHand(itemService.createItem());
        AdminWorldTeleportItemEventHandler handler = handler(mock(WorldService.class), itemService);

        PlayerInputCandidate candidate = handler.resolve(context(player)).stream().findFirst().orElseThrow();

        assertEquals("admin-world-teleport-item", candidate.id());
        assertEquals(InteractionTier.INPUT_LOCK, candidate.tier());
        assertEquals(InteractionCandidateOrder.ADMIN_WORLD_TELEPORT_ITEM, candidate.stableOrder());
        assertEquals(InputClaimPolicy.CLAIM_AND_CANCEL, candidate.claimPolicy());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/28-player-interaction/3-メソッド仕様/28_3-サービス.md
     * 章・見出し: # 28_3-サービス > ## 2. 候補解決
     * 検証契約: 管理者用ワールドテレポートアイテムは右クリックだけを捕捉し、左クリック候補を生成しない。
     */
    @Test
    void leftClickDoesNotCreateAdminTeleportCandidate() {
        Player player = server().addPlayer();
        AdminWorldTeleportItemService itemService = new AdminWorldTeleportItemService();
        player.getInventory().setItemInMainHand(itemService.createItem());
        AdminWorldTeleportItemEventHandler handler = handler(mock(WorldService.class), itemService);

        assertTrue(handler.resolve(context(player, InputFamily.LEFT_CLICK, Action.LEFT_CLICK_AIR)).isEmpty());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/17-world/3-メソッド仕様/17_3-サービス.md
     * 章・見出し: # 17_3-サービス > ## 管理者用ワールドテレポートアイテム・GUI
     * 検証契約: 専用コンパスの右クリックは外部アイテムツールへ渡る前にブロック操作を拒否し、
     * gateway が候補解決を継続できる入力状態にする。
     */
    @Test
    void rightClickIsSuppressedBeforeExternalToolWhileGatewayCanContinue() {
        Player player = server().addPlayer();
        AdminWorldTeleportItemService itemService = new AdminWorldTeleportItemService();
        Block block = player.getWorld().getBlockAt(0, 64, 0);
        PlayerInteractEvent event = new PlayerInteractEvent(
                player,
                Action.RIGHT_CLICK_BLOCK,
                itemService.createItem(),
                block,
                BlockFace.UP,
                EquipmentSlot.HAND
        );
        AdminWorldTeleportItemEventHandler handler = handler(mock(WorldService.class), itemService);

        handler.onPlayerInteract(event);

        assertEquals(Event.Result.DENY, event.useInteractedBlock());
        assertEquals(Event.Result.DEFAULT, event.useItemInHand());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/17-world/17_4-統合フロー.md
     * 章・見出し: # 17_4-統合フロー > ## 6. 管理者用ワールドテレポートアイテム > ### 処理要点
     * 検証契約: 先行抑止済みの右クリックを共通 gateway が受け取り、管理者用ワールド一覧GUIを開く。
     */
    @Test
    void rightClickOpensGuiThroughGatewayAfterPreCapture() {
        Player player = mock(Player.class);
        World bukkitWorld = mock(World.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        InventoryView view = mock(InventoryView.class);
        AtomicReference<Inventory> openedInventory = new AtomicReference<>();
        UUID playerId = UUID.randomUUID();
        AdminWorldTeleportItemService itemService = new AdminWorldTeleportItemService();
        ItemStack item = itemService.createItem();
        WorldService worldService = mock(WorldService.class);
        AdminWorldTeleportGui gui = new AdminWorldTeleportGui();
        AstralRecord plugin = mock(AstralRecord.class);
        AstPlayer astPlayer = mock(AstPlayer.class);
        PlayerInteractEvent event = mock(PlayerInteractEvent.class);

        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getWorld()).thenReturn(bukkitWorld);
        when(player.getEyeLocation()).thenReturn(new Location(bukkitWorld, 0.0D, 64.0D, 0.0D));
        when(player.getLocation()).thenReturn(new Location(bukkitWorld, 0.0D, 64.0D, 0.0D));
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getItem(EquipmentSlot.HAND)).thenReturn(item);
        when(view.getTopInventory()).thenAnswer(ignored -> openedInventory.get());
        when(player.getOpenInventory()).thenReturn(view);
        doAnswer(invocation -> {
            openedInventory.set(invocation.getArgument(0));
            return view;
        }).when(player).openInventory(any(Inventory.class));
        when(bukkitWorld.rayTraceBlocks(
                any(Location.class),
                any(org.bukkit.util.Vector.class),
                anyDouble(),
                eq(FluidCollisionMode.NEVER),
                anyBoolean()
        )).thenReturn(null);
        when(event.getPlayer()).thenReturn(player);
        when(event.getHand()).thenReturn(EquipmentSlot.HAND);
        when(event.getAction()).thenReturn(Action.RIGHT_CLICK_AIR);
        when(event.getClickedBlock()).thenReturn(null);
        when(event.getBlockFace()).thenReturn(null);
        when(event.useInteractedBlock()).thenReturn(Event.Result.DENY);
        when(event.useItemInHand()).thenReturn(Event.Result.DEFAULT);
        when(astPlayer.hasAdminPermission()).thenReturn(true);
        when(worldService.getAll()).thenReturn(List.of(world("base", "拠点", WorldType.BASE)));
        when(plugin.getServer()).thenReturn(server());

        AdminWorldTeleportItemEventHandler handler = new AdminWorldTeleportItemEventHandler(
                plugin,
                worldService,
                itemService,
                gui
        );
        PlayerInteractionGatewayEventHandler gateway = new PlayerInteractionGatewayEventHandler(
                plugin,
                List.of(handler),
                ignored -> false,
                ignored -> false,
                ignored -> {
                }
        );

        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class)) {
            cache.when(() -> AstPlayerCache.get(player)).thenReturn(astPlayer);
            handler.onPlayerInteract(event);
            gateway.onPlayerInteract(event);
        }

        assertTrue(gui.isInventory(openedInventory.get()));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/17-world/3-メソッド仕様/17_3-コマンド.md
     * 章・見出し: # 17_3-コマンド > ## `/adminitem`
     * 検証契約: 左クリックは管理者用処理で変更せず、Compass のナビゲーション操作へ委譲する。
     */
    @Test
    void leftClickIsNotSuppressedForCompassNavigation() {
        Player player = server().addPlayer();
        AdminWorldTeleportItemService itemService = new AdminWorldTeleportItemService();
        Block block = player.getWorld().getBlockAt(0, 64, 0);
        PlayerInteractEvent event = new PlayerInteractEvent(
                player,
                Action.LEFT_CLICK_BLOCK,
                itemService.createItem(),
                block,
                BlockFace.UP,
                EquipmentSlot.HAND
        );
        AdminWorldTeleportItemEventHandler handler = handler(mock(WorldService.class), itemService);

        handler.onPlayerInteract(event);

        assertEquals(Event.Result.ALLOW, event.useInteractedBlock());
        assertEquals(Event.Result.DEFAULT, event.useItemInHand());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/28-player-interaction/3-メソッド仕様/28_3-サービス.md
     * 章・見出し: # 28_3-サービス > ## 4. 勝者選択
     * 検証契約: ブロックを右クリックした場合でも、管理者用アイテム候補はプレイヤーモードのブロッククリックguardより先に選択される。
     */
    @Test
    void adminTeleportItemWinsOverPlayerModeBlockGuard() {
        Player player = server().addPlayer();
        AdminWorldTeleportItemService itemService = new AdminWorldTeleportItemService();
        player.getInventory().setItemInMainHand(itemService.createItem());
        WorldService worldService = mock(WorldService.class);
        AdminWorldTeleportItemEventHandler handler = handler(worldService, itemService);
        StoneButtonReachService stoneButtonReachService = new StoneButtonReachService(mock(Plugin.class), worldService);
        AstPlayer astPlayer = mock(AstPlayer.class);
        AccountModel account = mock(AccountModel.class);
        when(astPlayer.getAccount()).thenReturn(account);
        when(account.getMode()).thenReturn(AccountMode.PLAYER);
        Block clickedBlock = player.getWorld().getBlockAt(0, 64, 0);
        clickedBlock.setType(Material.STONE);
        PlayerInputDispatcher<PlayerInteractionSnapshot> dispatcher = new PlayerInputDispatcher<>(List.of(
                handler::resolve,
                stoneButtonReachService::resolve
        ));

        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class)) {
            cache.when(() -> AstPlayerCache.get(player)).thenReturn(astPlayer);
            PlayerInputCandidate winner = dispatcher.select(context(
                    player,
                    InputFamily.RIGHT_CLICK,
                    Action.RIGHT_CLICK_BLOCK,
                    InputSource.PLAYER_INTERACT,
                    clickedBlock
            )).orElseThrow();

            assertEquals("admin-world-teleport-item", winner.id());
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/28-player-interaction/3-メソッド仕様/28_3-サービス.md
     * 章・見出し: # 28_3-サービス > ## 4. 勝者選択
     * 検証契約: グローバル入力ロックは管理者用アイテムより先に選択され、ロード中などの入力抑止を維持する。
     */
    @Test
    void globalInputLockWinsOverAdminTeleportItem() {
        Player player = server().addPlayer();
        AdminWorldTeleportItemService itemService = new AdminWorldTeleportItemService();
        player.getInventory().setItemInMainHand(itemService.createItem());
        AdminWorldTeleportItemEventHandler handler = handler(mock(WorldService.class), itemService);
        PlayerInputCandidate globalLock = new PlayerInputCandidate(
                "player-input-lock",
                InteractionTier.INPUT_LOCK,
                0.0D,
                InteractionCandidateOrder.GLOBAL_INPUT_LOCK,
                player.getUniqueId().toString(),
                InputClaimPolicy.CLAIM_AND_CANCEL,
                () -> {
                }
        );
        PlayerInputDispatcher<PlayerInteractionSnapshot> dispatcher = new PlayerInputDispatcher<>(List.of(
                handler::resolve,
                ignored -> List.of(globalLock)
        ));

        PlayerInputCandidate winner = dispatcher.select(context(player)).orElseThrow();

        assertEquals("player-input-lock", winner.id());
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
        return context(player, InputFamily.RIGHT_CLICK, Action.RIGHT_CLICK_AIR);
    }

    private PlayerInputContext<PlayerInteractionSnapshot> context(
            Player player,
            InputFamily family,
            Action action
    ) {
        return context(player, family, action, InputSource.SYNTHETIC, null);
    }

    private PlayerInputContext<PlayerInteractionSnapshot> context(
            Player player,
            InputFamily family,
            Action action,
            InputSource source,
            Block clickedBlock
    ) {
        PlayerInteractionSnapshot snapshot = new PlayerInteractionSnapshot(
                player,
                mock(Event.class),
                EquipmentSlot.HAND,
                action,
                null,
                clickedBlock,
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
                family,
                source,
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
