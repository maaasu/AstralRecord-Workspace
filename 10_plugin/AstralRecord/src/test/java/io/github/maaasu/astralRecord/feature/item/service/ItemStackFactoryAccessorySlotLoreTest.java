package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipment;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentHandType;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentSlot;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.loot.service.LootService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ItemStackFactoryAccessorySlotLoreTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 5. ItemStack生成 > ### 所有インスタンスItemStack生成
     * 検証契約: アクセサリの装備 Lore に、共有タグから解決した種類別スロット名を表示し、未登録タグは内部 ID として表示しない。
     */
    @Test
    void equipmentLoreShowsTypeSpecificAccessorySlot() throws ReflectiveOperationException {
        ItemStackFactory factory = new ItemStackFactory(mock(LootService.class), mock(ItemService.class));
        Method method = ItemStackFactory.class.getDeclaredMethod(
                "buildLoreForEquipmentInstance", ItemModel.class, EquipmentInstance.class);
        method.setAccessible(true);
        Method masterMethod = ItemStackFactory.class.getDeclaredMethod(
                "buildLore", ItemModel.class);
        masterMethod.setAccessible(true);

        ItemModel amuletModel = accessoryModel("AMULET");
        List<String> amuletLore = buildLore(method, factory, amuletModel);
        assertTrue(amuletLore.stream().anyMatch(line -> line.contains("スロット:") && line.contains("アクセサリ")));
        assertTrue(amuletLore.stream().anyMatch(line -> line.contains("アクセサリ枠:") && line.contains("アミュレット")));
        @SuppressWarnings("unchecked")
        List<String> amuletMasterLore = (List<String>) masterMethod.invoke(factory, amuletModel);
        assertTrue(amuletMasterLore.stream().anyMatch(line -> line.contains("アクセサリ枠:") && line.contains("アミュレット")));

        List<String> charmLore = buildLore(method, factory, accessoryModel("CHARM"));
        assertTrue(charmLore.stream().anyMatch(line -> line.contains("アクセサリ枠:") && line.contains("チャーム")));

        List<String> unknownLore = buildLore(method, factory, accessoryModel("UNKNOWN"));
        assertFalse(unknownLore.stream().anyMatch(line -> line.contains("UNKNOWN")));
    }

    @SuppressWarnings("unchecked")
    private List<String> buildLore(Method method, ItemStackFactory factory, ItemModel model)
            throws ReflectiveOperationException {
        EquipmentInstance instance = new EquipmentInstance(
                "instance-id",
                "account-id",
                model.getId(),
                0,
                0,
                0,
                0,
                0,
                "",
                "",
                List.of(),
                List.of(),
                List.of()
        );
        return (List<String>) method.invoke(factory, model, instance);
    }

    private ItemModel accessoryModel(String tag) {
        ItemEquipment equipment = new ItemEquipment(
                ItemEquipmentSlot.ACCESSORY,
                ItemEquipmentHandType.ONE,
                tag,
                0,
                List.of(),
                null,
                List.of(),
                null,
                null,
                null,
                null,
                List.of()
        );
        return new ItemModel(
                1,
                "accessory-test",
                ItemCategory.EQUIPMENT.getApiValue(),
                "テストアクセサリ",
                "PAPER",
                "common",
                1,
                0,
                null,
                null,
                List.of(),
                false,
                false,
                null,
                null,
                equipment,
                null,
                null,
                null,
                null
        );
    }
}
