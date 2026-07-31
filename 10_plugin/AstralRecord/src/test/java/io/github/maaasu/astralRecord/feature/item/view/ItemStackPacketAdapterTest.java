package io.github.maaasu.astralRecord.feature.item.view;

import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.Equippable;
import org.bukkit.Material;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemStackPacketAdapterTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-アダプタ・リスナー.md
     * 章・見出し: # 04_3-アダプタ・リスナー > ## 1. ItemStackPacketAdapter メソッド仕様 > ### アイコン書き換え判定
     * 検証契約: ARMOR_DISPLAY=false時にarmor ItemStack自体を保ちEQUIPPABLE componentだけを外す。
     */
    @Test
    void removeEquippableComponentKeepsArmorItemButDisablesEquipmentLayer() {
        ItemStack armor = new ItemStack(Material.IRON_CHESTPLATE);
        armor.setData(DataComponentTypes.EQUIPPABLE, Equippable.equippable(EquipmentSlot.CHEST));

        assertTrue(armor.hasData(DataComponentTypes.EQUIPPABLE));
        assertTrue(ItemStackPacketAdapter.removeEquippableComponent(armor));
        assertFalse(armor.hasData(DataComponentTypes.EQUIPPABLE));
        assertEquals(Material.IRON_CHESTPLATE, armor.getType());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-アダプタ・リスナー.md
     * 章・見出し: # 04_3-アダプタ・リスナー > ## 1. ItemStackPacketAdapter メソッド仕様 > ### アイコン書き換え判定
     * 検証契約: EQUIPPABLEを持たない通常itemを変更しない。
     */
    @Test
    void removeEquippableComponentDoesNotModifyOrdinaryItem() {
        ItemStack paper = new ItemStack(Material.PAPER);

        assertFalse(ItemStackPacketAdapter.removeEquippableComponent(paper));
        assertEquals(Material.PAPER, paper.getType());
    }
}
