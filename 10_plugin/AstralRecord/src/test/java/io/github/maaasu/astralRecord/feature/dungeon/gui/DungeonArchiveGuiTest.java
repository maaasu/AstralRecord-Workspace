package io.github.maaasu.astralRecord.feature.dungeon.gui;

import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.item.service.ItemStackFactory;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DungeonArchiveGuiTest extends MockBukkitTestBase {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 8. カルトグラフ
     * 検証契約: archive詳細だけが設定数量・確率を表示し、描画時は起動済みitem cache以外へI/Oしない。
     */
    @Test
    void showsConfiguredProbabilityUsingOnlyLoadedItemCache() {
        ItemService itemService = mock(ItemService.class);
        ItemStackFactory factory = mock(ItemStackFactory.class);
        ItemModel model = mock(ItemModel.class);
        when(itemService.findLoadedById("reward_item")).thenReturn(model);
        when(factory.create(model, 1)).thenReturn(new ItemStack(Material.DIAMOND));
        DungeonArchiveGui gui = new DungeonArchiveGui(itemService, factory);
        var player = server().addPlayer();
        DungeonArchiveGui.ArchiveDungeon dungeon = new DungeonArchiveGui.ArchiveDungeon(
                "internal_dungeon_id",
                "表示用ダンジョン名",
                3,
                Instant.parse("2026-08-12T00:00:00Z"),
                List.of(new DungeonArchiveGui.ArchiveReward("reward_item", "1~2", 12.34D))
        );

        gui.openDetails(player, UUID.randomUUID(), dungeon, 3, 0);

        ItemStack rendered = player.getOpenInventory().getTopInventory().getItem(0);
        List<String> lore = rendered.getItemMeta().lore().stream()
                .map(PlainTextComponentSerializer.plainText()::serialize)
                .toList();
        assertTrue(lore.stream().anyMatch(line -> line.contains("数量: 1~2")));
        assertTrue(lore.stream().anyMatch(line -> line.contains("設定確率: 12.34%")));
        assertEquals(3, gui.detailHolder(
                player.getOpenInventory().getTopInventory()).listPageIndex());
        verify(itemService).findLoadedById("reward_item");
        verify(itemService, never()).loadItem(org.mockito.ArgumentMatchers.anyString());
    }
}
