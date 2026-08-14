package io.github.maaasu.astralRecord.feature.item.service;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemStackFactoryBundleTooltipTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 5. ItemStack生成 > ### display・shop ItemStack生成
     * 検証契約: bundle アイコンの表示用 ItemStack は、バニラの内容量表示を出さずにプラグイン定義の表示だけを維持する。
     */
    @Test
    void bundleContentsTooltipIsHiddenIdempotently() {
        ItemStack item = new ItemStack(Material.BUNDLE);

        assertTrue(ItemStackFactory.hideBundleContentsTooltip(item));

        TooltipDisplay tooltipDisplay = item.getData(DataComponentTypes.TOOLTIP_DISPLAY);
        assertNotNull(tooltipDisplay);
        assertTrue(tooltipDisplay.hiddenComponents().contains(DataComponentTypes.BUNDLE_CONTENTS));
        assertFalse(ItemStackFactory.hideBundleContentsTooltip(item));
    }
}
