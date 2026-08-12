package io.github.maaasu.astralRecord.feature.dungeon.gui;

import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonRewardEntry;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.item.service.ItemStackFactory;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DungeonRewardGuiTest extends MockBukkitTestBase {
    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 6. クリア報酬と30秒回収
     * 検証契約: GUI holderは描画時のslotとclaim ID対応をimmutableに固定し、範囲外slotを報酬へ解決しない。
     */
    @Test
    void holderPinsVisibleClaimIdsAtRenderTime() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        List<UUID> source = new ArrayList<>(List.of(first, second));
        DungeonRewardGui.Holder holder = new DungeonRewardGui.Holder(
                UUID.randomUUID(), UUID.randomUUID(), 0, source);

        source.removeFirst();

        assertEquals(first, holder.claimIdAt(0));
        assertEquals(second, holder.claimIdAt(1));
        assertNull(holder.claimIdAt(-1));
        assertNull(holder.claimIdAt(DungeonRewardGui.CONTENT_SIZE));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/32-dungeon/32_3-処理契約.md
     * 章・見出し: # 32_3-処理契約 > ## 6. クリア報酬と30秒回収
     * 検証契約: 抽選済みの実報酬GUIは数量と受取操作だけを表示し、設定確率を公開しない。
     */
    @Test
    void hidesConfiguredProbabilityFromActualRewardLore() {
        ItemService itemService = mock(ItemService.class);
        ItemStackFactory factory = mock(ItemStackFactory.class);
        ItemModel model = mock(ItemModel.class);
        when(itemService.findLoadedById("reward_item")).thenReturn(model);
        when(model.getMaxStack()).thenReturn(64);
        when(factory.create(model, 2)).thenReturn(new ItemStack(Material.DIAMOND, 2));
        DungeonRewardGui gui = new DungeonRewardGui(itemService, factory);
        var player = server().addPlayer();

        gui.open(player, UUID.randomUUID(), "Test Dungeon", List.of(
                new DungeonRewardEntry(UUID.randomUUID(), "reward_item", 2, 12.34D)), 0);

        ItemStack rendered = player.getOpenInventory().getTopInventory().getItem(0);
        List<String> lore = rendered.getItemMeta().lore().stream()
                .map(PlainTextComponentSerializer.plainText()::serialize)
                .toList();
        assertFalse(lore.stream().anyMatch(line -> line.contains("設定確率") || line.contains("12.34")));
    }
}
