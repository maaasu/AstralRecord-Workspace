package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.loot.service.LootService;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ItemStackFactorySaleValueLoreTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/04_2-ユースケース.md
     * 章・見出し: # 04_2-ユースケース > ## 8. ItemStack の lore を生成する
     * 検証契約: 売却可能な非通貨アイテムの lore には saleValue を売値として表示する。
     */
    @Test
    void sellableItemLoreShowsSaleValue() {
        List<String> lore = createLore(false);

        assertTrue(lore.contains(" ▸ 売値: 123 ゴールド"));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/04_2-ユースケース.md
     * 章・見出し: # 04_2-ユースケース > ## 8. ItemStack の lore を生成する
     * 検証契約: 売却不可アイテムの lore には売値を表示せず、売却不可表示だけを残す。
     */
    @Test
    void unsellableItemLoreHidesSaleValue() {
        List<String> lore = createLore(true);

        assertFalse(lore.stream().anyMatch(line -> line.contains("売値:")));
        assertTrue(lore.contains("✖ 売却不可"));
    }

    private List<String> createLore(boolean unSellable) {
        ItemStackFactory factory = new ItemStackFactory(mock(LootService.class), mock(ItemService.class));
        var item = factory.create(createModel(unSellable));
        var meta = Objects.requireNonNull(item.getItemMeta());
        return Objects.requireNonNull(meta.lore()).stream()
                .map(PlainTextComponentSerializer.plainText()::serialize)
                .toList();
    }

    private ItemModel createModel(boolean unSellable) {
        return new ItemModel(
                1,
                "sale_value_lore_test",
                ItemCategory.MATERIAL.getApiValue(),
                "売値表示テスト",
                "PAPER",
                "COMMON",
                64,
                123,
                null,
                null,
                List.of(),
                false,
                unSellable,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }
}
