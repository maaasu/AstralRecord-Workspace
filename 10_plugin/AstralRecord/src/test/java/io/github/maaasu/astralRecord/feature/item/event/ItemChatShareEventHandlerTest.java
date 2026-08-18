package io.github.maaasu.astralRecord.feature.item.event;

import io.github.maaasu.astralRecord.feature.item.service.ItemChatShareService;
import io.github.maaasu.astralRecord.support.MockBukkitTestBase;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ItemChatShareEventHandlerTest extends MockBukkitTestBase {

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-イベント.md
     * 章・見出し: # 04_3-イベント > ## 6. 所持アイテムのチャット共有入力
     * 検証契約: 通常所持品画面のAstralRecord itemに対するShift+Qは全体チャット共有後にcancelされ、アイテムをドロップしない。
     */
    @Test
    void controlDropOfAstralItemCancelsVanillaDrop() {
        PlayerMock player = server().addPlayer();
        ItemStack item = astralItem("star_sword", "星詠みの剣");
        InventoryClickEvent event = controlDropEvent(player, item);
        ItemChatShareService itemChatShareService = mock(ItemChatShareService.class);
        when(itemChatShareService.share(player, item)).thenReturn(true);
        ItemChatShareEventHandler handler = new ItemChatShareEventHandler(itemChatShareService);

        handler.onInventoryClick(event);

        verify(itemChatShareService).share(player, item);
        verify(event).setCancelled(true);
    }

    /**
     * 設計入力: 00_docs/10_Plugin設計書/feature/04-item/3-メソッド仕様/04_3-イベント.md
     * 章・見出し: # 04_3-イベント > ## 6. 所持アイテムのチャット共有入力
     * 検証契約: AstralRecord itemではない所持品へのShift+Qは共有入力として扱わず、通常のドロップ操作を妨げない。
     */
    @Test
    void controlDropOfNonAstralItemKeepsVanillaDrop() {
        PlayerMock player = server().addPlayer();
        InventoryClickEvent event = controlDropEvent(player, new ItemStack(Material.DIAMOND));
        ItemChatShareEventHandler handler = new ItemChatShareEventHandler(new ItemChatShareService());

        handler.onInventoryClick(event);

        verify(event, never()).setCancelled(true);
    }

    private @NotNull InventoryClickEvent controlDropEvent(@NotNull PlayerMock player, @NotNull ItemStack item) {
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        InventoryView view = mock(InventoryView.class);
        when(event.getClick()).thenReturn(ClickType.CONTROL_DROP);
        when(event.getView()).thenReturn(view);
        when(view.getType()).thenReturn(InventoryType.CRAFTING);
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getClickedInventory()).thenReturn(player.getInventory());
        when(event.getCurrentItem()).thenReturn(item);
        return event;
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
