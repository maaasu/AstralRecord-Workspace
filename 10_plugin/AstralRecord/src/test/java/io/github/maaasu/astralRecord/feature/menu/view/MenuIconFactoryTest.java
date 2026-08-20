package io.github.maaasu.astralRecord.feature.menu.view;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.menu.model.CurrencyDisplayEntry;
import io.github.maaasu.astralRecord.feature.menu.model.MenuIconDefinition;
import io.github.maaasu.astralRecord.feature.menu.model.MenuShortcutAction;
import io.github.maaasu.astralRecord.feature.menu.model.PlayerEquipmentSnapshot;
import io.github.maaasu.astralRecord.feature.menu.model.PlayerGuiRenderContext;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MenuIconFactoryTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-GUI・View.md
     * 章・見出し: # 09_3-GUI・View > ## 5. 画面固有の描画不変条件
     * 検証契約: 共通icon定義から呼出ごとに独立ItemStackを生成し相互変更を漏らさず、
     * カレンシーのBUNDLE内容量を表示しない。
     */
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
        TooltipDisplay tooltipDisplay = first.getData(DataComponentTypes.TOOLTIP_DISPLAY);
        assertNotNull(tooltipDisplay);
        assertTrue(tooltipDisplay.hiddenComponents().contains(DataComponentTypes.BUNDLE_CONTENTS));
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

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-GUI・View.md
     * 章・見出し: # 09_3-GUI・View > ## 5. 画面固有の描画不変条件
     * 検証契約: currency detailを最大10件に制限し超過時ellipsisを追加する。
     */
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

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/09_1-モデル定義.md
     * 章・見出し: # 09_1-モデル定義 > ## 3. 共通アイコン
     * 検証契約: メインメニューの共通アイコンは用途ごとに異なる Material を使用する。
     */
    @Test
    void usesDistinctMaterialsForEachMenuIconDefinition() {
        List<Material> materials = java.util.Arrays.stream(MenuIconDefinition.values())
            .map(MenuIconDefinition::getMaterial)
            .toList();

        assertEquals(materials.size(), new java.util.HashSet<>(materials).size());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-GUI・View.md
     * 章・見出し: # 09_3-GUI・View > ## 3. アイコン生成
     * 検証契約: プレイヤー情報アイコンへ対象プレイヤーのスキンを設定する。
     */
    @Test
    void createsPlayerInfoIconWithTargetPlayerSkin() {
        var player = server().addPlayer();

        var itemStack = MenuIconFactory.createPlayerInfo(player);

        assertEquals(Material.PLAYER_HEAD, itemStack.getType());
        assertTrue(itemStack.getItemMeta() instanceof org.bukkit.inventory.meta.SkullMeta);
        var profile = ((org.bukkit.inventory.meta.SkullMeta) itemStack.getItemMeta()).getPlayerProfile();
        assertNotNull(profile);
        assertEquals(player.getPlayerProfile().getId(), profile.getId());
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/09_1-モデル定義.md
     * 章・見出し: # 09_1-モデル定義 > ## 2. クラフトショートカット
     * 検証契約: 各shortcut actionを同じ意味の共通icon定義へ対応付ける。
     */
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
