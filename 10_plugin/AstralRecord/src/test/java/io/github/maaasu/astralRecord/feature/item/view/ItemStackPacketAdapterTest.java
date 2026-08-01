package io.github.maaasu.astralRecord.feature.item.view;

import com.comphenix.protocol.wrappers.EnumWrappers;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketContainer;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.Equippable;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

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

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-アダプタ・リスナー.md
     * 章・見出し: # 04_3-アダプタ・リスナー > ## 1. ItemStackPacketAdapter メソッド仕様 > ### ENTITY_EQUIPMENT書き換え
     * 検証契約: ProtocolLibの全装備slotをPaper APIの同じ装備位置へ対応付け、Paper API経由の受信者専用再送で手持ち・防具の表示を維持する。
     */
    @Test
    void mapsProtocolLibEquipmentSlotsToEquivalentPaperSlots() {
        assertEquals(EquipmentSlot.HAND, ItemStackPacketAdapter.toBukkitEquipmentSlot(EnumWrappers.ItemSlot.MAINHAND));
        assertEquals(EquipmentSlot.OFF_HAND, ItemStackPacketAdapter.toBukkitEquipmentSlot(EnumWrappers.ItemSlot.OFFHAND));
        assertEquals(EquipmentSlot.FEET, ItemStackPacketAdapter.toBukkitEquipmentSlot(EnumWrappers.ItemSlot.FEET));
        assertEquals(EquipmentSlot.LEGS, ItemStackPacketAdapter.toBukkitEquipmentSlot(EnumWrappers.ItemSlot.LEGS));
        assertEquals(EquipmentSlot.CHEST, ItemStackPacketAdapter.toBukkitEquipmentSlot(EnumWrappers.ItemSlot.CHEST));
        assertEquals(EquipmentSlot.HEAD, ItemStackPacketAdapter.toBukkitEquipmentSlot(EnumWrappers.ItemSlot.HEAD));
        assertEquals(EquipmentSlot.BODY, ItemStackPacketAdapter.toBukkitEquipmentSlot(EnumWrappers.ItemSlot.BODY));
        assertEquals(EquipmentSlot.SADDLE, ItemStackPacketAdapter.toBukkitEquipmentSlot(EnumWrappers.ItemSlot.SADDLE));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-アダプタ・リスナー.md
     * 章・見出し: # 04_3-アダプタ・リスナー > ## 1. ItemStackPacketAdapter メソッド仕様 > ### ENTITY_EQUIPMENT書き換え
     * 検証契約: Paper API再送は送信スレッドに依存せず、同一装備の連続更新でも一回ずつだけ通過させる。
     */
    @Test
    void equipmentOverrideRegistryConsumesEachConsecutiveResendOnlyOnce() {
        ItemStackPacketAdapter.EquipmentOverrideRegistry registry = new ItemStackPacketAdapter.EquipmentOverrideRegistry();
        UUID viewerId = UUID.randomUUID();
        Map<EquipmentSlot, ItemStack> equipment = Map.of(EquipmentSlot.CHEST, new ItemStack(Material.DIAMOND_CHESTPLATE));

        registry.mark(viewerId, 42, equipment, 1_000L);
        registry.mark(viewerId, 42, equipment, 1_001L);

        assertTrue(registry.consume(viewerId, 42, equipment, 1_002L));
        assertTrue(registry.consume(viewerId, 42, equipment, 1_003L));
        assertFalse(registry.consume(viewerId, 42, equipment, 1_004L));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-アダプタ・リスナー.md
     * 章・見出し: # 04_3-アダプタ・リスナー > ## 1. ItemStackPacketAdapter メソッド仕様 > ### ENTITY_EQUIPMENT書き換え
     * 検証契約: 未到達の再送識別は短時間で期限切れになり、viewer logout時にも破棄する。
     */
    @Test
    void equipmentOverrideRegistryExpiresAndDiscardsViewerEntries() {
        ItemStackPacketAdapter.EquipmentOverrideRegistry registry = new ItemStackPacketAdapter.EquipmentOverrideRegistry();
        UUID viewerId = UUID.randomUUID();
        Map<EquipmentSlot, ItemStack> equipment = Map.of(EquipmentSlot.HEAD, new ItemStack(Material.DIAMOND_HELMET));

        registry.mark(viewerId, 42, equipment, 1_000L);
        assertFalse(registry.consume(viewerId, 42, equipment, 6_000L));

        registry.mark(viewerId, 42, equipment, 7_000L);
        registry.discardViewer(viewerId);
        assertFalse(registry.consume(viewerId, 42, equipment, 7_001L));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-アダプタ・リスナー.md
     * 章・見出し: # 04_3-アダプタ・リスナー > ## 1. ItemStackPacketAdapter メソッド仕様 > ### ENTITY_EQUIPMENT書き換え
     * 検証契約: main threadで対象を解決できない場合、cancel済みの元パケットをlistener filterを通さず再送する。
     */
    @Test
    void sendsOriginalEquipmentPacketWithoutListenerFiltersForFallback() {
        ProtocolManager manager = mock(ProtocolManager.class);
        Player viewer = mock(Player.class);
        PacketContainer originalPacket = null;

        ItemStackPacketAdapter.sendOriginalPacketWithoutFilters(manager, viewer, originalPacket);

        verify(manager).sendServerPacket(viewer, originalPacket, false);
    }
}
