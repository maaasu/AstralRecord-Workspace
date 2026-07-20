package io.github.maaasu.astralRecord.feature.menu.view;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.menu.model.CurrencyDisplayEntry;
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
        assertEquals(Material.BUNDLE, first.getType());
        assertEquals("カレンシー", plain(first.getItemMeta().displayName()));
        assertEquals(List.of(
            "所持通貨を確認",
            "◆ 合計ゴールド ◆",
            "321 G",
            "",
            "◆ 所持カレンシー ◆"
        ), first.getItemMeta().lore().stream()
            .map(MenuIconFactoryTest::plain)
            .toList());

        first.setAmount(2);
        assertEquals(1, second.getAmount());
    }

    @Test
    void limitsCurrencyDetailsToTenEntriesAndAddsEllipsis() {
        PlayerGuiRenderContext base = context();
        List<CurrencyDisplayEntry> balances = new java.util.ArrayList<>();
        for (int index = 1; index <= 11; index++) {
            balances.add(new CurrencyDisplayEntry(
                "currency_" + index,
                Component.text("通貨" + index),
                index
            ));
        }
        PlayerGuiRenderContext manyCurrencies = new PlayerGuiRenderContext(
            base.account(),
            base.statusSnapshot(),
            base.availableClassPoints(),
            base.availablePassivePoints(),
            base.goldAmount(),
            base.returnToBaseGoldCost(),
            base.equipment(),
            balances
        );

        assertEquals(
            List.of(
                "◆ 合計ゴールド ◆",
                "321 G",
                "",
                "◆ 所持カレンシー ◆",
                "通貨1: 1",
                "通貨2: 2",
                "通貨3: 3",
                "通貨4: 4",
                "通貨5: 5",
                "通貨6: 6",
                "通貨7: 7",
                "通貨8: 8",
                "通貨9: 9",
                "通貨10: 10",
                "…"
            ),
            MenuIconFactory.currencyDetails(manyCurrencies).stream()
                .map(MenuIconFactoryTest::plain)
                .toList()
        );
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
