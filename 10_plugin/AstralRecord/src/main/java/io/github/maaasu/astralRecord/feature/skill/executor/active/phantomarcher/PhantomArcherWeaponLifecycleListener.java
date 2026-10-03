package io.github.maaasu.astralRecord.feature.skill.executor.active.phantomarcher;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;

/** 武器持ち替えとワールド移動時に旧弾・霊印・準備済み幻影矢を終了します。 */
public final class PhantomArcherWeaponLifecycleListener implements Listener {
    /** @param event 主手スロット変更 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeldSlotChange(PlayerItemHeldEvent event) {
        PhantomArcherCastLifecycle.clearOwner(event.getPlayer().getUniqueId());
    }

    /** @param event 主手と副手の交換 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHandSwap(PlayerSwapHandItemsEvent event) {
        PhantomArcherCastLifecycle.clearOwner(event.getPlayer().getUniqueId());
    }

    /** 主手を空にする投棄だけを解除対象にします。@param event 所持品の投棄 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (event.getPlayer().getInventory().getItemInMainHand().getType().isAir()) {
            PhantomArcherCastLifecycle.clearOwner(event.getPlayer().getUniqueId());
        }
    }

    /** @param event ワールド移動 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        PhantomArcherCastLifecycle.clearOwner(event.getPlayer().getUniqueId());
    }
}
