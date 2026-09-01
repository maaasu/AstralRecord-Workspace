package io.github.maaasu.astralRecord.feature.storage.view;

import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.Inventory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class StorageScreenViewTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/08_2-ユースケース.md
     * 章・見出し: # 08_2-ユースケース > ## 6. ストレージ収納・取り出し
     * 検証契約: カテゴリ候補は全カテゴリ共通のchestではなく、カテゴリをイメージできる個別アイコンを表示する。
     */
    @Test
    void categoryFilterUsesCategorySpecificIcons() {
        StorageScreenView view = new StorageScreenView(
            new NamespacedKey("astralrecord", "storage_placeholder_test"),
            new NamespacedKey("astralrecord", "storage_entry_test")
        );
        Inventory inventory = Bukkit.createInventory(null, 54);

        view.renderFilterOptions(inventory, StorageScreenView.FilterType.CATEGORY, null);

        List<Material> expected = List.of(
            Material.BARRIER,
            Material.BUNDLE,
            Material.GOLD_INGOT,
            Material.DIAMOND_CHESTPLATE,
            Material.IRON_INGOT,
            Material.END_CRYSTAL,
            Material.APPLE,
            Material.ENCHANTED_BOOK,
            Material.AMETHYST_SHARD,
            Material.FIREWORK_STAR
        );
        for (int slot = 0; slot < expected.size(); slot++) {
            assertEquals(expected.get(slot), inventory.getItem(slot).getType(), "slot=" + slot);
        }
        for (int slot = 1; slot < expected.size(); slot++) {
            assertNotEquals(Material.CHEST, inventory.getItem(slot).getType(), "slot=" + slot);
        }
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/08-inventory/08_1-モデル定義.md
     * 章・見出し: # 08_1-モデル定義 > ## 3. インベントリ種別
     * 検証契約: 拡張トークン未所持時の5ページ分を超えてストレージGUIをページングしない。
     */
    @Test
    void storagePageCountIsLimitedToConfiguredCapacity() {
        StorageScreenView view = new StorageScreenView(
            new NamespacedKey("astralrecord", "storage_placeholder_page_test"),
            new NamespacedKey("astralrecord", "storage_entry_page_test")
        );

        assertEquals(5, view.totalPages(225, 5));
        assertEquals(3, view.normalizePage(99, 180, 5));
        assertFalse(view.hasNextPage(4, 225, 5));
    }
}
