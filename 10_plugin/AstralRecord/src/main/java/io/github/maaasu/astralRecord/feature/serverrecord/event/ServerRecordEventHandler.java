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
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 閲覧ワールドの帰還・発射ブロック接触と、ロードされたチャンクの表示を扱います。 */
public final class ServerRecordEventHandler implements EventHandler {
    private static final double LAUNCH_HORIZONTAL_SPEED = 1.25D;
    private static final double LAUNCH_UPWARD_SPEED = 1.25D;
    private final Plugin plugin;
    private final ServerRecordWorldService visits;
    private final ServerRecordDisplayService displays;
    private final Set<UUID> emeraldContacts = new HashSet<>();
    private final Map<UUID, UUID> pendingLaunches = new HashMap<>();
    private final Map<UUID, UUID> pendingArrivalChecks = new HashMap<>();
    private boolean active = true;

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

    /** @param event 同一ワールド内の転送も含め旧発射要求を失効させ、転送後の接触を確認します */
    @org.bukkit.event.EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleportCompleted(PlayerTeleportEvent event) {
        clearLaunchState(event.getPlayer());
        if (event.getTo() != null && visits.isRecordWorld(event.getTo().getWorld())) checkAfterArrival(event.getPlayer());
    }

    /**
     * 足元と体の側面で帰還・発射ブロックへの接触を検出します。向きだけの移動は走査しません。
     * @param event 座標が変化した移動
     */
    @org.bukkit.event.EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!active || event instanceof PlayerTeleportEvent || event.getTo() == null || !event.hasChangedPosition()) return;
        Material contact = touchedSpecialBlock(event.getPlayer(), event.getTo());
        if (contact == Material.SCULK) {
            clearLaunchState(event.getPlayer());
            scheduleReturn(event.getPlayer());
        } else {
            updateEmeraldContact(event.getPlayer(), contact);
        }
    }

    /** @param event ワールド移動後の初回接触を検出します */
    @org.bukkit.event.EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        clearLaunchState(event.getPlayer());
        checkAfterArrival(event.getPlayer());
    }

    /** @param event 再接続後も保存した帰還位置を使用します */
    @org.bukkit.event.EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) { checkAfterArrival(event.getPlayer()); }

    /** @param event 切断前の転送を失効させます */
    @org.bukkit.event.EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        clearLaunchState(event.getPlayer());
        visits.clearPending(event.getPlayer());
    }

    /** @param event アカウント破棄前の転送を失効させます */
    @org.bukkit.event.EventHandler(priority = EventPriority.MONITOR)
    public void onRuntimeDiscard(PlayerRuntimeDiscardEvent event) {
        clearLaunchState(event.getPlayer());
        visits.clearPending(event.getPlayer());
    }

    /** @param event チャンク読込後に非永続表示を再生成します */
    @org.bukkit.event.EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) { displays.spawnChunk(event.getChunk()); }

    /** @param event チャンク解放時の毎tick再生成を防ぎます */
    @org.bukkit.event.EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkUnload(ChunkUnloadEvent event) { displays.unloadChunk(event.getChunk()); }

    /** @param event ワールド解放前に表示を破棄します */
    @org.bukkit.event.EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldUnload(WorldUnloadEvent event) { displays.unloadWorld(event.getWorld()); }

    /** 接触状態と旧発射要求を破棄し、停止後の遅延処理を失効させます。 */
    @Override
    public void cleanup() {
        active = false;
        emeraldContacts.clear();
        pendingLaunches.clear();
        pendingArrivalChecks.clear();
    }

    /** @param player 接触確認の対象 */
    private void checkAfterArrival(Player player) {
        if (!active) return;
        UUID id = player.getUniqueId();
        UUID token = UUID.randomUUID();
        pendingArrivalChecks.put(id, token);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!pendingArrivalChecks.remove(id, token) || !active || !player.isOnline()) return;
            Material contact = touchedSpecialBlock(player, player.getLocation());
            if (contact == Material.SCULK) {
                clearLaunchState(player);
                visits.back(player);
            } else {
                updateEmeraldContact(player, contact);
            }
        });
    }

    /** @param player 次tickに帰還する対象。collision処理中には転送しません */
    private void scheduleReturn(Player player) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (active && player.isOnline() && touchedSpecialBlock(player, player.getLocation()) == Material.SCULK) visits.back(player);
        });
    }

    /** @param player 接触対象 @param contact 接触ブロック。接触が終了するまで発射は一度だけ予約します */
    private void updateEmeraldContact(Player player, Material contact) {
        UUID id = player.getUniqueId();
        if (contact != Material.EMERALD_BLOCK) {
            emeraldContacts.remove(id);
            return;
        }
        if (!active || !emeraldContacts.add(id)) return;
        UUID token = UUID.randomUUID();
        if (pendingLaunches.putIfAbsent(id, token) != null) return;
        var sourceWorld = player.getWorld();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!pendingLaunches.remove(id, token) || !active || !player.isOnline() || player.isDead()
                    || !player.getWorld().equals(sourceWorld) || !visits.isRecordWorld(player.getWorld())
                    || touchedSpecialBlock(player, player.getLocation()) == Material.SCULK) return;
            // 上下の視線角にかかわらず、水平な視線方向と上向き成分で斜め45度へ発射します。
            double yaw = Math.toRadians(player.getLocation().getYaw());
            player.setFallDistance(0.0F);
            player.setVelocity(new Vector(-Math.sin(yaw) * LAUNCH_HORIZONTAL_SPEED,
                    LAUNCH_UPWARD_SPEED, Math.cos(yaw) * LAUNCH_HORIZONTAL_SPEED));
        });
    }

    /** @param player 転送・切断・ランタイム破棄で旧接触と発射要求を失効させる対象 */
    private void clearLaunchState(Player player) {
        emeraldContacts.remove(player.getUniqueId());
        pendingLaunches.remove(player.getUniqueId());
        pendingArrivalChecks.remove(player.getUniqueId());
    }

    /**
     * 足元と体の側面に接した対象ブロックを一度の走査で探します。
     * @param player 接触対象
     * @param location 移動後の足位置
     * @return 帰還優先のSCULK、EMERALD_BLOCK、未接触または他ワールドならAIR
     */
    private Material touchedSpecialBlock(Player player, Location location) {
        if (!visits.isRecordWorld(location.getWorld())) return Material.AIR;
        double halfWidth = player.getWidth() / 2.0 + 0.03;
        int minX = (int) Math.floor(location.getX() - halfWidth);
        int maxX = (int) Math.floor(location.getX() + halfWidth);
        int minY = (int) Math.floor(location.getY() - 0.04);
        int maxY = (int) Math.floor(location.getY() + player.getHeight() - 0.01);
        int minZ = (int) Math.floor(location.getZ() - halfWidth);
        int maxZ = (int) Math.floor(location.getZ() + halfWidth);
        boolean emerald = false;
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) {
            if (!location.getWorld().isChunkLoaded(x >> 4, z >> 4)) continue;
            for (int y = minY; y <= maxY; y++) {
                Material material = location.getWorld().getBlockAt(x, y, z).getType();
                if (material == Material.SCULK) return Material.SCULK;
                if (material == Material.EMERALD_BLOCK) emerald = true;
            }
        }
        return emerald ? Material.EMERALD_BLOCK : Material.AIR;
    }
}
