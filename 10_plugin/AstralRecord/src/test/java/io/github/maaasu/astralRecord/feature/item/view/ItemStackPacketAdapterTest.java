package io.github.maaasu.astralRecord.feature.item.view;

import com.comphenix.protocol.wrappers.EnumWrappers;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketContainer;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipment;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentDurability;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentHandType;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentSlot;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.item.service.ItemStackFactory;
import io.github.maaasu.astralRecord.feature.playersetting.service.PlayerSettingService;
import io.github.maaasu.astralRecord.feature.skill.service.SkillActionRingService;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import io.github.maaasu.astralRecord.shared.masterdata.tag.MasterTagIds;
import io.github.maaasu.astralRecord.feature.loot.service.LootService;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.Equippable;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.CrossbowMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-アダプタ・リスナー.md
     * 章・見出し: # 04_3-アダプタ・リスナー > ## 1. ItemStackPacketAdapter メソッド仕様 > ### アイコン書き換え判定
     * 検証契約: 長押し選択設定が有効で選択中 hotbar slot の場合だけ、選択中 hotbar slot の武器を仮想トライデント化する。
     */
    @Test
    void virtualTridentRequiresEnabledSettingAndSelectedHotbarSlot() {
        assertTrue(ItemStackPacketAdapter.shouldVirtualizeHotbarWeapon(true, 2, 2));
        assertFalse(ItemStackPacketAdapter.shouldVirtualizeHotbarWeapon(true, 1, 2));
        assertFalse(ItemStackPacketAdapter.shouldVirtualizeHotbarWeapon(false, 2, 2));
        assertFalse(ItemStackPacketAdapter.shouldVirtualizeHotbarWeapon(true, -1, 2));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-アダプタ・リスナー.md
     * 章・見出し: # 04_3-アダプタ・リスナー > ## 1. ItemStackPacketAdapter メソッド仕様 > ### ENTITY_EQUIPMENT書き換え
     * 検証契約: ENTITY_EQUIPMENTでは本人のメインハンドだけが選択中 hotbar slot の仮想トライデント対象になる。
     */
    @Test
    void entityEquipmentVirtualTridentRequiresViewerPacketAndSelectedHotbarSlot() {
        assertTrue(ItemStackPacketAdapter.shouldVirtualizeSelectedMainHand(true, 2, true));
        assertFalse(ItemStackPacketAdapter.shouldVirtualizeSelectedMainHand(true, 2, false));
        assertFalse(ItemStackPacketAdapter.shouldVirtualizeSelectedMainHand(false, 2, true));
        assertFalse(ItemStackPacketAdapter.shouldVirtualizeSelectedMainHand(true, -1, true));
        assertFalse(ItemStackPacketAdapter.shouldVirtualizeSelectedMainHand(true, 9, true));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-アダプタ・リスナー.md
     * 章・見出し: # 04_3-アダプタ・リスナー > ## 1. ItemStackPacketAdapter メソッド仕様 > ### アイコン書き換え判定
     * 検証契約: 長押し選択設定時の主武器だけは送信コピーをTRIDENTへ変換し、サーバー側のPaper ItemStackを変更しない。
     */
    @Test
    void virtualTridentKeepsServerPaperWeaponUntouched() throws ReflectiveOperationException {
        ItemStack serverWeapon = astralWeapon("sword_test", "instance_test");
        ItemStackPacketAdapter adapter = new ItemStackPacketAdapter(
            mock(Plugin.class), mock(PlayerSettingService.class), mock(SkillActionRingService.class)
        );
        Method replaceIcon = ItemStackPacketAdapter.class.getDeclaredMethod(
            "replaceIcon", ItemStack.class, boolean.class, boolean.class
        );
        replaceIcon.setAccessible(true);

        ItemStack clientWeapon = (ItemStack) replaceIcon.invoke(adapter, serverWeapon, true, true);

        assertEquals(Material.PAPER, serverWeapon.getType());
        assertEquals(Material.TRIDENT, clientWeapon.getType());
        assertTrue(ItemStackFactory.isWeapon(serverWeapon));
        assertEquals("sword_test", ItemStackFactory.getAstralItemId(clientWeapon));
        assertEquals("instance_test", ItemStackFactory.getEquipmentInstanceId(clientWeapon));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-アダプタ・リスナー.md
     * 章・見出し: # 04_3-アダプタ・リスナー > ## 1. ItemStackPacketAdapter メソッド仕様 > ### アイコン書き換え判定
     * 検証契約: 装填済みフックショットはLoreへ状態を表示し、送信コピーだけをバニラのチャージ済みクロスボウ表示へ変換する。
     */
    @Test
    void loadedHookshotUsesChargedCrossbowIconAndLoadedLore() throws ReflectiveOperationException {
        ItemStack serverHookshot = new ItemStackFactory(
            mock(LootService.class),
            mock(ItemService.class)
        ).create(
            hookshotModel(),
            hookshotInstance(),
            1,
            "{\"hookshot\":{\"loaded\":true}}"
        );
        ItemMeta serverMeta = serverHookshot.getItemMeta();
        assertTrue(ItemStackFactory.isHookshotLoaded(serverHookshot));
        assertTrue(serverMeta != null && serverMeta.lore() != null
            && serverMeta.lore().stream().anyMatch(line -> line.toString().contains("フック装填済み")));

        ItemStackPacketAdapter adapter = new ItemStackPacketAdapter(
            mock(Plugin.class), mock(PlayerSettingService.class), mock(SkillActionRingService.class)
        );
        Method replaceIcon = ItemStackPacketAdapter.class.getDeclaredMethod(
            "replaceIcon", ItemStack.class, boolean.class, boolean.class
        );
        replaceIcon.setAccessible(true);

        ItemStack clientHookshot = (ItemStack) replaceIcon.invoke(adapter, serverHookshot, true, false);

        assertEquals(Material.PAPER, serverHookshot.getType());
        assertEquals(Material.CROSSBOW, clientHookshot.getType());
        assertTrue(clientHookshot.getItemMeta() instanceof CrossbowMeta);
        assertTrue(((CrossbowMeta) clientHookshot.getItemMeta()).hasChargedProjectiles());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-アダプタ・リスナー.md
     * 章・見出し: # 04_3-アダプタ・リスナー > ## 1. ItemStackPacketAdapter メソッド仕様 > ### アイコン書き換え判定
     * 検証契約: 独自表示情報を持たない純粋なBUNDLEは、送信コピーへ変換せずバニラ内容量表示を維持する。
     */
    @Test
    void rawBundleIsSkippedByPacketDisplay() throws ReflectiveOperationException {
        ItemStack serverBundle = new ItemStack(Material.BUNDLE);
        ItemStackPacketAdapter adapter = new ItemStackPacketAdapter(
            mock(Plugin.class), mock(PlayerSettingService.class), mock(SkillActionRingService.class)
        );
        Method replaceIcon = ItemStackPacketAdapter.class.getDeclaredMethod(
            "replaceIcon", ItemStack.class, boolean.class, boolean.class
        );
        replaceIcon.setAccessible(true);

        ItemStack clientBundle = (ItemStack) replaceIcon.invoke(adapter, serverBundle, true, false);

        assertNull(clientBundle);
        assertFalse(serverBundle.hasData(DataComponentTypes.TOOLTIP_DISPLAY));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-アダプタ・リスナー.md
     * 章・見出し: # 04_3-アダプタ・リスナー > ## 1. ItemStackPacketAdapter メソッド仕様 > ### パケットアダプタ登録
      * 検証契約: GUIセッション終了後の再同期はプレイヤーインベントリだけが表示中の場合に限り、別GUI表示中は実行しない。
      */
    @Test
    void equipmentRefreshRequiresPlayerInventoryOnlyView() {
        Player player = mock(Player.class);
        InventoryView view = mock(InventoryView.class);
        Inventory topInventory = mock(Inventory.class);
        when(player.getOpenInventory()).thenReturn(view);
        when(view.getTopInventory()).thenReturn(topInventory);
        when(view.getType()).thenReturn(InventoryType.CRAFTING);

        assertTrue(ItemStackPacketAdapter.isPlayerInventoryOnlyOpen(player));

        when(view.getType()).thenReturn(InventoryType.CHEST);
        assertFalse(ItemStackPacketAdapter.isPlayerInventoryOnlyOpen(player));

        when(view.getType()).thenReturn(InventoryType.CRAFTING);
        InventoryHolder pluginGuiHolder = () -> topInventory;
        when(topInventory.getHolder()).thenReturn(pluginGuiHolder);
        assertFalse(ItemStackPacketAdapter.isPlayerInventoryOnlyOpen(player));
    }

    private ItemStack astralWeapon(String itemId, String instanceId) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        var pdc = meta.getPersistentDataContainer();
        pdc.set(new NamespacedKey("astralrecord", "item_id"), PersistentDataType.STRING, itemId);
        pdc.set(new NamespacedKey("astralrecord", "equipment_slot"), PersistentDataType.STRING, "WEAPON");
        pdc.set(new NamespacedKey("astralrecord", "equipment_instance_id"), PersistentDataType.STRING, instanceId);
        item.setItemMeta(meta);
        return item;
    }

    private ItemModel hookshotModel() {
        ItemEquipment equipment = new ItemEquipment(
            ItemEquipmentSlot.TOOL,
            ItemEquipmentHandType.ONE,
            MasterTagIds.Equipment.HOOKSHOT,
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
            "hookshot",
            ItemCategory.EQUIPMENT.getApiValue(),
            "フックショット",
            "CROSSBOW",
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

    private EquipmentInstance hookshotInstance() {
        return new EquipmentInstance(
            "hookshot-instance",
            "account-id",
            "hookshot",
            0,
            0,
            0,
            200,
            200,
            "2026-08-15T00:00:00Z",
            "2026-08-15T00:00:00Z",
            List.of(),
            List.of(),
            List.of()
        );
    }
}
