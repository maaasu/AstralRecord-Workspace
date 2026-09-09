package io.github.maaasu.astralRecord.feature.item.service;

import org.bukkit.event.inventory.ClickType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemTransferSupportTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/08_2-ユースケース.md
     * 章・見出し: # 08_2-ユースケース > ## 6. ストレージ収納・取り出し
     * 検証契約: 単一stack操作と全stack操作で数量上限に従ったtransfer量を返す。
     */
    @Test
    void resolvesConsistentTransferAmountsForSingleStackAndAllStacks() {
        assertEquals(1, ItemTransferSupport.resolveTransferAmount(ClickType.LEFT, 130, 64));
        assertEquals(65, ItemTransferSupport.resolveTransferAmount(ClickType.RIGHT, 130, 64));
        assertEquals(64, ItemTransferSupport.resolveTransferAmount(ClickType.SHIFT_LEFT, 130, 64));
        assertEquals(16, ItemTransferSupport.resolveTransferAmount(ClickType.SHIFT_LEFT, 130, 16));
        assertEquals(130, ItemTransferSupport.resolveTransferAmount(ClickType.SHIFT_RIGHT, 130, 64));
        assertEquals(0, ItemTransferSupport.resolveTransferAmount(ClickType.MIDDLE, 130, 64));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/08_2-ユースケース.md
     * 章・見出し: # 08_2-ユースケース > ## 6. ストレージ収納・取り出し
     * 検証契約: Shift+右clickだけを同一item全stack transferとして認識する。
     */
    @Test
    void recognizesOnlyShiftRightAsAllStacksTransfer() {
        assertTrue(ItemTransferSupport.isAllStacksTransfer(ClickType.SHIFT_RIGHT));
        assertFalse(ItemTransferSupport.isAllStacksTransfer(ClickType.SHIFT_LEFT));
        assertFalse(ItemTransferSupport.isAllStacksTransfer(ClickType.RIGHT));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-サービス.md
     * 章・見出し: # 09_3-サービス > ## 売却
     * 検証契約: ストレージと売却で共通のクリック解釈を使い、Shift+右クリックだけを同一 item 全体の移動として扱う。
     */
    @Test
    void resolvesReusableClickTransferRequest() {
        var left = ItemTransferSupport.resolveClickTransferRequest(ClickType.LEFT, 64);
        var right = ItemTransferSupport.resolveClickTransferRequest(ClickType.RIGHT, 64);
        var shiftLeft = ItemTransferSupport.resolveClickTransferRequest(ClickType.SHIFT_LEFT, 64);
        var shiftRight = ItemTransferSupport.resolveClickTransferRequest(ClickType.SHIFT_RIGHT, 64);

        assertEquals(1, left.requestedAmount());
        assertFalse(left.allMatching());
        assertEquals(32, right.requestedAmount());
        assertEquals(64, shiftLeft.requestedAmount());
        assertEquals(0, shiftRight.requestedAmount());
        assertTrue(shiftRight.allMatching());
        assertTrue(shiftRight.isValid());
    }
}
