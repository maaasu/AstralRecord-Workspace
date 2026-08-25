package io.github.maaasu.astralRecord.feature.item.gui;

import io.github.maaasu.astralRecord.shared.gui.hotbar.HotbarShortcutGuiHolder;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.inventory.Inventory;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

class OrbGuiHolderTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 7. 補助サービス > ### オーブ装備操作
     * 検証契約: GUIサイズに応じたInventoryを生成し、所有者UUID・session token・確認画面種別をholderへ保持してイベント世代を分離する。
     */
    @Test
    void inventoryRetainsOwnerGenerationAndScreenForEventIsolation() {
        UUID ownerId = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        OrbGuiHolder holder = new OrbGuiHolder(
            ownerId,
            token,
            OrbGuiHolder.Screen.TRANSCENDENCE_CONFIRM
        );

        Inventory inventory = holder.getInventory();

        assertEquals(OrbGuiHolder.TRANSCENDENCE_CONFIRM_SIZE, inventory.getSize());
        assertSame(holder, inventory.getHolder());
        assertEquals(ownerId, holder.ownerId());
        assertEquals(token, holder.sessionToken());
        assertEquals(OrbGuiHolder.Screen.TRANSCENDENCE_CONFIRM, holder.screen());
        assertInstanceOf(HotbarShortcutGuiHolder.class, holder);

        for (OrbGuiHolder.Screen runeScreen : new OrbGuiHolder.Screen[] {
            OrbGuiHolder.Screen.RUNE_ATTACH,
            OrbGuiHolder.Screen.RUNE_DETACH,
            OrbGuiHolder.Screen.RUNE_DETACH_SELECT
        }) {
            OrbGuiHolder runeHolder = new OrbGuiHolder(ownerId, token, runeScreen);
            assertEquals(OrbGuiHolder.RUNE_SIZE, runeHolder.getInventory().getSize());
        }
    }
}
