package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ItemChatShareServiceTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-コマンド.md
     * 章・見出し: # 04_3-コマンド > ## 9. showitem コマンド
     * 検証契約: AstralRecord itemだけを所持品の表示順で重複なく補完候補にし、同じ表示名を指定した場合は最初の実アイテムを解決する。
     */
    @Test
    void listsAndResolvesAstralInventoryItemsByDisplayName() {
        ItemChatShareService service = new ItemChatShareService();
        ItemStack first = astralItem("star_sword", "星詠みの剣");
        ItemStack duplicate = astralItem("star_sword", "星詠みの剣");
        ItemStack second = astralItem("moon_staff", "月影の杖");
        ItemStack vanilla = new ItemStack(Material.DIAMOND);
        ItemStack[] contents = {first, duplicate, vanilla, second};

        assertEquals(List.of("星詠みの剣", "月影の杖"), service.getShareableItemNames(contents));
        assertSame(first, service.findShareableItem(contents, "星詠みの剣"));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-コマンド.md
     * 章・見出し: # 04_3-コマンド > ## 9. showitem コマンド
     * 検証契約: /si の候補・解決名ではアイテム名の ◆ 装飾を除き、装飾なしの名前を使用する。
     */
    @Test
    void removesDecorativeMarkerFromShareableName() {
        ItemChatShareService service = new ItemChatShareService();
        ItemStack item = astralItem("star_sword", "◆ 星詠みの剣");
        ItemStack[] contents = {item};

        assertEquals(List.of("星詠みの剣"), service.getShareableItemNames(contents));
        assertSame(item, service.findShareableItem(contents, "星詠みの剣"));
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/04_2-ユースケース.md
     * 章・見出し: # 04_2-ユースケース > ## 8.1. 所持アイテムをチャット共有する
     * 検証契約: showitem の共有 tooltip は clone を正規化し、インベントリ表示専用のクリック案内を除去しながら元の所持 ItemStack を変更しない。
     */
    @Test
    void sharedTooltipOmitsInventoryOnlyActionLoreWithoutMutatingInventoryItem() {
        ItemChatShareService service = new ItemChatShareService();
        ItemStack item = astralItem("star_sword", "星詠みの剣");
        ItemMeta meta = item.getItemMeta();
        meta.lore(new ArrayList<>(List.of(
            Component.text("通常のアイテム説明"),
            Component.text("クリックでホットバースロットに設定"),
            Component.text("クリックで使用"),
            Component.text("クリックでホットバースロットに設定（武器専用）"),
            Component.text("クリックで使用（説明用の正規 lore）")
        )));
        item.setItemMeta(meta);
        List<Component> originalLore = new ArrayList<>(item.getItemMeta().lore());
        Player player = mock(Player.class);
        PlayerMessageService messageService = mock(PlayerMessageService.class);

        try (MockedStatic<PlayerMessageService> messages =
                 org.mockito.Mockito.mockStatic(PlayerMessageService.class)) {
            messages.when(PlayerMessageService::getInstance).thenReturn(messageService);

            assertTrue(service.share(player, item));
        }

        ArgumentCaptor<ItemStack> tooltipCaptor = ArgumentCaptor.forClass(ItemStack.class);
        verify(messageService).broadcastGlobalItemChat(
            org.mockito.ArgumentMatchers.eq(player),
            org.mockito.ArgumentMatchers.eq("星詠みの剣"),
            tooltipCaptor.capture()
        );
        List<String> tooltipLore = tooltipCaptor.getValue().getItemMeta().lore().stream()
            .map(PlainTextComponentSerializer.plainText()::serialize)
            .toList();
        assertEquals(List.of(
            "通常のアイテム説明",
            "クリックでホットバースロットに設定（武器専用）",
            "クリックで使用（説明用の正規 lore）"
        ), tooltipLore);
        assertEquals(originalLore, item.getItemMeta().lore());
        assertNotSame(item, tooltipCaptor.getValue());
    }

    private @NotNull ItemStack astralItem(@NotNull String itemId, @NotNull String displayName) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(displayName));
        meta.getPersistentDataContainer().set(
            new NamespacedKey("astralrecord", "item_id"),
            PersistentDataType.STRING,
            itemId
        );
        item.setItemMeta(meta);
        return item;
    }
}
