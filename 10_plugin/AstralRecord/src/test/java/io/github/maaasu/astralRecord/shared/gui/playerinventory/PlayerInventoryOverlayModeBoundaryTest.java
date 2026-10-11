package io.github.maaasu.astralRecord.shared.gui.playerinventory;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlayerInventoryOverlayModeBoundaryTest {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/3-メソッド仕様/08_3-サービス.md
     * 章・見出し: # 08_3-サービス > ## 3. 管理者インベントリ保存・反映 > ### プレイヤーインベントリオーバーレイのモード境界
     * 検証契約: 同じオーバーレイGUIを開いていてもADMINでは下段描画を拒否し、PLAYERの場合だけ描画する。
     */
    @Test
    void retainedOverlayCannotWriteAdminInventory() {
        AccountModel account = mock(AccountModel.class);
        when(account.getMode()).thenReturn(AccountMode.ADMIN);
        AstPlayer astPlayer = mock(AstPlayer.class);
        when(astPlayer.getAccount()).thenReturn(account);
        Player player = mock(Player.class);
        InventoryView view = mock(InventoryView.class);
        Inventory inventory = mock(Inventory.class);
        OverlayHolder holder = mock(OverlayHolder.class);
        when(player.getOpenInventory()).thenReturn(view);
        when(view.getTopInventory()).thenReturn(inventory);
        when(inventory.getHolder()).thenReturn(holder);
        when(holder.hasPlayerInventoryOverlay()).thenReturn(true);

        try (MockedStatic<AstPlayerCache> cache = mockStatic(AstPlayerCache.class)) {
            cache.when(() -> AstPlayerCache.get(player)).thenReturn(astPlayer);
            assertFalse(PlayerInventoryOverlaySupport.renderIfActive(player));
            verify(holder, never()).renderPlayerInventory(player);
            when(account.getMode()).thenReturn(AccountMode.PLAYER);
            assertTrue(PlayerInventoryOverlaySupport.renderIfActive(player));
            verify(holder).renderPlayerInventory(player);
        }
    }

    private interface OverlayHolder extends InventoryHolder, PlayerInventoryOverlayGuiHolder {
    }
}
