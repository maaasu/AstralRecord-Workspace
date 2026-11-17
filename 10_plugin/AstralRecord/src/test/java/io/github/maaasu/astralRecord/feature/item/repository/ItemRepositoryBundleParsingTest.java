package io.github.maaasu.astralRecord.feature.item.repository;

import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ItemRepositoryBundleParsingTest {

    /**
     * bundle の開封時間をAPI payloadから保持し、省略時は20 tickへ補完する。
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
