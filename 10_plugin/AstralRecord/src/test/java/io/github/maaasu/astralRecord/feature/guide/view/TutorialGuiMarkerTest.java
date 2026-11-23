package io.github.maaasu.astralRecord.feature.guide.view;

import io.github.maaasu.astralRecord.feature.item.service.ItemStackFactory;
import io.github.maaasu.astralRecord.feature.mail.gui.MailGuiView;
import io.github.maaasu.astralRecord.feature.shop.gui.ShopGui;
import io.github.maaasu.astralRecord.feature.world.gui.OverworldTeleportGui;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.github.maaasu.astralRecord.feature.mail.model.MailEntry;
import io.github.maaasu.astralRecord.feature.mail.model.MailFilter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TutorialGuiMarkerTest {
    private static final NamespacedKey MAIL_KEY = new NamespacedKey("tutorial_test", "mail_id");
    private static final NamespacedKey SHOP_KEY = new NamespacedKey("tutorial_test", "shop_entry_id");
    private static final UUID PLAYER = UUID.randomUUID();

    @Test
    void bundleSelectsOnlyMainStorageOrHotbarInDefaultInventory() {
        InventoryView crafting = mock(InventoryView.class);
        PlayerInventory bottom = mock(PlayerInventory.class);
        CraftingInventory grid = mock(CraftingInventory.class);
        when(grid.getSize()).thenReturn(5);
        when(crafting.getTopInventory()).thenReturn(grid);
        when(crafting.countSlots()).thenReturn(46);
        when(crafting.getBottomInventory()).thenReturn(bottom);
        when(crafting.getInventory(44)).thenReturn(bottom);
        when(crafting.convertSlot(44)).thenReturn(8);

        ItemStack bundle = item(Material.PAPER);
        for (int excluded : List.of(0, 4, 5, 8, 45)) {
            when(crafting.getItem(excluded)).thenReturn(bundle);
        }

        try (var items = mockStatic(ItemStackFactory.class)) {
            items.when(() -> ItemStackFactory.getAstralItemId(bundle)).thenReturn("50a00001");
            assertNull(select(crafting, null, "open_initial_bundle"));

            when(crafting.getInventory(9)).thenReturn(bottom);
            when(crafting.convertSlot(9)).thenReturn(9);
            when(crafting.getItem(9)).thenReturn(bundle);
            assertTrue(select(crafting, null, "open_initial_bundle").targetsWindowSlot(0, 9));

            // 割当後もバッグに正本がある状態から、使用できるホットバーの枠へ誘導が移る。
            when(crafting.getItem(44)).thenReturn(bundle);
            var mark = select(crafting, 97, "open_initial_bundle");
            assertNotNull(mark);
            assertTrue(mark.targetsWindowSlot(0, 44));
            assertTrue(mark.targetsPlayerSlot(8));

            InventoryView chest = chest(mock(InventoryHolder.class));
            when(chest.getItem(54)).thenReturn(bundle);
            assertNull(select(chest, 97, "open_initial_bundle"));
        }
    }

    @Test
    void welcomeMailUsesTheRenderedPageAndDisappearsAfterReading() {
        MailEntry welcome = mail("welcome_mail", false);
        MailEntry other = mail("other_mail", false);
        List<MailEntry> mails = new ArrayList<>(Collections.nCopies(89, other));
        mails.add(welcome);
        InventoryView view = chest(new MailGuiView.Holder(0, MailFilter.ALL, mails));
        ItemStack welcomeItem = taggedItem(Material.PAPER, MAIL_KEY, "welcome_mail");
        ItemStack otherItem = taggedItem(Material.PAPER, MAIL_KEY, "other_mail");
        when(view.getItem(4)).thenReturn(otherItem);
        when(view.getItem(45)).thenReturn(welcomeItem);
        assertNull(select(view, 3, "receive_welcome_mail"));
        ItemStack next = item(Material.ARROW);
        when(view.getItem(MailGuiView.NEXT_SLOT)).thenReturn(next);
        assertEquals(MailGuiView.NEXT_SLOT, select(view, 3, "receive_welcome_mail").rawSlot());

        view = chest(new MailGuiView.Holder(1, MailFilter.ALL, mails));
        when(view.getItem(44)).thenReturn(welcomeItem);
        var mark = select(view, 4, "receive_welcome_mail");
        assertNotNull(mark);
        assertTrue(mark.targetsWindowSlot(4, 44));
        assertFalse(mark.targetsWindowSlot(3, 44));
        assertFalse(mark.targetsPlayerSlot(8));
        assertNull(select(view, null, "receive_welcome_mail"));

        ItemStack welcomeCopy = copyable(welcomeItem);
        ItemStack otherCopy = copyable(otherItem);
        ItemMeta otherMeta = otherItem.getItemMeta();
        when(otherMeta.hasEnchantmentGlintOverride()).thenReturn(true);
        when(otherMeta.getEnchantmentGlintOverride()).thenReturn(true);
        List<ItemStack> items = new ArrayList<>(Collections.nCopies(46, null));
        items.set(4, otherItem);
        items.set(44, welcomeItem);
        items.set(45, otherItem);

        var decorated = TutorialGuiMarker.decorateWindowItems(mark, 4, items);
        assertNotNull(decorated);
        assertSame(otherItem, items.get(4));
        assertSame(otherCopy, decorated.get(4));
        assertSame(welcomeCopy, decorated.get(44));
        assertSame(otherItem, decorated.get(45));
        assertNull(TutorialGuiMarker.decorateWindowItems(mark, 3, items));
        assertNull(select(chest(mock(InventoryHolder.class)), 4, "receive_welcome_mail"));
        assertSame(otherCopy, TutorialGuiMarker.decorateWindowSlot(mark, 4, 4, otherItem));
        assertSame(otherItem, TutorialGuiMarker.decorateWindowSlot(mark, 3, 4, otherItem));
        assertSame(otherItem, TutorialGuiMarker.decorateWindowSlot(mark, 4, 45, otherItem));
        assertSame(otherItem, TutorialGuiMarker.decorateWindowSlot(mark, 4, -1, otherItem));
        verify(otherCopy.getItemMeta(), times(2)).setEnchantmentGlintOverride(false);
        verify(otherMeta, never()).setEnchantmentGlintOverride(any());
        verify(otherItem, never()).setItemMeta(any());

        mails.set(89, mail("welcome_mail", true));
        assertNull(select(view, 4, "receive_welcome_mail"));
    }

    @Test
    void teleportMarksOnlyTheTargetWorldSlotOnTheRenderedPage() {
        var view = chest(new OverworldTeleportGui.Holder(Map.of(14, "midgard_greenfields", 11, "other_world")));
        doReturn(item(Material.GRASS_BLOCK)).when(view).getItem(11);
        doReturn(item(Material.GRASS_BLOCK)).when(view).getItem(14);
        var mark = select(view, 9, "defeat_grassboar");
        assertNotNull(mark);
        assertTrue(mark.targetsWindowSlot(9, 14));
        assertFalse(mark.targetsWindowSlot(9, 11));
        assertNull(select(view, 9, "receive_welcome_mail"));
        var otherPage = chest(new OverworldTeleportGui.Holder(Map.of(14, "other_world")));
        doReturn(item(Material.GRASS_BLOCK)).when(otherPage).getItem(14);
        assertNull(select(otherPage, 10, "defeat_grassboar"));
    }

    @Test
    void shopSelectionChecksTheShopEntryAndTheActualPurchaseButton() {
        var list = chest(new ShopGui.ListHolder("general_goods_shop", 2));
        doReturn(taggedItem(Material.PAPER, SHOP_KEY, "another_entry")).when(list).getItem(0);
        doReturn(taggedItem(Material.IRON_SWORD, SHOP_KEY, "nox_weapon")).when(list).getItem(18);

        var listMark = select(list, 5, "buy_starter_weapon");
        assertNotNull(listMark);
        assertEquals(18, listMark.rawSlot());

        var differentShop = chest(new ShopGui.ListHolder("another_shop", 2));
        doReturn(taggedItem(Material.IRON_SWORD, SHOP_KEY, "nox_weapon")).when(differentShop).getItem(18);
        assertNull(select(differentShop, 5, "buy_starter_weapon"));

        var confirm = chest(new ShopGui.ConfirmHolder("general_goods_shop", "nox_weapon", 1, 2));
        ItemStack red = item(Material.RED_TERRACOTTA);
        ItemMeta meta = mock(ItemMeta.class);
        when(red.getItemMeta()).thenReturn(meta);
        when(confirm.getItem(ShopGui.BUY_SLOT)).thenReturn(red);
        assertNull(select(confirm, 6, "buy_starter_weapon"));

        when(meta.lore()).thenReturn(List.of(
            Component.text("◆ 購入確認 ◆", NamedTextColor.GOLD, TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false)
        ));
        var buyMark = select(confirm, 6, "buy_starter_weapon");
        assertNotNull(buyMark);
        assertTrue(buyMark.targetsWindowSlot(6, ShopGui.BUY_SLOT));
        assertFalse(buyMark.targetsPlayerSlot(ShopGui.BUY_SLOT));
    }

    @Test
    void lowerInventoryMappingRejectsTopCursorAndOutOfRangeSlots() {
        InventoryView view = chest(mock(InventoryHolder.class));
        PlayerInventory bottom = mock(PlayerInventory.class);
        when(view.getBottomInventory()).thenReturn(bottom);
        when(view.getInventory(81)).thenReturn(bottom);
        when(view.convertSlot(81)).thenReturn(0);
        when(view.getInventory(89)).thenReturn(bottom);
        when(view.convertSlot(89)).thenReturn(8);
        when(view.getInventory(54)).thenReturn(bottom);
        when(view.convertSlot(54)).thenReturn(9);
        when(view.getInventory(55)).thenReturn(bottom);
        when(view.convertSlot(55)).thenReturn(40);

        assertEquals(9, TutorialGuiMarker.playerSlot(view, 54));
        assertEquals(0, TutorialGuiMarker.playerSlot(view, 81));
        assertEquals(8, TutorialGuiMarker.playerSlot(view, 89));
        for (int rawSlot : List.of(-999, -1, 53, 55, 90)) {
            assertEquals(-1, TutorialGuiMarker.playerSlot(view, rawSlot));
        }

        var lower = new TutorialGuiMarker.Mark(8, 81, 0, new TutorialGuiMarker.AstralItem("20a00001"));
        assertTrue(lower.targetsWindowSlot(8, 81));
        assertTrue(lower.targetsPlayerSlot(0));
        assertFalse(lower.targetsWindowSlot(-1, 81));
        assertFalse(lower.targetsWindowSlot(-2, 0));
        assertFalse(lower.targetsWindowSlot(0, 81));
        assertFalse(lower.targetsWindowSlot(8, -1));
        assertFalse(lower.targetsPlayerSlot(-1));
        assertFalse(lower.targetsPlayerSlot(36));
        assertFalse(lower.targetsPlayerSlot(81));
    }

    @Test
    void windowDecorationCopiesOnlyTheTargetAndPreservesItsOriginalData() {
        ItemStack original = taggedItem(Material.PAPER, MAIL_KEY, "welcome_mail");
        ItemMeta originalMeta = original.getItemMeta();
        PersistentDataContainer originalPdc = originalMeta.getPersistentDataContainer();
        ItemStack copy = item(Material.PAPER);
        ItemMeta copyMeta = mock(ItemMeta.class);
        when(original.clone()).thenReturn(copy);
        when(copy.getItemMeta()).thenReturn(copyMeta);
        when(copy.setItemMeta(copyMeta)).thenReturn(true);
        Component vanillaName = Component.translatable("item.minecraft.paper");
        when(copy.effectiveName()).thenReturn(vanillaName);

        ItemStack other = item(Material.PAPER);
        List<ItemStack> originalItems = List.of(other, original);
        var guard = new TutorialGuiMarker.TaggedItem(MAIL_KEY, "welcome_mail");
        var mark = new TutorialGuiMarker.Mark(2, 1, -1, guard);

        assertNull(TutorialGuiMarker.decorateWindowItems(mark, 1, originalItems));
        assertNull(TutorialGuiMarker.decorateWindowItems(mark, 2, List.of(other)));
        assertNull(TutorialGuiMarker.decorateWindowItems(mark, 2, List.of(original, other)));
        assertNull(TutorialGuiMarker.decorateWindowItems(
            new TutorialGuiMarker.Mark(2, -1, -1, guard), 2, originalItems
        ));
        verify(original, never()).clone();
        verify(other, never()).clone();

        List<ItemStack> decorated = TutorialGuiMarker.decorateWindowItems(mark, 2, originalItems);
        assertNotNull(decorated);
        assertNotSame(originalItems, decorated);
        assertSame(original, originalItems.get(1));
        assertSame(other, decorated.get(0));
        assertSame(copy, decorated.get(1));

        ArgumentCaptor<Component> name = ArgumentCaptor.forClass(Component.class);
        verify(copyMeta).displayName(name.capture());
        assertEquals(
            Component.text("▶ ", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
            name.getValue().children().get(0)
        );
        assertEquals(vanillaName.decoration(TextDecoration.ITALIC, false), name.getValue().children().get(1));
        verify(copyMeta).setEnchantmentGlintOverride(true);
        verify(copy).setItemMeta(copyMeta);
        verify(originalMeta, never()).displayName(any(Component.class));
        verify(originalMeta, never()).setEnchantmentGlintOverride(any());
        verify(original, never()).setItemMeta(any());
        verify(original, never()).setAmount(anyInt());
        verify(copy, never()).setAmount(anyInt());
        verify(copyMeta, never()).getPersistentDataContainer();
        verify(originalPdc).get(MAIL_KEY, PersistentDataType.STRING);
        verifyNoMoreInteractions(originalPdc);
        verify(other, never()).clone();
    }

    @Test
    void menuMarksOnlyTheButtonForTheCurrentStep() {
        var holder = new io.github.maaasu.astralRecord.feature.menu.view.MenuInventoryHolder(
            io.github.maaasu.astralRecord.feature.menu.model.MenuScreen.MAIN, -1, 0, null, null, false);
        InventoryView menu = chest(holder);
        ItemStack mail = item(Material.CHEST);
        ItemStack equipment = item(Material.NETHERITE_CHESTPLATE);
        when(menu.getItem(37)).thenReturn(mail);
        when(menu.getItem(12)).thenReturn(equipment);
        assertEquals(37, select(menu, 6, "receive_welcome_mail").rawSlot());
        assertEquals(12, select(menu, 6, "equip_starter_weapon").rawSlot());
        assertNull(select(menu, 6, "defeat_grassboar"));
    }
    private static TutorialGuiMarker.Mark select(InventoryView view, Integer windowId, String step) {
        return TutorialGuiMarker.selectMark(view, windowId, PLAYER, step, false, MAIL_KEY, SHOP_KEY);
    }

    private static InventoryView chest(InventoryHolder holder) {
        InventoryView view = mock(InventoryView.class);
        Inventory top = mock(Inventory.class);
        when(view.getTopInventory()).thenReturn(top);
        when(view.countSlots()).thenReturn(90);
        when(top.getSize()).thenReturn(54);
        when(top.getHolder()).thenReturn(holder);
        return view;
    }

    private static MailEntry mail(String id, boolean read) {
        return new MailEntry(id, "CHEST", null, id, "", java.time.LocalDateTime.MIN, null,
            true, List.of(), read, null, false);
    }

    private static ItemStack copyable(ItemStack original) {
        ItemStack copy = item(original.getType());
        ItemMeta copyMeta = mock(ItemMeta.class);
        when(original.clone()).thenReturn(copy);
        when(copy.getItemMeta()).thenReturn(copyMeta);
        when(copyMeta.displayName()).thenReturn(Component.text("メール"));
        when(copy.setItemMeta(copyMeta)).thenReturn(true);
        return copy;
    }

    private static ItemStack taggedItem(Material material, NamespacedKey key, String id) {
        ItemStack item = item(material);
        ItemMeta meta = mock(ItemMeta.class);
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        when(item.getItemMeta()).thenReturn(meta);
        when(item.getPersistentDataContainer()).thenReturn(pdc);
        when(meta.getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.get(key, PersistentDataType.STRING)).thenReturn(id);
        return item;
    }

    private static ItemStack item(Material material) {
        ItemStack item = mock(ItemStack.class);
        when(item.getType()).thenReturn(material);
        return item;
    }
}
