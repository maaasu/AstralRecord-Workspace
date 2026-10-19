package io.github.maaasu.astralRecord.feature.defense;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.WorldLoadEvent;

/** 専用会場を保護し、既存の参加者だけに入場・再接続を許可します。 */
public final class DefenseListener implements Listener {
    private final DefenseService service;

    /** @param service メインスレッドの開催所有者 */
    public DefenseListener(DefenseService service) { this.service = service; }

    /** @param event ブロック破壊。防衛会場の地形は変更しません */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) { if (service.isArena(event.getBlock().getWorld())) event.setCancelled(true); }

    /** @param event ブロック設置。経路封鎖を防ぎます */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) { if (service.isArena(event.getBlock().getWorld())) event.setCancelled(true); }

    /** @param event エンティティ爆発。地形だけ保護し戦闘ダメージは共通経路へ委ねます */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onExplode(EntityExplodeEvent event) { if (service.isArena(event.getLocation().getWorld())) event.blockList().clear(); }

    /** @param event ブロック由来の爆発 */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockExplode(BlockExplodeEvent event) { if (service.isArena(event.getBlock().getWorld())) event.blockList().clear(); }

    /** @param event エンティティによる地形変更 */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChange(EntityChangeBlockEvent event) { if (service.isArena(event.getBlock().getWorld())) event.setCancelled(true); }

    /** @param event 火による地形消失 */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) { if (service.isArena(event.getBlock().getWorld())) event.setCancelled(true); }

    /** @param event 会場の通常スポーン。PluginによるCUSTOM生成だけを許可します */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (service.isArena(event.getLocation().getWorld()) && event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.CUSTOM)
            event.setCancelled(true);
    }

    /** @param event 外部からの途中入場を拒否します。帰還は妨げません */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (event.getTo() != null && service.isArena(event.getTo().getWorld())
            && !service.isParticipant(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    /** @param event 接続直後。本人の状態復元を開催側へ委ねます */
    @EventHandler public void onJoin(PlayerJoinEvent event) { service.onJoin(event.getPlayer()); }

    /** @param event 切断。個人死亡と復帰猶予を維持します */
    @EventHandler public void onQuit(PlayerQuitEvent event) { service.onQuit(event.getPlayer()); }

    /** @param event 通常のワールド退出 */
    @EventHandler public void onWorld(PlayerChangedWorldEvent event) { service.onWorldChanged(event.getPlayer()); }

    /** @param event 再起動前の会場個体を回収します */
    @EventHandler public void onLoad(WorldLoadEvent event) { service.removeStaleEntities(event.getWorld()); }
}
