package io.github.maaasu.astralRecord.feature.item.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.item.service.ItemChatShareService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.NotNull;

/**
 * 所持品画面での Shift+Q をアイテム共有入力として受け付けるイベントハンドラ。
 */
public final class ItemChatShareEventHandler extends AbstractEventHandler {
    private final ItemChatShareService itemChatShareService;

    /**
     * ItemChatShareEventHandler を初期化します。
     *
     * @param itemChatShareService アイテム共有サービス
     */
    public ItemChatShareEventHandler(@NotNull ItemChatShareService itemChatShareService) {
        this.itemChatShareService = itemChatShareService;
    }

    /**
     * 通常所持品画面の Shift+Q で共有できた場合、アイテムをドロップせずに全体チャットへ送信します。
     *
     * @param event インベントリクリックイベント
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryClick(@NotNull InventoryClickEvent event) {
        runSafely(() -> {
            if (event.getClick() != ClickType.CONTROL_DROP
                || event.getView().getType() != InventoryType.CRAFTING
                || !(event.getWhoClicked() instanceof Player player)
                || !(event.getClickedInventory() instanceof PlayerInventory)) {
                return;
            }

            if (itemChatShareService.share(player, event.getCurrentItem())) {
                event.setCancelled(true);
            }
        }, LogId.E_3002, handlerName + ":item_chat_share");
    }
}
