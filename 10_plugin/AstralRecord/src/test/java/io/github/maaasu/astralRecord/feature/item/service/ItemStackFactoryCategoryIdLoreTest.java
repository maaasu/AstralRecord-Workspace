package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipment;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentHandType;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentSlot;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.loot.service.LootService;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ItemStackFactoryCategoryIdLoreTest {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/04_2-ユースケース.md
     * 章・見出し: # 04_2-ユースケース > ## 8. ItemStack の lore を生成する
     * 検証契約: マスタ ItemStack と所有装備 ItemStack のカテゴリ表示の右に、区切り文字と item ID を追加し、IDを濃い灰色で表示する。
     */
    @Test
    void masterAndEquipmentInstanceLoreShowCategoryAndIdInDarkGray()
            throws ReflectiveOperationException {
        ItemStackFactory factory = new ItemStackFactory(mock(LootService.class), mock(ItemService.class));

        ItemModel material = model(
                "material-lore-test",
                ItemCategory.MATERIAL.getApiValue(),
                null);
        assertCategoryAndId(invokeMasterLore(factory, material), "素材", material.getId());

        ItemEquipment equipment = new ItemEquipment(
                ItemEquipmentSlot.WEAPON,
                ItemEquipmentHandType.ONE,
                null,
                0,
                List.of(),
                null,
                List.of(),
                null,
                null,
                null,
                null,
                List.of());
        ItemModel equipmentModel = model(
                "equipment-lore-test",
                ItemCategory.EQUIPMENT.getApiValue(),
                equipment);
        EquipmentInstance instance = new EquipmentInstance(
                "instance-id",
                "account-id",
                equipmentModel.getId(),
                0,
                0,
                0,
                0,
                0,
                "",
                "",
                List.of(),
                List.of(),
                List.of());

        assertCategoryAndId(invokeEquipmentInstanceLore(factory, equipmentModel, instance), "装備", equipmentModel.getId());
    }

    @SuppressWarnings("unchecked")
    private List<String> invokeMasterLore(ItemStackFactory factory, ItemModel model)
            throws ReflectiveOperationException {
        Method method = ItemStackFactory.class.getDeclaredMethod("buildLore", ItemModel.class);
        method.setAccessible(true);
        return (List<String>) method.invoke(factory, model);
    }

    @SuppressWarnings("unchecked")
    private List<String> invokeEquipmentInstanceLore(
            ItemStackFactory factory,
            ItemModel model,
            EquipmentInstance instance
    ) throws ReflectiveOperationException {
        Method method = ItemStackFactory.class.getDeclaredMethod(
                "buildLoreForEquipmentInstance", ItemModel.class, EquipmentInstance.class);
        method.setAccessible(true);
        return (List<String>) method.invoke(factory, model, instance);
    }

    private void assertCategoryAndId(List<String> lore, String category, String itemId) {
        assertTrue(lore.stream().map(ColorCodeUtil::stripColor)
                .anyMatch(line -> line.contains(category + " | " + itemId)));
        assertTrue(lore.stream().anyMatch(line -> line.contains(
                ColorCodeUtil.DARK_GRAY + " | " + itemId)));
    }

    private ItemModel model(String id, String category, ItemEquipment equipment) {
        return new ItemModel(
                1,
                id,
                category,
                "Lore表示テスト",
                "PAPER",
                "COMMON",
                64,
                1,
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
                null);
    }
}
