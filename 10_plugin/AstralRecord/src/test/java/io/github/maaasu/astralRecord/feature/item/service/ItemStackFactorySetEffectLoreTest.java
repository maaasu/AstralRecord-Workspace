package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipment;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentDurability;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentHandType;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentSlot;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentStat;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentStatType;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.model.SetEffect;
import io.github.maaasu.astralRecord.feature.item.model.SetEffectPiece;
import io.github.maaasu.astralRecord.feature.item.model.SetEffectStat;
import io.github.maaasu.astralRecord.feature.loot.service.LootService;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ItemStackFactorySetEffectLoreTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 5. ItemStack生成
     * 検証契約: equipment のマスタ ItemStack と所有インスタンス ItemStack の双方へセット名、発動必要数、効果値を表示する。
     */
    @Test
    void equipmentLoreShowsSetEffectForMasterAndInstance() {
        ItemService itemService = mock(ItemService.class);
        when(itemService.findSetEffectById("debug_armor_set")).thenReturn(setEffect());
        ItemStackFactory factory = new ItemStackFactory(mock(LootService.class), itemService);
        ItemModel model = equipmentModel();

        assertSetEffectLore(factory.create(model));

        String now = LocalDateTime.now().toString();
        EquipmentInstance instance = new EquipmentInstance(
                "debug-armor-instance",
                "debug-account",
                model.getId(),
                0,
                0,
                0,
                100,
                100,
                now,
                now,
                List.of(),
                List.of(),
                List.of()
        );
        assertSetEffectLore(factory.create(model, instance, 1));
    }

    /**
     * 装備中コンテキストでは発動状態記号を付け、未発動効果を灰色で表示する。
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-サービス.md
     * 章・見出し: # 04_3-サービス > ## 5. ItemStack生成
     * 検証契約: 装備中表示だけセット効果の発動状態を可変表示し、未発動行を灰色で表示する。
     */
    @Test
    void equippedEquipmentLoreShowsActiveAndInactiveSetEffects() {
        ItemService itemService = mock(ItemService.class);
        when(itemService.findSetEffectById("debug_armor_set")).thenReturn(setEffect());
        ItemStackFactory factory = new ItemStackFactory(mock(LootService.class), itemService);
        ItemModel model = equipmentModel();
        String now = LocalDateTime.now().toString();
        EquipmentInstance instance = new EquipmentInstance(
                "debug-armor-instance", "debug-account", model.getId(), 0, 0, 0,
                100, 100, now, now, List.of(), List.of(), List.of());

        ItemStack item = factory.create(model, instance, 1, null, Map.of("debug_armor_set", 2));

        ItemMeta meta = item.getItemMeta();
        assertNotNull(meta);
        List<String> lore = meta.lore().stream()
                .map(LegacyComponentSerializer.legacySection()::serialize)
                .toList();
        assertTrue(lore.stream().anyMatch(line ->
                line.contains("2セット効果") && line.contains("§a+")));
        assertTrue(lore.stream().anyMatch(line -> line.contains("4セット効果 -")));
        String inactiveEffect = lore.stream()
                .filter(line -> line.contains("+20"))
                .findFirst()
                .orElseThrow();
        assertTrue(inactiveEffect.startsWith("§7"));
    }

    private void assertSetEffectLore(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        assertNotNull(meta);
        assertNotNull(meta.lore());
        assertTrue(meta.lore().stream().anyMatch(line -> line.toString().contains("デバッグ防具セット")));
        assertTrue(meta.lore().stream().anyMatch(line -> line.toString().contains("2セット効果")));
        assertTrue(meta.lore().stream().anyMatch(line -> line.toString().contains("4セット効果")));
        assertTrue(meta.lore().stream().anyMatch(line -> line.toString().contains("防御力")));
    }

    private ItemModel equipmentModel() {
        ItemEquipment equipment = new ItemEquipment(
                ItemEquipmentSlot.HEAD,
                ItemEquipmentHandType.ONE,
                null,
                0,
                List.of(),
                "debug_armor_set",
                List.of(new ItemEquipmentStat("DEFENSE", ItemEquipmentStatType.FLAT, 1.0D, 1.0D)),
                new ItemEquipmentDurability(100, 1),
                null,
                null,
                null,
                List.of()
        );
        return new ItemModel(
                1,
                "debug_armor_helmet",
                ItemCategory.EQUIPMENT.getApiValue(),
                "デバッグヘルム",
                "LEATHER_HELMET",
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
                null,
                null
        );
    }

    private SetEffect setEffect() {
        return new SetEffect(
                "debug_armor_set",
                "&dデバッグ防具セット",
                List.of(
                        new SetEffectPiece(
                                2,
                                List.of(new SetEffectStat("DEFENSE", ItemEquipmentStatType.FLAT, "4"))
                        ),
                        new SetEffectPiece(
                                4,
                                List.of(new SetEffectStat("MAX_HEALTH", ItemEquipmentStatType.FLAT, "20"))
                        )
                )
        );
    }
}
