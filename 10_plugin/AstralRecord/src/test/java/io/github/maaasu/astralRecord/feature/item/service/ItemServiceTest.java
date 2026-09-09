package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.repository.ItemRepository;
import io.github.maaasu.astralRecord.feature.item.repository.SetEffectRepository;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ItemServiceTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-コマンド.md
     * 章・見出し: # 04_3-コマンド > ## 4. ロード済みアイテム付与
     * 検証契約: GETのアイテム指定は、ロード済みキャッシュからIDまたはカラーコードを除いた表示名で同一アイテムを解決する。
     */
    @Test
    void resolvesLoadedItemByIdOrPlainDisplayName() {
        ItemRepository itemRepository = mock(ItemRepository.class);
        ItemModel model = DesignTestFixtures.item(
            "20a00001",
            "&bノクスソード",
            ItemCategory.EQUIPMENT,
            1
        );
        when(itemRepository.findById(model.getId(), model.getCategory())).thenReturn(model);
        ItemService itemService = new ItemService(itemRepository, mock(SetEffectRepository.class));

        itemService.loadItem(model.getId(), model.getCategory());

        assertSame(model, itemService.findLoadedByIdOrName(model.getId()));
        assertSame(model, itemService.findLoadedByIdOrName("ノクスソード"));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-コマンド.md
     * 章・見出し: # 04_3-コマンド > ## 5. item コマンド補完
     * 検証契約: 同名アイテムが別カテゴリーに存在しても、カテゴリータグ付き検索は指定カテゴリーのアイテムを返す。
     */
    @Test
    void resolvesSameNameFromRequestedCategory() {
        ItemRepository itemRepository = mock(ItemRepository.class);
        ItemModel equipment = DesignTestFixtures.item(
            "20a00001",
            "&f同名アイテム",
            ItemCategory.EQUIPMENT,
            1
        );
        ItemModel material = DesignTestFixtures.item(
            "10a00001",
            "&f同名アイテム",
            ItemCategory.MATERIAL,
            64
        );
        when(itemRepository.findById(equipment.getId(), equipment.getCategory())).thenReturn(equipment);
        when(itemRepository.findById(material.getId(), material.getCategory())).thenReturn(material);
        ItemService itemService = new ItemService(itemRepository, mock(SetEffectRepository.class));

        itemService.loadItem(equipment.getId(), equipment.getCategory());
        itemService.loadItem(material.getId(), material.getCategory());

        assertSame(
            equipment,
            itemService.findLoadedByCategoryAndName(ItemCategory.EQUIPMENT.getApiValue(), "同名アイテム")
        );
        assertSame(
            material,
            itemService.findLoadedByCategoryAndName(ItemCategory.MATERIAL.getApiValue(), "同名アイテム")
        );
    }
}
