package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.feature.loot.service.LootService;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

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

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 5. ItemStack生成 > ### display・shop ItemStack生成
     * 検証契約: icon未設定のBUNDLEを表示用cloneへ変換した場合も、バニラ内容量を表示せず元ItemStackを変更しない。
     */
    @Test
    void displayStackWithoutIconHidesBundleContentsOnClone() {
        ItemStack source = new ItemStack(Material.BUNDLE);
        ItemStack display = new ItemStackFactory(
            mock(LootService.class),
            mock(ItemService.class)
        ).asDisplayStack(source);

        assertNotSame(source, display);
        TooltipDisplay tooltipDisplay = display.getData(DataComponentTypes.TOOLTIP_DISPLAY);
        assertNotNull(tooltipDisplay);
        assertTrue(tooltipDisplay.hiddenComponents().contains(DataComponentTypes.BUNDLE_CONTENTS));
        assertFalse(source.hasData(DataComponentTypes.TOOLTIP_DISPLAY));
    }
}
