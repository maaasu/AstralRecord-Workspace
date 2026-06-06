package io.github.maaasu.astralRecord.feature.inventory.service;

import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HotbarLayoutMockBukkitTest extends MockBukkitTestBase {

    @Test
    void createSnapshotAndApplySnapshotRestoreManagedHotbarSlots() {
        PlayerMock player = server().addPlayer();
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND));
        player.getInventory().setItem(1, new ItemStack(Material.GOLD_INGOT));
        player.getInventory().setItem(8, new ItemStack(Material.EMERALD));

        ItemStack[] snapshot = HotbarLayout.createSnapshot(player);

        player.getInventory().setItem(0, new ItemStack(Material.DIRT));
        player.getInventory().setItem(1, new ItemStack(Material.STONE));
        player.getInventory().setItem(8, new ItemStack(Material.COBBLESTONE));

        HotbarLayout.applySnapshot(player, snapshot);

        assertEquals(Material.DIAMOND, player.getInventory().getStorageContents()[0].getType());
        assertEquals(Material.GOLD_INGOT, player.getInventory().getStorageContents()[1].getType());
        assertEquals(Material.EMERALD, player.getInventory().getStorageContents()[8].getType());
    }
}
