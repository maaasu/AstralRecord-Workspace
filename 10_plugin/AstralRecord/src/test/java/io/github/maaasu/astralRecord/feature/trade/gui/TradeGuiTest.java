package io.github.maaasu.astralRecord.feature.trade.gui;

import io.github.maaasu.astralRecord.feature.trade.model.TradeSession;
import io.github.maaasu.astralRecord.feature.trade.model.TradeSessionStatus;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TradeGuiTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/22-trade/22_3-メソッド仕様.md
     * 章・見出し: # 22_3-メソッド仕様 > ## 送信確定
     * 検証契約: COMMITTING 中の送信枠は時計アイコンへ置換し、通常の送信枠は chest のまま表示する。
     */
    @Test
    void committingSessionShowsProcessingClockInSendSlot() {
        UUID viewerId = UUID.randomUUID();
        TradeSession session = new TradeSession(
            UUID.randomUUID(), viewerId, UUID.randomUUID(), "sender",
            UUID.randomUUID(), UUID.randomUUID(), "recipient", Instant.now()
        );
        Inventory inventory = Bukkit.createInventory(
            new TradeGui.TradeHolder(session.getSessionId(), viewerId), TradeGuiLayout.SIZE
        );
        InventoryView view = mock(InventoryView.class);
        Player viewer = mock(Player.class);
        when(viewer.getUniqueId()).thenReturn(viewerId);
        when(viewer.getOpenInventory()).thenReturn(view);
        when(view.getTopInventory()).thenReturn(inventory);
        TradeGui gui = new TradeGui();

        assertTrue(gui.refreshIfOpen(viewer, session));
        assertEquals(Material.CHEST, inventory.getItem(TradeGuiLayout.SEND_SLOT).getType());

        session.setStatus(TradeSessionStatus.COMMITTING);
        assertTrue(gui.refreshIfOpen(viewer, session));
        assertEquals(Material.CLOCK, inventory.getItem(TradeGuiLayout.SEND_SLOT).getType());
    }
}
