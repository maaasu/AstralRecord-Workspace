package io.github.maaasu.astralRecord.feature.skill.service;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.jetbrains.annotations.NotNull;

/** 成立した持ち替えとワールド移動で星糸の飛翔を終了し、退出時だけセッションを破棄します。 */
public final class RangedStarweaveLifecycleListener implements Listener {
    private final RangedStarweaveRuntimeService runtime;

    /** @param runtime 星糸追矢の所有者別状態 */
    public RangedStarweaveLifecycleListener(@NotNull RangedStarweaveRuntimeService runtime) { this.runtime = runtime; }

    /** @param event 成立した主手スロット変更 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeldSlotChange(PlayerItemHeldEvent event) { runtime.clearOwner(event.getPlayer().getUniqueId()); }

    /** @param event 成立した両手の交換 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHandSwap(PlayerSwapHandItemsEvent event) { runtime.clearOwner(event.getPlayer().getUniqueId()); }

    /** 主手が空になった投棄だけを終了条件にします。@param event 成立した投棄 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (event.getPlayer().getInventory().getItemInMainHand().getType().isAir()) {
            runtime.clearOwner(event.getPlayer().getUniqueId());
        }
    }

    /** @param event 所有者のワールド移動 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) { runtime.clearOwner(event.getPlayer().getUniqueId()); }

    /** @param event 所有者の退出。バインド保持ではなくセッション全体を破棄します */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) { runtime.forgetOwner(event.getPlayer().getUniqueId()); }
}
