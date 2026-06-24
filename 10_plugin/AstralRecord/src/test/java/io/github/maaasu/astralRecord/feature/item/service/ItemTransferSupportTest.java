package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.feature.item.model.ItemReference;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class ItemTransferSupportTest extends MockBukkitTestBase {
    private static final PlainTextComponentSerializer PLAIN_TEXT = PlainTextComponentSerializer.plainText();

    @Test
    void resolveTransferAmountMapsSupportedClicks() {
        assertEquals(1, ItemTransferSupport.resolveTransferAmount(ClickType.LEFT, 20));
        assertEquals(20, ItemTransferSupport.resolveTransferAmount(ClickType.SHIFT_LEFT, 20));
        assertEquals(10, ItemTransferSupport.resolveTransferAmount(ClickType.RIGHT, 20));
        assertEquals(11, ItemTransferSupport.resolveTransferAmount(ClickType.RIGHT, 21));
        assertEquals(0, ItemTransferSupport.resolveTransferAmount(ClickType.DROP, 20));
        assertEquals(0, ItemTransferSupport.resolveTransferAmount(ClickType.LEFT, 0));
    }

    @Test
    void stripDisplayLoreRemovesOnlyManagedPrefixes() {
        ItemStack itemStack = new ItemStack(Material.STONE, 3);
        ItemMeta meta = itemStack.getItemMeta();
        meta.lore(List.of(
            Component.text("amount: 3"),
            Component.text("keep me"),
            Component.text("price: 12")
        ));
        itemStack.setItemMeta(meta);

        ItemStack cleaned = ItemTransferSupport.stripDisplayLore(itemStack, "amount: ", "price: ");

        assertNotSame(itemStack, cleaned);
        List<String> lore = cleaned.getItemMeta().lore().stream()
            .map(PLAIN_TEXT::serialize)
            .toList();
        assertEquals(List.of("keep me"), lore);
        assertEquals(3, itemStack.getAmount());
    }

    @Test
    void normalizeMergesPlainStacksUpToMaxStackSize() {
        ItemStack first = new ItemStack(Material.STONE, 40);
        ItemStack second = new ItemStack(Material.STONE, 40);

        List<ItemStack> normalized = ItemTransferSupport.normalize(
            List.of(first, second),
            this::isEmpty,
            ItemStack::clone,
            itemStack -> new ItemReference("stone", "BLOCK", null, null)
        );

        assertEquals(2, normalized.size());
        assertEquals(64, normalized.get(0).getAmount());
        assertEquals(16, normalized.get(1).getAmount());
        assertEquals(40, first.getAmount());
        assertEquals(40, second.getAmount());
    }

    @Test
    void normalizeKeepsInstancedItemsSeparate() {
        ItemStack first = new ItemStack(Material.STONE, 20);
        ItemStack second = new ItemStack(Material.STONE, 20);

        List<ItemStack> normalized = ItemTransferSupport.normalize(
            List.of(first, second),
            this::isEmpty,
            ItemStack::clone,
            itemStack -> new ItemReference("stone", "BLOCK", "equipment-" + itemStack.getAmount(), null)
        );

        assertEquals(2, normalized.size());
        assertEquals(20, normalized.get(0).getAmount());
        assertEquals(20, normalized.get(1).getAmount());
    }

    private boolean isEmpty(Inventory ignored, ItemStack itemStack) {
        return itemStack == null || itemStack.getType().isAir();
    }
}
