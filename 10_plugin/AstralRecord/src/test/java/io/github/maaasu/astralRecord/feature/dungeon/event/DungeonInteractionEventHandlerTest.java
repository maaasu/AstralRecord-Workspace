package io.github.maaasu.astralRecord.feature.dungeon.event;

import io.github.maaasu.astralRecord.feature.dungeon.gui.DungeonCancelGui;
import io.github.maaasu.astralRecord.feature.dungeon.gui.DungeonArchiveGui;
import io.github.maaasu.astralRecord.feature.dungeon.gui.DungeonEmergencyTeleportGui;
import io.github.maaasu.astralRecord.feature.dungeon.gui.DungeonMapGui;
import io.github.maaasu.astralRecord.feature.dungeon.gui.DungeonRewardGui;
import io.github.maaasu.astralRecord.feature.dungeon.service.DungeonService;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.shared.interaction.InputFamily;
import io.github.maaasu.astralRecord.shared.interaction.InputClaimPolicy;
import io.github.maaasu.astralRecord.shared.interaction.InputSource;
import io.github.maaasu.astralRecord.shared.interaction.InteractionCandidateOrder;
import io.github.maaasu.astralRecord.shared.interaction.InteractionTier;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInputCandidate;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInputContext;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInteractionSnapshot;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInteractionRayTrace;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.FluidCollisionMode;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DungeonInteractionEventHandlerTest {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 6. クリア報酬と30秒回収
     * 検証契約: 受取対象プレイヤーが報酬CHESTを視認して右クリックした場合、共通gateway候補を実行して報酬GUIを開く。
     */
    @Test
    void resolvesAndExecutesTargetedRewardChestCandidate() {
        TestContext context = new TestContext();
        Block chest = rewardChestBlock(context);
        PlayerInteractionSnapshot snapshot = rewardSnapshot(context, 8.0D);
        UUID sessionId = UUID.randomUUID();
        DungeonService.DungeonRewardChestTarget target =
                new DungeonService.DungeonRewardChestTarget(sessionId, chest);
        when(context.service.findRewardChestTarget(context.player)).thenReturn(target);

        List<PlayerInputCandidate> candidates = List.copyOf(context.handler.resolve(rightClick(snapshot)));

        assertEquals(1, candidates.size());
        PlayerInputCandidate candidate = candidates.getFirst();
        assertEquals("dungeon-reward-chest", candidate.id());
        assertEquals(InteractionTier.WORLD_INTERACTION, candidate.tier());
        assertEquals(2.0D, candidate.hitDistance());
        assertEquals(InteractionCandidateOrder.DUNGEON_CONTROLLER, candidate.stableOrder());
        assertEquals(sessionId + ":" + context.world.getUID() + ":2:64:3", candidate.targetKey());
        assertEquals(InputClaimPolicy.CLAIM_AND_CANCEL, candidate.claimPolicy());
        assertTrue(candidate.executeIfValid());
        verify(context.service).openRewardChest(context.player, chest);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 6. クリア報酬と30秒回収
     * 検証契約: off hand由来の右クリックでは報酬CHEST候補を公開しない。
     */
    @Test
    void doesNotResolveRewardChestForOffHandInput() {
        TestContext context = new TestContext();
        PlayerInteractionSnapshot snapshot = rewardSnapshot(context, 8.0D, EquipmentSlot.OFF_HAND);

        assertTrue(context.handler.resolve(rightClick(snapshot)).isEmpty());
        verify(context.service, never()).findRewardChestTarget(context.player);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 6. クリア報酬と30秒回収
     * 検証契約: eligible snapshotに含まれないプレイヤーには報酬CHEST候補を公開しない。
     */
    @Test
    void doesNotResolveRewardChestForIneligiblePlayer() {
        TestContext context = new TestContext();
        PlayerInteractionSnapshot snapshot = rewardSnapshot(context, 8.0D);
        when(context.service.findRewardChestTarget(context.player)).thenReturn(null);

        assertTrue(context.handler.resolve(rightClick(snapshot)).isEmpty());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 6. クリア報酬と30秒回収
     * 検証契約: 報酬CHESTが視線ray外または遮蔽後にある場合は共通gateway候補にしない。
     */
    @Test
    void doesNotResolveRewardChestOutsideVisibleRay() {
        TestContext context = new TestContext();
        Block chest = rewardChestBlock(context);
        PlayerInteractionSnapshot snapshot = rewardSnapshot(context, 1.0D);
        DungeonService.DungeonRewardChestTarget target =
                new DungeonService.DungeonRewardChestTarget(UUID.randomUUID(), chest);
        when(context.service.findRewardChestTarget(context.player)).thenReturn(target);

        assertTrue(context.handler.resolve(rightClick(snapshot)).isEmpty());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 6. クリア報酬と30秒回収
     * 検証契約: 勝者実行直前に対象セッションまたは報酬CHEST座標が変わった場合はGUIを開かない。
     */
    @Test
    void doesNotExecuteWhenRewardTargetChanges() {
        TestContext context = new TestContext();
        Block selectedChest = rewardChestBlock(context);
        Block currentChest = rewardChestBlock(context);
        when(currentChest.getX()).thenReturn(4);
        when(currentChest.getBoundingBox()).thenReturn(new BoundingBox(
                4.0D, 64.0D, 3.0D, 5.0D, 65.0D, 4.0D));
        DungeonService.DungeonRewardChestTarget selected =
                new DungeonService.DungeonRewardChestTarget(UUID.randomUUID(), selectedChest);
        DungeonService.DungeonRewardChestTarget current =
                new DungeonService.DungeonRewardChestTarget(UUID.randomUUID(), currentChest);
        when(context.service.findRewardChestTarget(context.player)).thenReturn(selected, current);
        PlayerInputCandidate candidate = context.handler.resolve(
                rightClick(rewardSnapshot(context, 8.0D))).iterator().next();

        assertFalse(candidate.executeIfValid());
        verify(context.service, never()).openRewardChest(context.player, selectedChest);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 6. クリア報酬と30秒回収
     * 検証契約: 勝者実行直前に参加・eligible・クリア待機などのセッション条件が失効した場合はGUIを開かない。
     */
    @Test
    void doesNotExecuteWhenRewardSessionBecomesIneligible() {
        TestContext context = new TestContext();
        Block chest = rewardChestBlock(context);
        DungeonService.DungeonRewardChestTarget target =
                new DungeonService.DungeonRewardChestTarget(UUID.randomUUID(), chest);
        when(context.service.findRewardChestTarget(context.player)).thenReturn(
                target, (DungeonService.DungeonRewardChestTarget) null);
        PlayerInputCandidate candidate = context.handler.resolve(
                rightClick(rewardSnapshot(context, 8.0D))).iterator().next();

        assertFalse(candidate.executeIfValid());
        verify(context.service, never()).openRewardChest(context.player, chest);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 6. クリア報酬と30秒回収
     * 検証契約: 勝者実行直前に報酬座標がCHESTでなくなった場合はGUIを開かない。
     */
    @Test
    void doesNotExecuteWhenRewardChestDisappears() {
        TestContext context = new TestContext();
        Block chest = rewardChestBlock(context);
        DungeonService.DungeonRewardChestTarget target =
                new DungeonService.DungeonRewardChestTarget(UUID.randomUUID(), chest);
        when(context.service.findRewardChestTarget(context.player)).thenReturn(target);
        PlayerInputCandidate candidate = context.handler.resolve(
                rightClick(rewardSnapshot(context, 8.0D))).iterator().next();
        when(chest.getType()).thenReturn(Material.AIR);

        assertFalse(candidate.executeIfValid());
        verify(context.service, never()).openRewardChest(context.player, chest);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 6. クリア報酬と30秒回収
     * 検証契約: 勝者実行直前に視線が報酬CHESTから外れた場合はGUIを開かない。
     */
    @Test
    void doesNotExecuteWhenPlayerLooksAway() {
        TestContext context = new TestContext();
        Block chest = rewardChestBlock(context);
        DungeonService.DungeonRewardChestTarget target =
                new DungeonService.DungeonRewardChestTarget(UUID.randomUUID(), chest);
        when(context.service.findRewardChestTarget(context.player)).thenReturn(target);
        PlayerInputCandidate candidate = context.handler.resolve(
                rightClick(rewardSnapshot(context, 8.0D))).iterator().next();
        when(context.player.getEyeLocation()).thenReturn(
                new Location(context.world, 0.0D, 64.5D, 3.5D, 90.0F, 0.0F));

        assertFalse(candidate.executeIfValid());
        verify(context.service, never()).openRewardChest(context.player, chest);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 6. クリア報酬と30秒回収
     * 検証契約: 勝者実行直前に報酬CHESTより手前へ遮蔽物が現れた場合はGUIを開かない。
     */
    @Test
    void doesNotExecuteWhenRewardChestBecomesOccluded() {
        TestContext context = new TestContext();
        Block chest = rewardChestBlock(context);
        DungeonService.DungeonRewardChestTarget target =
                new DungeonService.DungeonRewardChestTarget(UUID.randomUUID(), chest);
        when(context.service.findRewardChestTarget(context.player)).thenReturn(target);
        PlayerInputCandidate candidate = context.handler.resolve(
                rightClick(rewardSnapshot(context, 8.0D))).iterator().next();
        RayTraceResult blockHit = mock(RayTraceResult.class);
        when(blockHit.getHitPosition()).thenReturn(new Vector(1.0D, 64.5D, 3.5D));
        when(context.world.rayTraceBlocks(
                any(Location.class),
                any(Vector.class),
                anyDouble(),
                eq(FluidCollisionMode.NEVER),
                eq(true)
        )).thenReturn(blockHit);

        assertFalse(candidate.executeIfValid());
        verify(context.service, never()).openRewardChest(context.player, chest);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 6. クリア報酬と30秒回収
     * 検証契約: ダンジョン報酬GUI上のInventoryDragEventをcancelして標準移動を防ぐ。
     */
    @Test
    void cancelsDragOverRewardInventory() {
        TestContext context = new TestContext();
        InventoryDragEvent event = mock(InventoryDragEvent.class);
        when(event.getView()).thenReturn(context.view);
        when(context.rewardGui.isInventory(context.top)).thenReturn(true);

        context.handler.onInventoryDrag(event);

        verify(event).setCancelled(true);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 6. クリア報酬と30秒回収
     * 検証契約: 報酬clickはGUI描画時にslotへ固定したclaim IDをserviceへ渡す。
     */
    @Test
    void delegatesPinnedClaimIdOnRewardClick() {
        TestContext context = new TestContext();
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        Player player = mock(Player.class);
        UUID playerId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID claimId = UUID.randomUUID();
        DungeonRewardGui.Holder holder = new DungeonRewardGui.Holder(
                sessionId, playerId, 0, List.of(claimId));
        when(event.getView()).thenReturn(context.view);
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getRawSlot()).thenReturn(0);
        when(player.getUniqueId()).thenReturn(playerId);
        when(context.rewardGui.holder(context.top)).thenReturn(holder);

        context.handler.onInventoryClick(event);

        verify(event).setCancelled(true);
        verify(context.service).handleRewardClick(player, sessionId, 0, 0, claimId);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 8. カルトグラフ > ### 8.2 現在ダンジョンマップ
     * 検証契約: 攻略済み部屋 slot のクリックは session と room ID を service へ渡し、成功時に地図を閉じる。
     */
    @Test
    void delegatesClearedRoomClickAndClosesMapOnSuccess() {
        TestContext context = new TestContext();
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        UUID sessionId = UUID.randomUUID();
        int roomId = 404;
        DungeonMapGui.Holder holder = new DungeonMapGui.Holder(
                sessionId, context.player.getUniqueId(), 0, java.util.Map.of(0, roomId));
        when(event.getView()).thenReturn(context.view);
        when(event.getWhoClicked()).thenReturn(context.player);
        when(event.getRawSlot()).thenReturn(0);
        when(context.mapGui.holder(context.top)).thenReturn(holder);
        when(context.service.teleportToClearedRoom(context.player, sessionId, roomId)).thenReturn(true);

        context.handler.onInventoryClick(event);

        verify(event).setCancelled(true);
        verify(context.service).teleportToClearedRoom(context.player, sessionId, roomId);
        verify(context.player).closeInventory();
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 8. カルトグラフ
     * 検証契約: 踏破記録詳細から戻る場合は、詳細を開いた一覧ページへ復帰する。
     */
    @Test
    void returnsToOriginatingArchiveListPage() {
        TestContext context = new TestContext();
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        UUID accountId = UUID.randomUUID();
        DungeonArchiveGui.DetailHolder holder = new DungeonArchiveGui.DetailHolder(
                context.player.getUniqueId(), accountId, "dungeon", 3, 0);
        when(event.getView()).thenReturn(context.view);
        when(event.getWhoClicked()).thenReturn(context.player);
        when(event.getRawSlot()).thenReturn(DungeonArchiveGui.BACK_SLOT);
        when(context.archiveGui.detailHolder(context.top)).thenReturn(holder);

        context.handler.onInventoryClick(event);

        verify(event).setCancelled(true);
        verify(context.service).openArchiveListPage(context.player, accountId, 3);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 5. 離脱・再参加・中止
     * 検証契約: 中止GUI表示中のplayer inventory clickを共通HotbarShortcut契約へ委譲する。
     */
    @Test
    void delegatesCancelGuiPlayerInventoryClickToHotbarSupport() {
        TestContext context = new TestContext();
        InventoryClickEvent event = playerInventoryClick(context);
        when(context.cancelGui.isInventory(context.top)).thenReturn(true);

        handleWithCachedPlayer(context, event);

        verify(context.inventoryService).handleInventoryControlClick(context.astPlayer, 17);
        verify(event, atLeastOnce()).setCancelled(true);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 6. クリア報酬と30秒回収
     * 検証契約: 報酬GUI表示中のplayer inventory clickを共通HotbarShortcut契約へ委譲する。
     */
    @Test
    void delegatesRewardGuiPlayerInventoryClickToHotbarSupport() {
        TestContext context = new TestContext();
        InventoryClickEvent event = playerInventoryClick(context);
        UUID playerId = context.player.getUniqueId();
        when(context.rewardGui.holder(context.top)).thenReturn(new DungeonRewardGui.Holder(
                UUID.randomUUID(), playerId, 0, List.of()));

        handleWithCachedPlayer(context, event);

        verify(context.inventoryService).handleInventoryControlClick(context.astPlayer, 17);
        verify(event, atLeastOnce()).setCancelled(true);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 6. クリア報酬と30秒回収
     * 検証契約: 共通click guardなどが取消済みにしたeventをDungeon固有slot処理で再処理しない。
     */
    @Test
    void ignoresAlreadyCancelledInventoryClick() {
        TestContext context = new TestContext();
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        when(event.isCancelled()).thenReturn(true);

        context.handler.onInventoryClick(event);

        verify(context.service, never()).cancelForLeader(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    private InventoryClickEvent playerInventoryClick(TestContext context) {
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        PlayerInventory playerInventory = mock(PlayerInventory.class);
        when(event.getView()).thenReturn(context.view);
        when(event.getWhoClicked()).thenReturn(context.player);
        when(event.getClickedInventory()).thenReturn(playerInventory);
        when(event.getSlot()).thenReturn(17);
        return event;
    }

    private PlayerInputContext<PlayerInteractionSnapshot> rightClick(PlayerInteractionSnapshot snapshot) {
        return new PlayerInputContext<>(
                snapshot.player().getUniqueId(),
                1L,
                InputFamily.RIGHT_CLICK,
                InputSource.PLAYER_INTERACT,
                snapshot
        );
    }

    private PlayerInteractionSnapshot rewardSnapshot(TestContext context, double blockingDistance) {
        return rewardSnapshot(context, blockingDistance, EquipmentSlot.HAND);
    }

    private PlayerInteractionSnapshot rewardSnapshot(
            TestContext context,
            double blockingDistance,
            EquipmentSlot hand
    ) {
        PlayerInteractionRayTrace ray = PlayerInteractionRayTrace.create(
                context.player.getEyeLocation().toVector(),
                context.player.getEyeLocation().getDirection(),
                8.0D
        );
        return new PlayerInteractionSnapshot(
                context.player,
                mock(Event.class),
                hand,
                Action.RIGHT_CLICK_AIR,
                null,
                null,
                null,
                false,
                ray,
                blockingDistance
        );
    }

    private Block rewardChestBlock(TestContext context) {
        Block block = mock(Block.class);
        when(block.getType()).thenReturn(Material.CHEST);
        when(block.getWorld()).thenReturn(context.world);
        when(block.getX()).thenReturn(2);
        when(block.getY()).thenReturn(64);
        when(block.getZ()).thenReturn(3);
        when(block.getBoundingBox()).thenReturn(new BoundingBox(2.0D, 64.0D, 3.0D, 3.0D, 65.0D, 4.0D));
        return block;
    }

    private void handleWithCachedPlayer(TestContext context, InventoryClickEvent event) {
        when(context.inventoryService.isHotbarShortcutMode(context.astPlayer)).thenReturn(true);
        when(context.inventoryService.handleInventoryControlClick(context.astPlayer, 17)).thenReturn(true);
        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class)) {
            cache.when(() -> AstPlayerCache.get(context.player)).thenReturn(context.astPlayer);
            context.handler.onInventoryClick(event);
        }
    }

    private static final class TestContext {
        private final DungeonService service = mock(DungeonService.class);
        private final DungeonCancelGui cancelGui = mock(DungeonCancelGui.class);
        private final DungeonRewardGui rewardGui = mock(DungeonRewardGui.class);
        private final DungeonMapGui mapGui = mock(DungeonMapGui.class);
        private final DungeonEmergencyTeleportGui emergencyTeleportGui = mock(DungeonEmergencyTeleportGui.class);
        private final DungeonArchiveGui archiveGui = mock(DungeonArchiveGui.class);
        private final InventoryService inventoryService = mock(InventoryService.class);
        private final Player player = mock(Player.class);
        private final World world = mock(World.class);
        private final AstPlayer astPlayer = mock(AstPlayer.class);
        private final Inventory top = mock(Inventory.class);
        private final InventoryView view = mock(InventoryView.class);
        private final DungeonInteractionEventHandler handler =
                new DungeonInteractionEventHandler(service, inventoryService);

        private TestContext() {
            when(view.getTopInventory()).thenReturn(top);
            when(player.getUniqueId()).thenReturn(UUID.randomUUID());
            when(player.getWorld()).thenReturn(world);
            when(player.getEyeLocation()).thenReturn(new Location(world, 0.0D, 64.5D, 3.5D, -90.0F, 0.0F));
            when(world.getUID()).thenReturn(UUID.randomUUID());
            when(service.cancelGui()).thenReturn(cancelGui);
            when(service.rewardGui()).thenReturn(rewardGui);
            when(service.mapGui()).thenReturn(mapGui);
            when(service.emergencyTeleportGui()).thenReturn(emergencyTeleportGui);
            when(service.archiveGui()).thenReturn(archiveGui);
        }
    }
}
