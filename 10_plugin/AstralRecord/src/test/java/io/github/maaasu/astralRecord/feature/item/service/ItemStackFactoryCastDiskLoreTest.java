package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipment;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentDurability;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentHandType;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentSlot;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.loot.service.LootService;
import io.github.maaasu.astralRecord.shared.masterdata.tag.MasterTagIds;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ItemStackFactoryCastDiskLoreTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 5. ItemStack生成 > ### 所有インスタンスItemStack生成
     * 検証契約: スキルキャストディスクの個体Loreは、metadataに保存された現在のスキル枠と武器枠を表示する。
     */
    @Test
    void equipmentLoreShowsConfiguredCastDiskSlots() {
        ItemModel model = skillCastDiskModel();
        EquipmentInstance instance = instance(model);
        ItemStackFactory factory = new ItemStackFactory(mock(LootService.class), mock(ItemService.class));

        List<String> lore = plainLore(factory.create(
            model,
            instance,
            1,
            "{\"castDisk\":{\"actionSlot\":1,\"weaponHotbarSlot\":3}}"
        ));

        assertTrue(lore.stream().anyMatch(line -> line.contains("スキル枠: 2")));
        assertTrue(lore.stream().anyMatch(line -> line.contains("武器枠: 4")));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 5. ItemStack生成 > ### 所有インスタンスItemStack生成
     * 検証契約: 未設定または不正なmetadataのスキルキャストディスクは、現在設定を未設定として表示する。
     */
    @Test
    void equipmentLoreShowsUnsetCastDiskSlotsWhenMetadataIsAbsentOrInvalid() {
        ItemModel model = skillCastDiskModel();
        ItemStackFactory factory = new ItemStackFactory(mock(LootService.class), mock(ItemService.class));

        assertUnsetCastDiskSlots(plainLore(factory.create(model, instance(model), 1, null)));
        assertUnsetCastDiskSlots(plainLore(factory.create(
            model,
            instance(model),
            1,
            "{\"castDisk\":{\"actionSlot\":6,\"weaponHotbarSlot\":9}}"
        )));
        assertUnsetCastDiskSlots(plainLore(factory.create(model, instance(model), 1, "not-json")));
    }

    private void assertUnsetCastDiskSlots(List<String> lore) {
        assertTrue(lore.stream().anyMatch(line -> line.contains("スキル枠: 未設定")));
        assertTrue(lore.stream().anyMatch(line -> line.contains("武器枠: 未設定")));
    }

    private List<String> plainLore(ItemStack itemStack) {
        ItemMeta meta = itemStack.getItemMeta();
        return meta.lore().stream()
            .map(PlainTextComponentSerializer.plainText()::serialize)
            .toList();
    }

    private ItemModel skillCastDiskModel() {
        ItemEquipment equipment = new ItemEquipment(
            ItemEquipmentSlot.TOOL,
            ItemEquipmentHandType.ONE,
            MasterTagIds.Equipment.SKILL_CAST_DISK,
            0,
            List.of(),
            null,
            List.of(),
            new ItemEquipmentDurability(200, 1),
            null,
            null,
            null,
            List.of()
        );
        return new ItemModel(
            1,
            "cast-disk-lore-test",
            ItemCategory.EQUIPMENT.getApiValue(),
            "スキルキャストディスク",
            "MUSIC_DISC_11",
            "UNCOMMON",
            1,
            0,
            null,
            null,
            List.of("説明"),
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

    private EquipmentInstance instance(ItemModel model) {
        return new EquipmentInstance(
            "cast-disk-instance",
            "account-id",
            model.getId(),
            0,
            0,
            0,
            200,
            200,
            "",
            "",
            List.of(),
            List.of(),
            List.of()
        );
    }
}
