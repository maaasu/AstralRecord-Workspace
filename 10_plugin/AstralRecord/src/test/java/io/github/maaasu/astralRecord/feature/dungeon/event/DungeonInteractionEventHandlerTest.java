package io.github.maaasu.astralRecord.feature.dungeon.event;

import io.github.maaasu.astralRecord.feature.dungeon.gui.DungeonCancelGui;
import io.github.maaasu.astralRecord.feature.dungeon.gui.DungeonRewardGui;
import io.github.maaasu.astralRecord.feature.dungeon.service.DungeonService;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DungeonInteractionEventHandlerTest {
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
        private final InventoryService inventoryService = mock(InventoryService.class);
        private final Player player = mock(Player.class);
        private final AstPlayer astPlayer = mock(AstPlayer.class);
        private final Inventory top = mock(Inventory.class);
        private final InventoryView view = mock(InventoryView.class);
        private final DungeonInteractionEventHandler handler =
                new DungeonInteractionEventHandler(service, inventoryService);

        private TestContext() {
            when(view.getTopInventory()).thenReturn(top);
            when(player.getUniqueId()).thenReturn(UUID.randomUUID());
            when(service.cancelGui()).thenReturn(cancelGui);
            when(service.rewardGui()).thenReturn(rewardGui);
        }
    }
}
