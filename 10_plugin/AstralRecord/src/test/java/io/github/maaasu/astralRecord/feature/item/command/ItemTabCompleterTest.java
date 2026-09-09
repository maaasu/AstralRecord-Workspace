package io.github.maaasu.astralRecord.feature.item.command;

import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ItemTabCompleterTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-コマンド.md
     * 章・見出し: # 04_3-コマンド > ## 5. item コマンド補完
     * 検証契約: get の第2引数では、IDを含めずカテゴリー表示名タグとアイテム名から候補を生成する。
     */
    @Test
    void completesGetWithCategoryTagAndPlainItemName() {
        ItemService itemService = mock(ItemService.class);
        when(itemService.getLoadedItems()).thenReturn(List.of(
            DesignTestFixtures.item("20a00001", "&bノクスソード", ItemCategory.EQUIPMENT, 1)
        ));
        CommandSender sender = mock(CommandSender.class);

        List<String> completions = new ItemTabCompleter(itemService).onTabComplete(
            sender,
            null,
            "item",
            new String[] {"get", ""}
        );

        assertEquals(List.of("[装備]ノクスソード"), completions);
    }
}
