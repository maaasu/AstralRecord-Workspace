package io.github.maaasu.astralRecord.feature.skill.service;

import io.github.maaasu.astralRecord.feature.item.service.ItemStackFactory;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SkillActionRingHoldServiceTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/13-skill/4-統合フロー/13_4-統合フロー.md
     * 章・見出し: # 13_4-統合フロー > ## 4. action ring・skilltree 入力調停
     * 検証契約: RELEASE_USE_ITEMの確定前に、開始時と同じhotbar slot・武器ID・instance IDをサーバー側Paper ItemStackで再確認する。
     */
    @Test
    void matchesHeldWeaponRequiresTheOriginalServerPaperWeapon() {
        Player player = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        ItemStack weapon = astralWeapon("sword_test", "instance_test");
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getHeldItemSlot()).thenReturn(2);
        when(inventory.getItem(2)).thenReturn(weapon);

        assertTrue(ItemStackFactory.isWeapon(weapon));
        assertTrue(SkillActionRingHoldService.matchesHeldWeapon(player, 2, "sword_test", "instance_test"));
        assertFalse(SkillActionRingHoldService.matchesHeldWeapon(player, 2, "sword_test", "other_instance"));
        assertFalse(SkillActionRingHoldService.matchesHeldWeapon(player, 3, "sword_test", "instance_test"));
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
}
