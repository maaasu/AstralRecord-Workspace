package io.github.maaasu.astralRecord.feature.menu.player;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.feature.status.model.StatusValue;
import io.github.maaasu.astralRecord.feature.world.model.WorldMasterData;
import io.github.maaasu.astralRecord.feature.world.model.WorldSpawnLocation;
import io.github.maaasu.astralRecord.feature.world.model.WorldType;
import io.github.maaasu.astralRecord.feature.world.service.WorldService;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlayerDetailGuiTest extends MockBukkitTestBase {

    @Test
    void consolidatesProfileAndUsesDisplayWorldNameAndCompactStatusValues() {
        var player = server().addPlayer();
        var astPlayer = DesignTestFixtures.astPlayer(player, AccountMode.PLAYER);
        WorldService worldService = mock(WorldService.class);
        when(worldService.findByBukkitWorld(player.getWorld())).thenReturn(world("internal_greenfall", "&aGreenfall Fields"));

        EnumMap<StatusType, StatusValue> values = new EnumMap<>(StatusType.class);
        values.put(StatusType.MAX_HEALTH, new StatusValue(100.0D, 5.0D));
        StatusSnapshot snapshot = new StatusSnapshot(values, 100.0D, 0.0D, 0.0D, 0.0D, 0L, LocalDateTime.now());

        new PlayerDetailGui(worldService).open(
            player,
            astPlayer,
            snapshot,
            250L,
            "Adventurer",
            0.5D,
            100L
        );

        Inventory inventory = player.getOpenInventory().getTopInventory();
        String headLore = plainLore(inventory.getItem(PlayerDetailGui.HEAD_SLOT));
        String statusLore = plainLore(inventory.getItem(PlayerDetailGui.RESOURCE_SLOT));

        assertTrue(headLore.contains("Greenfall Fields"));
        assertFalse(headLore.contains("internal_greenfall"));
        assertFalse(headLore.contains("X:"));
        assertTrue(statusLore.contains("105"));
        assertTrue(statusLore.contains("100"));
        assertTrue(statusLore.contains("+5"));
        assertTrue(statusLore.contains("105  (100 +5)"));
        assertFalse(statusLore.contains("基礎"));
        assertFalse(statusLore.contains("補正"));
    }

    private static String plainLore(ItemStack itemStack) {
        List<Component> lore = itemStack.getItemMeta().lore();
        if (lore == null) {
            return "";
        }
        return lore.stream()
            .map(PlainTextComponentSerializer.plainText()::serialize)
            .reduce("", (left, right) -> left + "\n" + right);
    }

    private static WorldMasterData world(String id, String displayName) {
        return new WorldMasterData(
            1,
            id,
            displayName,
            WorldType.OVERWORLD,
            id,
            "world_instances",
            false,
            false,
            0,
            false,
            false,
            false,
            true,
            WorldSpawnLocation.defaultLocation(),
            id,
            null,
            null
        );
    }
}
