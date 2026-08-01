package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemStackFactoryDurabilityLoreTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 5. ItemStack生成 > ### 耐久表示反映
     * 検証契約: 耐久率に応じて充填バーを濃い緑、緑、黄色、赤へ段階着色し、未充填部分は灰色にする。
     */
    @Test
    void durabilityBarUsesFourColorsAtDocumentedThresholds() {
        assertFilledColor(100, 100, ColorCodeUtil.DARK_GREEN);
        assertFilledColor(75, 100, ColorCodeUtil.DARK_GREEN);
        assertFilledColor(50, 100, ColorCodeUtil.GREEN);
        assertFilledColor(49, 100, ColorCodeUtil.YELLOW);
        assertFilledColor(25, 100, ColorCodeUtil.YELLOW);
        assertFilledColor(24, 100, ColorCodeUtil.RED);
        assertFilledColor(0, 100, ColorCodeUtil.RED);
    }

    private void assertFilledColor(int value, int max, String expectedColor) {
        String lore = ItemStackFactory.formatDurabilityBarLore(value, max);

        assertTrue(lore.startsWith(ColorCodeUtil.GRAY + " ▸ 耐久値: " + expectedColor));
        assertTrue(lore.endsWith(ColorCodeUtil.GRAY + "|".repeat(20 - (int) Math.round(
                Math.clamp((double) value / max, 0.0D, 1.0D) * 20))));
    }
}
