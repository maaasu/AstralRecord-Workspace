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
        values.put(StatusType.FIRE_RESISTANCE, new StatusValue(10.0D, 2.0D));
        values.put(StatusType.BURNING_RESISTANCE, new StatusValue(15.0D, 3.0D));
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
        String elementLore = plainLore(inventory.getItem(PlayerDetailGui.ELEMENT_SLOT));
        String conditionLore = plainLore(inventory.getItem(PlayerDetailGui.CONDITION_SLOT));
        String buffLore = plainLore(inventory.getItem(PlayerDetailGui.BUFF_SLOT));
        String classLore = plainLore(inventory.getItem(PlayerDetailGui.CLASS_SLOT));

        assertTrue(headLore.contains("Greenfall Fields"));
        assertFalse(headLore.contains("internal_greenfall"));
        assertFalse(headLore.contains("X:"));
        assertTrue(statusLore.contains("105"));
        assertTrue(statusLore.contains("100"));
        assertTrue(statusLore.contains("+5"));
        assertTrue(statusLore.contains("105  (100 +5)"));
        assertFalse(statusLore.contains("基礎"));
        assertFalse(statusLore.contains("補正"));
        assertTrue(elementLore.contains("火属性耐性"));
        assertTrue(elementLore.contains("12.0%  (10.0% +2.0%)"));
        assertTrue(conditionLore.contains("燃焼付与耐性"));
        assertTrue(conditionLore.contains("18.0%  (15.0% +3.0%)"));
        assertTrue(buffLore.contains("クリックで詳細を表示"));
        assertTrue(classLore.contains("Adventurer Lv.10"));
        assertTrue(classLore.contains("Mage Lv.4"));
        assertTrue(classLore.contains("CEXP"));
        assertFalse(headLore.contains("Class Lv."));
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
            null,
            null
        );
    }
}
