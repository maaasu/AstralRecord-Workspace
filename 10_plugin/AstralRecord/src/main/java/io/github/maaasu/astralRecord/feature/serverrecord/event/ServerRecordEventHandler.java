package io.github.maaasu.astralRecord.feature.serverrecord.event;

import io.github.maaasu.astralRecord.core.event.EventHandler;
import io.github.maaasu.astralRecord.feature.player.event.PlayerRuntimeDiscardEvent;
import io.github.maaasu.astralRecord.feature.serverrecord.service.ServerRecordDisplayService;
import io.github.maaasu.astralRecord.feature.serverrecord.service.ServerRecordWorldService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.plugin.Plugin;

/** 閲覧ワールドでのスカルク接触と、ロードされたチャンクの表示を扱います。 */
public final class ServerRecordEventHandler implements EventHandler {
    private final Plugin plugin;
    private final ServerRecordWorldService visits;
    private final ServerRecordDisplayService displays;

    /** @param plugin プラグイン本体 @param visits 入退場処理 @param displays 表示管理 */
    public ServerRecordEventHandler(Plugin plugin, ServerRecordWorldService visits, ServerRecordDisplayService displays) {
        this.plugin = plugin;
        this.visits = visits;
        this.displays = displays;
    }

    /** @param event 入場前の位置を保存します。保存失敗時の入場は取り消します */
    @org.bukkit.event.EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (event.getTo() != null && !visits.rememberEntry(event.getPlayer(), event.getFrom(), event.getTo())) event.setCancelled(true);
    }

    /**
     * 足元と体の側面で固体スカルクとの接触を検出します。向きだけの移動は走査しません。
     * @param event 座標が変化した移動
     */
    @org.bukkit.event.EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event instanceof PlayerTeleportEvent || event.getTo() == null || !event.hasChangedPosition()) return;
        if (touchesSculk(event.getPlayer(), event.getTo())) scheduleReturn(event.getPlayer());
    }

    /** @param event ワールド移動後の初回接触を検出します */
    @org.bukkit.event.EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) { checkAfterArrival(event.getPlayer()); }

    /** @param event 再接続後も保存した帰還位置を使用します */
    @org.bukkit.event.EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) { checkAfterArrival(event.getPlayer()); }

    /** @param event 切断前の転送を失効させます */
    @org.bukkit.event.EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) { visits.clearPending(event.getPlayer()); }

    /** @param event アカウント破棄前の転送を失効させます */
    @org.bukkit.event.EventHandler(priority = EventPriority.MONITOR)
    public void onRuntimeDiscard(PlayerRuntimeDiscardEvent event) { visits.clearPending(event.getPlayer()); }

    /** @param event チャンク読込後に非永続表示を再生成します */
    @org.bukkit.event.EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) { displays.spawnChunk(event.getChunk()); }

    /** @param event チャンク解放時の毎tick再生成を防ぎます */
    @org.bukkit.event.EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkUnload(ChunkUnloadEvent event) { displays.unloadChunk(event.getChunk()); }

    /** @param event ワールド解放前に表示を破棄します */
    @org.bukkit.event.EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldUnload(WorldUnloadEvent event) { displays.unloadWorld(event.getWorld()); }

    /** @param player 接触確認の対象 */
    private void checkAfterArrival(Player player) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && touchesSculk(player, player.getLocation())) visits.back(player);
        });
    }

    /** @param player 次tickに帰還する対象。collision処理中には転送しません */
    private void scheduleReturn(Player player) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && touchesSculk(player, player.getLocation())) visits.back(player);
        });
    }

    /** @param player 接触対象 @param location 移動後の足位置 @return スカルクに接触した場合true */
    private boolean touchesSculk(Player player, Location location) {
        if (!visits.isRecordWorld(location.getWorld())) return false;
        double halfWidth = player.getWidth() / 2.0 + 0.03;
        int minX = (int) Math.floor(location.getX() - halfWidth);
        int maxX = (int) Math.floor(location.getX() + halfWidth);
        int minY = (int) Math.floor(location.getY() - 0.04);
        int maxY = (int) Math.floor(location.getY() + player.getHeight() - 0.01);
        int minZ = (int) Math.floor(location.getZ() - halfWidth);
        int maxZ = (int) Math.floor(location.getZ() + halfWidth);
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) {
            if (!location.getWorld().isChunkLoaded(x >> 4, z >> 4)) continue;
            for (int y = minY; y <= maxY; y++) {
                if (location.getWorld().getBlockAt(x, y, z).getType() == Material.SCULK) return true;
            }
        }
        return false;
    }
}
