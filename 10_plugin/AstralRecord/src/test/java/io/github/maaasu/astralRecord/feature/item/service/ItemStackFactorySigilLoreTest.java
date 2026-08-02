package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.feature.item.model.ItemSigil;
import io.github.maaasu.astralRecord.feature.item.model.ItemSigilModifier;
import io.github.maaasu.astralRecord.feature.loot.service.LootService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ItemStackFactorySigilLoreTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/3-メソッド仕様/13_3-GUI・View.md
     * 章・見出し: # 13_3-GUI・View > ## 2. 合成画面
     * 検証契約: シジル単体の lore でも、装着時に得る能力を日本語名と数値で確認できる。
     */
    @Test
    void sigilItemLoreShowsItsActualModifier() throws ReflectiveOperationException {
        ItemStackFactory factory = new ItemStackFactory(mock(LootService.class), mock(ItemService.class));
        List<String> lore = new ArrayList<>();
        Method method = ItemStackFactory.class.getDeclaredMethod("appendSigilLore", List.class, ItemSigil.class);
        method.setAccessible(true);

        method.invoke(factory, lore, new ItemSigil(
            "cooldown_reduction",
            List.of(new ItemSigilModifier("COOLDOWN_REDUCTION", 5.0D))
        ));

        assertTrue(lore.stream().anyMatch(line -> line.contains("シジル効果")));
        assertTrue(lore.stream().anyMatch(line -> line.contains("クールダウン短縮") && line.contains("+5%")));
    }
}
