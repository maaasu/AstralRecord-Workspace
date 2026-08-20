package io.github.maaasu.astralRecord.feature.menu.view.screen;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.menu.model.PlayerEquipmentSnapshot;
import io.github.maaasu.astralRecord.feature.menu.model.PlayerGuiRenderContext;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.meta.SkullMeta;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainMenuScreenViewTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/09_2-ユースケース.md
     * 章・見出し: # 09_2-ユースケース > ## 1. メインメニューを開く
     * 検証契約: personal/social/utilityの各rowへ設計slotどおりmenu itemを配置する。
     */
    @Test
    void groupsMenuItemsIntoPersonalSocialAndUtilityRows() {
        var player = server().addPlayer();
        Inventory inventory = Bukkit.createInventory(null, BaseMenuScreenView.SIZE);
        var astPlayer = DesignTestFixtures.astPlayer(player, AccountMode.PLAYER);
        var context = new PlayerGuiRenderContext(
            astPlayer.getAccount(),
            astPlayer.getStatusSnapshot(),
            3,
            4,
            123L,
            100L,
            new PlayerEquipmentSnapshot(
                Component.text("星頭巾"),
                Component.text("なし"),
                Component.text("なし"),
                Component.text("なし")
            )
        );

        new MainMenuScreenView().render(inventory, player, context);

        assertEquals(20, MainMenuScreenView.STATUS_SLOT);
        assertEquals(21, MainMenuScreenView.EQUIPMENT_GUI_SLOT);
        assertEquals(22, MainMenuScreenView.SKILL_BIND_SLOT);
        assertEquals(23, MainMenuScreenView.QUEST_SLOT);
        assertEquals(24, MainMenuScreenView.PLAYER_SETTING_SLOT);
        assertEquals(29, MainMenuScreenView.ADVENTURE_RECORD_SLOT);
        assertEquals(30, MainMenuScreenView.MAIL_SLOT);
        assertEquals(31, MainMenuScreenView.PARTY_SLOT);
        assertEquals(32, MainMenuScreenView.PLAYER_INFO_SLOT);
        assertEquals(38, MainMenuScreenView.CURRENCY_SLOT);
        assertEquals(39, MainMenuScreenView.GUIDE_SLOT);
        assertEquals(40, MainMenuScreenView.RETURN_TO_BASE_SLOT);
        assertEquals(41, MainMenuScreenView.TRASH_SLOT);
        assertEquals(49, BaseMenuScreenView.BACK_SLOT);
        assertMaterial(inventory, 20, Material.PLAYER_HEAD);
        assertMaterial(inventory, 21, Material.NETHERITE_CHESTPLATE);
        assertMaterial(inventory, 22, Material.ENCHANTING_TABLE);
        assertMaterial(inventory, 23, Material.MAP);
        assertMaterial(inventory, 24, Material.COMPARATOR);
        assertDisplayNameContains(inventory, 23, "クエスト");
        assertMaterial(inventory, 29, Material.SPYGLASS);
        assertMaterial(inventory, 30, Material.CHEST);
        assertMaterial(inventory, 31, Material.IRON_CHAIN);
        assertMaterial(inventory, 32, Material.NAME_TAG);
        assertMaterial(inventory, 38, Material.BUNDLE);
        assertMaterial(inventory, 39, Material.KNOWLEDGE_BOOK);
        assertMaterial(inventory, 40, Material.BEACON);
        assertMaterial(inventory, 41, Material.LAVA_BUCKET);
        assertMaterial(inventory, 49, Material.BARRIER);
        assertDisplayNameContains(inventory, 20, "プレイヤー情報");
        assertDisplayNameContains(inventory, 31, "パーティー");
        assertPlayerHeadProfile(inventory, 20, player);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-GUI・View.md
     * 章・見出し: # 09_3-GUI・View > ## 2. メイン画面
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-GUI・View.md
     * 章・見出し: # 09_3-GUI・View > ## 3. アイコン生成
     * 検証契約: 装備・通貨・帰還iconのloreへ同一PlayerGuiRenderContextの装備名・残高・必要額を描画する。
     */
    @Test
    void rendersPlayerDependentLoreFromOneContext() {
        var player = server().addPlayer();
        Inventory inventory = Bukkit.createInventory(null, BaseMenuScreenView.SIZE);
        var astPlayer = DesignTestFixtures.astPlayer(player, AccountMode.PLAYER);
        var context = new PlayerGuiRenderContext(
            astPlayer.getAccount(),
            astPlayer.getStatusSnapshot(),
            3,
            4,
            123L,
            100L,
            new PlayerEquipmentSnapshot(
                Component.text("星頭巾"),
                Component.text("なし"),
                Component.text("なし"),
                Component.text("なし")
            )
        );

        new MainMenuScreenView().render(inventory, player, context);

        assertLoreContains(inventory, 21, "星頭巾");
        assertLoreContains(inventory, 38, "123 G");
        assertLoreContains(inventory, 40, "必要ゴールド 100");
    }

    private static void assertMaterial(Inventory inventory, int slot, Material expected) {
        assertEquals(expected, inventory.getItem(slot).getType());
    }

    private static void assertDisplayNameContains(Inventory inventory, int slot, String expected) {
        var displayName = inventory.getItem(slot).getItemMeta().displayName();
        assertTrue(displayName != null
            && PlainTextComponentSerializer.plainText().serialize(displayName).contains(expected));
    }

    private static void assertPlayerHeadProfile(Inventory inventory, int slot, org.bukkit.entity.Player expected) {
        var meta = inventory.getItem(slot).getItemMeta();
        assertTrue(meta instanceof SkullMeta);
        assertTrue(((SkullMeta) meta).getPlayerProfile() != null);
        assertEquals(expected.getPlayerProfile().getId(), ((SkullMeta) meta).getPlayerProfile().getId());
    }

    private static void assertLoreContains(Inventory inventory, int slot, String expected) {
        var lore = inventory.getItem(slot).getItemMeta().lore();
        assertTrue(lore != null && lore.stream()
            .map(PlainTextComponentSerializer.plainText()::serialize)
            .anyMatch(line -> line.contains(expected)));
    }
}
