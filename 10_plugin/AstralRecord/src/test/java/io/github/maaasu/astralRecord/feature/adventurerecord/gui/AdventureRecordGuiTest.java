package io.github.maaasu.astralRecord.feature.adventurerecord.gui;

import io.github.maaasu.astralRecord.feature.adventurerecord.service.AdventureRecordService;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.loot.model.LootContent;
import io.github.maaasu.astralRecord.feature.loot.model.LootModel;
import io.github.maaasu.astralRecord.feature.loot.model.LootPoolModel;
import io.github.maaasu.astralRecord.feature.loot.service.LootService;
import io.github.maaasu.astralRecord.feature.mob.model.MobCategory;
import io.github.maaasu.astralRecord.feature.mob.model.MobBaseStat;
import io.github.maaasu.astralRecord.feature.mob.model.MobDropConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobEquipmentConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobIdleConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobInteractionsConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobShieldConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobTemplate;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdventureRecordGuiTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/21-adventurerecord/21_3-メソッド仕様.md
     * 章・見出し: # 21_3-メソッド仕様 > ## 表示 entry 生成
     * 検証契約: ロード済み lootTable の平坦化候補を、直接 drop と同じドロップアイテムリストへ表示する。
     */
    @Test
    void appendsLoadedLootTableEntriesToMobDropLore() {
        ItemService itemService = mock(ItemService.class);
        LootService lootService = mock(LootService.class);
        ItemModel item = mock(ItemModel.class);
        when(itemService.findLoadedById("table_reward")).thenReturn(item);
        when(item.getName()).thenReturn("テーブル報酬");
        when(lootService.getLoaded("loot_table")).thenReturn(new LootModel(
            1,
            "loot_table",
            "loot_table",
            1,
            List.of(new LootPoolModel(
                "loot_pool",
                1,
                List.of(new LootContent("table_reward", 2, 3, 12.34D))
            ))
        ));

        AdventureRecordGui gui = new AdventureRecordGui(itemService, lootService);
        var player = server().addPlayer();
        gui.openMobList(
            player,
            io.github.maaasu.astralRecord.feature.adventurerecord.model.AdventureRecordListType.ALL,
            List.of(new AdventureRecordService.Entry(
                mobWithDrops(new MobDropConfig(0, null, List.of(), "loot_table")),
                null,
                true
            )),
            0,
            Set.of(),
            false
        );

        ItemStack rendered = player.getOpenInventory().getTopInventory().getItem(0);
        List<String> lore = rendered.getItemMeta().lore().stream()
            .map(PlainTextComponentSerializer.plainText()::serialize)
            .toList();

        assertTrue(lore.contains("- テーブル報酬 x2~3 (12.34%)"));
        verify(lootService).getLoaded("loot_table");
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/21-adventurerecord/21_2-ユースケース.md
     * 章・見出し: # 21_2-ユースケース > ## UC-21-06 Mob のステータスを確認する
     * 検証契約: Mob 情報画面は player 情報画面と同じカテゴリ構成で、MobTemplate の基礎値を概要表示する。
     */
    @Test
    void rendersMobStatusCategoriesFromTemplateBaseStats() {
        AdventureRecordGui gui = new AdventureRecordGui(mock(ItemService.class), mock(LootService.class));
        var player = server().addPlayer();
        AdventureRecordService.Entry entry = new AdventureRecordService.Entry(
            mobWithDrops(
                new MobDropConfig(0, null, List.of(), null),
                List.of(new MobBaseStat("MAX_HEALTH", 120.0D), new MobBaseStat("ATTACK", 42.0D))
            ),
            null,
            true
        );

        gui.openMobDetail(player, entry);

        List<String> lore = player.getOpenInventory().getTopInventory()
            .getItem(AdventureRecordGui.MOB_RESOURCE_SLOT).getItemMeta().lore().stream()
            .map(PlainTextComponentSerializer.plainText()::serialize)
            .toList();

        assertTrue(lore.stream().anyMatch(line -> line.contains("最大HP") && line.contains("120")));
        assertEquals(AdventureRecordGui.Screen.MOB_DETAIL,
            gui.getScreen(player.getOpenInventory().getTopInventory()));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/21-adventurerecord/21_3-メソッド仕様.md
     * 章・見出し: # 21_3-メソッド仕様 > ## 表示 entry 生成 > ### Mob 詳細・ステータス表示
     * 検証契約: ステータス詳細項目は StatusType の説明と単位を使い、Mob の基礎値として表示する。
     */
    @Test
    void rendersMobStatusDetailWithStatusTypeDefinition() {
        AdventureRecordGui gui = new AdventureRecordGui(mock(ItemService.class), mock(LootService.class));
        var player = server().addPlayer();
        AdventureRecordService.Entry entry = new AdventureRecordService.Entry(
            mobWithDrops(
                new MobDropConfig(0, null, List.of(), null),
                List.of(new MobBaseStat("ATTACK_SPEED", 125.0D))
            ),
            null,
            true
        );

        gui.openMobStatusDetail(player, entry, StatusType.Category.OFFENSE, 0);

        ItemStack rendered = player.getOpenInventory().getTopInventory().getItem(0);
        List<String> lore = rendered.getItemMeta().lore().stream()
            .map(PlainTextComponentSerializer.plainText()::serialize)
            .toList();

        assertEquals(Material.NETHERITE_SWORD, rendered.getType());
        assertTrue(lore.contains("基礎値: 125%"));
        assertEquals(AdventureRecordGui.Screen.MOB_STATUS_DETAIL,
            gui.getScreen(player.getOpenInventory().getTopInventory()));
    }

    private MobTemplate mobWithDrops(MobDropConfig drops) {
        return mobWithDrops(drops, List.of());
    }

    private MobTemplate mobWithDrops(MobDropConfig drops, List<MobBaseStat> baseStats) {
        return new MobTemplate(
            1,
            "loot_mob",
            MobCategory.ENEMY,
            "loot_mob",
            null,
            1,
            EntityType.ZOMBIE,
            false,
            null,
            List.of(),
            List.of(),
            null,
            MobEquipmentConfig.EMPTY,
            baseStats,
            MobShieldConfig.EMPTY,
            MobIdleConfig.defaults(),
            false,
            MobInteractionsConfig.EMPTY,
            null,
            null,
            drops
        );
    }
}
