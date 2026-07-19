package io.github.maaasu.astralRecord.feature.menu.view;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.menu.model.MenuIconDefinition;
import io.github.maaasu.astralRecord.feature.menu.model.MenuShortcutAction;
import io.github.maaasu.astralRecord.feature.menu.model.PlayerEquipmentSnapshot;
import io.github.maaasu.astralRecord.feature.menu.model.PlayerGuiRenderContext;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

class MenuIconFactoryTest extends MockBukkitTestBase {

    @Test
    void createsIndependentItemStacksFromSharedDefinition() {
        PlayerGuiRenderContext context = context();

        var first = MenuIconFactory.create(
            MenuIconDefinition.CURRENCY,
            MenuIconFactory.currencyDetails(context)
        );
        var second = MenuIconFactory.create(
            MenuIconDefinition.CURRENCY,
            MenuIconFactory.currencyDetails(context)
        );

        assertNotSame(first, second);
        assertEquals(Material.EMERALD, first.getType());
        assertEquals("通貨", plain(first.getItemMeta().displayName()));
        assertEquals(List.of("所持通貨を確認", "ゴールド: 321"), first.getItemMeta().lore().stream()
            .map(MenuIconFactoryTest::plain)
            .toList());

        first.setAmount(2);
        assertEquals(1, second.getAmount());
    }

    @Test
    void shortcutActionsReferenceTheSameSemanticIconDefinitions() {
        assertSame(MenuIconDefinition.MAIN_MENU, MenuShortcutAction.MAIN_MENU.getIconDefinition());
        assertSame(MenuIconDefinition.ACCOUNT_INFO, MenuShortcutAction.STATUS.getIconDefinition());
        assertSame(MenuIconDefinition.RETURN_TO_BASE, MenuShortcutAction.RETURN_TO_BASE.getIconDefinition());
        assertSame(MenuIconDefinition.CURRENCY, MenuShortcutAction.INVENTORY_CURRENCY.getIconDefinition());
        assertSame(MenuIconDefinition.EQUIPMENT, MenuShortcutAction.EQUIPMENT_GUI.getIconDefinition());
    }

    private PlayerGuiRenderContext context() {
        var astPlayer = DesignTestFixtures.astPlayer(server().addPlayer(), AccountMode.PLAYER);
        return new PlayerGuiRenderContext(
            astPlayer.getAccount(),
            astPlayer.getStatusSnapshot(),
            0,
            0,
            321L,
            100L,
            new PlayerEquipmentSnapshot(
                Component.text("なし"),
                Component.text("なし"),
                Component.text("なし"),
                Component.text("なし")
            )
        );
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}
