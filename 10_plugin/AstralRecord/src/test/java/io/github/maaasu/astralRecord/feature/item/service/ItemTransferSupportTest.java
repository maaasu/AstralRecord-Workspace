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
}
