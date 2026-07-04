package io.github.maaasu.astralRecord.feature.inventory.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryType;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.user.model.UserModel;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class HotbarShortcutRendererMockBukkitTest extends MockBukkitTestBase {

    @Test
    void renderShortcutIconsSetsCloseAndInventoryCycleItemsToExpectedHotbarSlots() {
        PlayerMock player = server().addPlayer();
        AstPlayer astPlayer = astPlayer(player);
        HotbarRenderer renderer = new HotbarRenderer(mock(InventoryItemStackResolver.class));

        renderer.renderShortcutIcons(astPlayer, InventoryType.NORMAL, Map.of(InventoryType.NORMAL, 3L), 7);

        assertEquals(Material.LIGHT_GRAY_STAINED_GLASS_PANE, player.getInventory().getItem(0).getType());
        assertEquals(Material.BARRIER, player.getInventory().getItem(HotbarRenderer.SHORTCUT_CLOSE_SLOT).getType());
        assertEquals(Material.CHEST, player.getInventory().getItem(HotbarRenderer.SHORTCUT_INVENTORY_CYCLE_SLOT).getType());
        assertEquals(7, player.getInventory().getItem(HotbarRenderer.SHORTCUT_INVENTORY_CYCLE_SLOT).getAmount());
        assertEquals(Material.GRAY_STAINED_GLASS_PANE, player.getInventory().getItemInOffHand().getType());
    }

    private static AstPlayer astPlayer(PlayerMock player) {
        UUID userId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID systemId = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        UserModel user = new UserModel(
            userId,
            player.getName(),
            now,
            now,
            "127.0.0.1",
            accountId,
            false,
            null,
            false,
            0,
            now,
            now,
            systemId,
            systemId,
            false
        );
        AccountModel account = new AccountModel(
            accountId,
            userId,
            "test",
            0,
            true,
            AccountMode.PLAYER,
            "{}",
            now,
            now,
            systemId,
            systemId,
            false
        );
        return new AstPlayer(player, user, account);
    }
}
