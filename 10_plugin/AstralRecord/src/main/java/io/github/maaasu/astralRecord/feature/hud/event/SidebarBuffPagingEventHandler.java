package io.github.maaasu.astralRecord.feature.hud.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.hud.service.PlayerHudService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.event.PlayerRuntimeDiscardEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.jetbrains.annotations.NotNull;

/** 手の持ち替え入力をサイドバーの効果ページ切替へ変換します。 */
public final class SidebarBuffPagingEventHandler extends AbstractEventHandler {
    private final PlayerHudService hudService;

    /** @param hudService 自分のサイドバー表示を更新するサービス */
    public SidebarBuffPagingEventHandler(@NotNull PlayerHudService hudService) {
        this.hudService = hudService;
    }

    /**
     * 通常プレイ中のFを次ページ、スニーク中のFを前ページとして扱います。
     * 管理インベントリがLOWESTで持ち替えを拒否するため、cancel済み入力も対象とします。
     * @param event 手の持ち替え入力
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
    public void onSwapHands(@NotNull PlayerSwapHandItemsEvent event) {
        event.setCancelled(true);
        var player = event.getPlayer();
        if (player.getOpenInventory().getType() != InventoryType.CRAFTING) {
            return;
        }
        var astPlayer = AstPlayerCache.get(player);
        if (astPlayer != null) {
            hudService.changeSidebarBuffPage(astPlayer, player.isSneaking() ? -1 : 1);
        }
    }

    /** @param event 退出したプレイヤーのページ状態を破棄するイベント */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(@NotNull PlayerQuitEvent event) {
        hudService.clearSidebarBuffPage(event.getPlayer().getUniqueId());
    }

    /** @param event ワールド移動後に先頭ページへ戻すイベント */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(@NotNull PlayerChangedWorldEvent event) {
        hudService.clearSidebarBuffPage(event.getPlayer().getUniqueId());
    }

    /** @param event 復旧時に保存を伴わずページ状態を破棄するイベント */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRuntimeDiscard(@NotNull PlayerRuntimeDiscardEvent event) {
        hudService.clearSidebarBuffPage(event.getPlayer().getUniqueId());
    }
}
