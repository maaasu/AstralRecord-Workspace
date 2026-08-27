package io.github.maaasu.astralRecord.feature.menu.player;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.github.maaasu.astralRecord.feature.playerclass.model.ClassProgressViewEntry;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.feature.status.model.StatusValue;
import io.github.maaasu.astralRecord.feature.world.model.WorldMasterData;
import io.github.maaasu.astralRecord.feature.world.model.WorldSpawnLocation;
import io.github.maaasu.astralRecord.feature.world.model.WorldType;
import io.github.maaasu.astralRecord.feature.world.service.WorldService;
import io.github.maaasu.astralRecord.support.DesignTestFixtures;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
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

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-GUI・View.md
     * 章・見出し: # 09_3-GUI・View > ## 4. プレイヤー一覧・詳細
     * 検証契約: プロフィールを1画面へ統合し、world表示名とリソース・属性・状態異常の合計値（基礎値＋補正値）をcompact描画する。
     */
    @Test
    void consolidatesProfileAndUsesDisplayWorldNameAndCompactStatusValues() {
        var player = server().addPlayer();
        var astPlayer = DesignTestFixtures.astPlayer(player, AccountMode.PLAYER);
        WorldService worldService = mock(WorldService.class);
        when(worldService.findByBukkitWorld(player.getWorld())).thenReturn(world("internal_greenfall", "&aGreenfall Fields"));

        EnumMap<StatusType, StatusValue> values = new EnumMap<>(StatusType.class);
        values.put(StatusType.MAX_HEALTH, new StatusValue(100.0D, 5.0D));
        values.put(StatusType.FIRE_DAMAGE_INCREASE, new StatusValue(20.0D, 0.0D));
        values.put(StatusType.FIRE_RESISTANCE, new StatusValue(10.0D, 2.0D));
        values.put(StatusType.ICE_DAMAGE_INCREASE, new StatusValue(30.0D, 0.0D));
        values.put(StatusType.ICE_RESISTANCE, new StatusValue(40.0D, 0.0D));
        values.put(StatusType.BURNING_RESISTANCE, new StatusValue(15.0D, 3.0D));
        values.put(StatusType.MAX_SHIELD, new StatusValue(0.0D, 0.0D));
        values.put(StatusType.LIGHTNING_DAMAGE_INCREASE, new StatusValue(0.0D, 0.0D));
        StatusType.byCategory(StatusType.Category.OFFENSE).forEach(type ->
            values.put(type, new StatusValue(0.0D, 0.0D)));
        StatusSnapshot snapshot = new StatusSnapshot(values, 100.0D, 0.0D, 0.0D, 0.0D, 0L, LocalDateTime.now());

        new PlayerDetailGui(worldService).open(
            player,
            astPlayer,
            snapshot,
            250L,
            "Adventurer",
            List.of(
                new ClassProgressViewEntry("adventurer", "Adventurer", "WOODEN_SWORD", 10, 4000L, 0.5D, 100L, true),
                new ClassProgressViewEntry("mage", "Mage", "BLAZE_ROD", 4, 900L, 0.25D, 300L, false)
            )
        );

        Inventory inventory = player.getOpenInventory().getTopInventory();
        String headLore = plainLore(inventory.getItem(PlayerDetailGui.HEAD_SLOT));
        String statusLore = plainLore(inventory.getItem(PlayerDetailGui.RESOURCE_SLOT));
        String offenseLore = plainLore(inventory.getItem(PlayerDetailGui.OFFENSE_SLOT));
        String elementLore = plainLore(inventory.getItem(PlayerDetailGui.ELEMENT_SLOT));
        String conditionLore = plainLore(inventory.getItem(PlayerDetailGui.CONDITION_SLOT));
        String buffLore = plainLore(inventory.getItem(PlayerDetailGui.BUFF_SLOT));
        String classLore = plainLore(inventory.getItem(PlayerDetailGui.CLASS_SLOT));
        String equipmentLore = plainLore(inventory.getItem(PlayerDetailGui.EQUIPMENT_SLOT));

        assertTrue(headLore.contains("Greenfall Fields"));
        assertFalse(headLore.contains("internal_greenfall"));
        assertFalse(headLore.contains("X:"));
        assertTrue(statusLore.contains("105"));
        assertTrue(statusLore.contains("100"));
        assertTrue(statusLore.contains("+5"));
        assertTrue(statusLore.contains("105  (100 +5)"));
        assertFalse(statusLore.contains("最大シールド"));
        assertFalse(statusLore.contains("基礎"));
        assertFalse(statusLore.contains("補正"));
        assertTrue(offenseLore.contains("ステータス情報はありません"));
        assertTrue(NamedTextColor.GRAY.equals(findLoreLine(inventory.getItem(PlayerDetailGui.OFFENSE_SLOT), "ステータス情報はありません").color()));
        assertTrue(elementLore.contains("火属性耐性"));
        assertTrue(elementLore.contains("12.0%  (10.0% +2.0%)"));
        assertTrue(elementLore.indexOf("火属性ダメージ増加") < elementLore.indexOf("氷属性ダメージ増加"));
        assertTrue(elementLore.indexOf("氷属性ダメージ増加") < elementLore.indexOf("火属性耐性"));
        assertTrue(elementLore.indexOf("火属性耐性") < elementLore.indexOf("氷属性耐性"));
        assertFalse(elementLore.contains("雷属性ダメージ増加"));
        assertTrue(conditionLore.contains("燃焼付与耐性"));
        assertTrue(conditionLore.contains("18.0%  (15.0% +3.0%)"));
        assertTrue(buffLore.contains("クリックで詳細を表示"));
        assertTrue(classLore.contains("Adventurer Lv.10"));
        assertTrue(classLore.contains("Mage Lv.4"));
        assertTrue(classLore.contains("CEXP"));
        assertTrue(Material.NETHERITE_CHESTPLATE.equals(inventory.getItem(PlayerDetailGui.EQUIPMENT_SLOT).getType()));
        assertTrue(equipmentLore.contains("クリックして装備画面を開く"));
        assertFalse(headLore.contains("Class Lv."));
        assertTrue(NamedTextColor.RED.equals(findTextComponent(inventory.getItem(PlayerDetailGui.ELEMENT_SLOT), "火属性ダメージ増加").color()));
        assertTrue(NamedTextColor.AQUA.equals(findTextComponent(inventory.getItem(PlayerDetailGui.ELEMENT_SLOT), "氷属性ダメージ増加").color()));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/09-menu/3-メソッド仕様/09_3-GUI・View.md
     * 章・見出し: # 09_3-GUI・View > ## 4. プレイヤー一覧・詳細
     * 検証契約: プレイヤー詳細のカテゴリから、対象カテゴリのステータスを名称・説明・基礎値・補正値付きで参照できる。
     */
    @Test
    void opensCategoryStatusDetailWithDescriptionAndValueBreakdown() {
        var player = server().addPlayer();
        var astPlayer = DesignTestFixtures.astPlayer(player, AccountMode.PLAYER);
        WorldService worldService = mock(WorldService.class);
        EnumMap<StatusType, StatusValue> values = new EnumMap<>(StatusType.class);
        values.put(StatusType.MAX_HEALTH, new StatusValue(20.0D, 15.0D));
        values.put(StatusType.MAX_MANA, new StatusValue(10.0D, 2.0D));
        values.put(StatusType.MAX_ENERGY, new StatusValue(0.0D, 0.0D));
        StatusSnapshot snapshot = new StatusSnapshot(values, 35.0D, 12.0D, 0.0D, 0.0D, 0L, LocalDateTime.now());

        PlayerDetailGui gui = new PlayerDetailGui(worldService);
        gui.openStatusDetail(player, astPlayer, StatusType.Category.RESOURCE, snapshot, 0);

        Inventory inventory = player.getOpenInventory().getTopInventory();
        assertTrue(gui.isStatusDetailInventory(inventory));
        assertTrue(StatusType.Category.RESOURCE == gui.getStatusDetailCategory(inventory));
        assertTrue(player.getUniqueId().equals(gui.getStatusDetailTargetId(inventory)));
        ItemStack healthItem = inventory.getItem(0);
        String itemName = PlainTextComponentSerializer.plainText()
            .serialize(healthItem.getItemMeta().displayName());
        String lore = plainLore(healthItem);
        assertTrue(itemName.contains("最大HP"));
        assertTrue(lore.contains("戦闘不能になるまでに耐えられるダメージ量の上限。"));
        assertTrue(lore.contains("現在値: 35"));
        assertTrue(lore.contains("基礎値: 20"));
        assertTrue(lore.contains("合計補正: +15"));
        assertTrue(Material.AIR.equals(inventory.getItem(2).getType()));
        assertTrue(gui.getStatusDetailItemCount(StatusType.Category.RESOURCE, snapshot) == 2);
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

    private static Component findTextComponent(ItemStack itemStack, String text) {
        return itemStack.getItemMeta().lore().stream()
            .flatMap(component -> component.children().stream())
            .filter(component -> text.equals(PlainTextComponentSerializer.plainText().serialize(component)))
            .findFirst()
            .orElseThrow();
    }

    private static Component findLoreLine(ItemStack itemStack, String text) {
        return itemStack.getItemMeta().lore().stream()
            .filter(component -> text.equals(PlainTextComponentSerializer.plainText().serialize(component)))
            .findFirst()
            .orElseThrow();
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
            null,
            null
        );
    }
}
