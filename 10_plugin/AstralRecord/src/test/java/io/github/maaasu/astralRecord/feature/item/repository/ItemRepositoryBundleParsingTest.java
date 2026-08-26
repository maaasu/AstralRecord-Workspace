package io.github.maaasu.astralRecord.feature.item.repository;

import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ItemRepositoryBundleParsingTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/04_1-モデル定義.md
     * 章・見出し: # 04_1-モデル定義 > ## 4. カテゴリ固有定義 > ### 4.1 `ItemBundle`
     * 検証契約: bundle の開封時間をAPI payloadから保持し、省略時は20 tickへ補完する。
     */
    @Test
    void bundleOpenTimeTicksUsesConfiguredValueAndDefault() throws Exception {
        ItemModel configured = parseItem("""
            {
              "schemaVersion":1,
              "id":"drop_bundle",
              "category":"bundle",
              "name":"drop bundle",
              "icon":"CHEST",
              "rarity":"COMMON",
              "bundle":{"openTimeTicks":10}
            }
            """);
        ItemModel defaulted = parseItem("""
            {
              "schemaVersion":1,
              "id":"normal_bundle",
              "category":"bundle",
              "name":"normal bundle",
              "icon":"CHEST",
              "rarity":"COMMON",
              "bundle":{}
            }
            """);

        assertEquals(10L, configured.getBundle().getOpenTimeTicks());
        assertEquals(20L, defaulted.getBundle().getOpenTimeTicks());
    }

    private ItemModel parseItem(String json) throws Exception {
        ItemRepository repository = new ItemRepository();
        Method parser = ItemRepository.class.getDeclaredMethod("parseItem", String.class);
        parser.setAccessible(true);
        return (ItemModel) parser.invoke(repository, json);
    }
}
