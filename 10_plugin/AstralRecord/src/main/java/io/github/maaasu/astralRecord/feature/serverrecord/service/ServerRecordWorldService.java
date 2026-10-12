package io.github.maaasu.astralRecord.feature.serverrecord.service;

import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.serverrecord.repository.ServerRecordReturnRepository;
import io.github.maaasu.astralRecord.feature.textdisplay.model.TextDisplayPlacement;
import io.github.maaasu.astralRecord.feature.world.model.WorldType;
import io.github.maaasu.astralRecord.feature.world.service.WorldService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 共有閲覧ワールドへの入場と、訪問前の座標・方向への帰還を管理します。 */
public final class ServerRecordWorldService {
    public static final String WORLD_ID = "server_record";
    private final Plugin plugin;
    private final WorldService worldService;
    private final ServerRecordReturnRepository repository;
    private final Map<UUID, TextDisplayPlacement> returns = new HashMap<>();
    private final Map<UUID, UUID> pending = new HashMap<>();
    private boolean available;
    private String fallbackWorldId;

    /** @param plugin プラグイン本体 @param worldService マスターワールド管理 */
    public ServerRecordWorldService(Plugin plugin, WorldService worldService) {
        this.plugin = plugin;
        this.worldService = worldService;
        repository = new ServerRecordReturnRepository(plugin);
    }

    /** @param fallbackWorldId 帰還先を読み込めない場合の初期スポーンマスタID */
    public void start(String fallbackWorldId) {
        this.fallbackWorldId = fallbackWorldId;
        try {
            returns.clear();
            returns.putAll(repository.loadAll());
            available = true;
        } catch (RuntimeException failure) {
            available = false;
            Logger.log(LogId.E_7801, failure, "server_record_returns.yml");
        }
    }

    /** @param world 判定ワールド @return 閲覧専用ワールドか */
    public boolean isRecordWorld(World world) {
        var data = world == null ? null : worldService.findByBukkitWorld(world);
        return data != null && WORLD_ID.equals(data.id());
    }

    /** @param player 入場対象。拠点からのみ入場し、指定スポーンのyaw90/pitch0を適用します */
    public void enter(Player player) {
        if (pending.containsKey(player.getUniqueId())) return;
        if (isRecordWorld(player.getWorld())) return;
        var source = worldService.findByBukkitWorld(player.getWorld());
        if (source == null || source.worldType() != WorldType.BASE) {
            message(player, PlayerMsgId.P_7809);
            return;
        }
        var data = worldService.getById(WORLD_ID);
        Location spawn = data == null ? null : worldService.resolveOrLoadSpawnLocation(data);
        if (!available || spawn == null) {
            message(player, PlayerMsgId.P_7808);
            return;
        }
        if (!rememberEntry(player, player.getLocation(), spawn)) return;
        teleport(player, spawn, false);
    }

    /**
     * NPC以外からの転送も含め、別ワールドから入場する直前の位置を保存します。
     * @param player 訪問者
     * @param from 移動元
     * @param to 移動先
     * @return 訪問前の位置を保存できた場合true
     */
    public boolean rememberEntry(Player player, Location from, Location to) {
        if (!isRecordWorld(to.getWorld()) || isRecordWorld(from.getWorld())) return true;
        if (!available) return false;
        var saved = TextDisplayPlacement.from(player.getUniqueId().toString(), "", from);
        if (saved.equals(returns.get(player.getUniqueId()))) return true;
        var previous = returns.put(player.getUniqueId(), saved);
        try {
            repository.saveAll(returns);
            return true;
        } catch (RuntimeException failure) {
            if (previous == null) returns.remove(player.getUniqueId());
            else returns.put(player.getUniqueId(), previous);
            Logger.log(LogId.E_7801, failure, "server_record_returns.yml");
            message(player, PlayerMsgId.P_7805);
            return false;
        }
    }

    /** @param player 帰還対象。失敗時は保存した位置を残し次の接触で再試行します */
    public void back(Player player) {
        if (!available || !isRecordWorld(player.getWorld()) || pending.containsKey(player.getUniqueId())) return;
        var saved = returns.get(player.getUniqueId());
        Location target = saved == null ? null : saved.toLocation();
        if (target == null && saved != null) {
            var data = worldService.getAll().stream()
                    .filter(candidate -> candidate.baseWorldPath().replace('\\', '/').equals(saved.worldName().replace('\\', '/')))
                    .findFirst().orElse(null);
            if (data != null) {
                World world = worldService.resolveOrLoadWorld(data);
                if (world != null) target = new Location(world, saved.x(), saved.y(), saved.z(), saved.yaw(), saved.pitch());
            }
        }
        if (target == null || isRecordWorld(target.getWorld())) {
            var data = worldService.getById(fallbackWorldId);
            target = data == null || WORLD_ID.equals(data.id()) ? null : worldService.resolveOrLoadSpawnLocation(data);
            if (target == null) {
                message(player, PlayerMsgId.P_7812);
                return;
            }
            message(player, PlayerMsgId.P_7813);
        }
        teleport(player, target, true);
    }

    /** @param player 破棄されたプレイヤーの未完了転送を失効させます。帰還位置は再接続用に保持します */
    public void clearPending(Player player) { pending.remove(player.getUniqueId()); }

    /** 停止後に遅延転送を実行しないよう全要求を失効させます。 */
    public void stop() { available = false; pending.clear(); }

    /** @param player 対象 @param target 転送先 @param returning 帰還時か */
    private void teleport(Player player, Location target, boolean returning) {
        UUID id = player.getUniqueId();
        UUID token = UUID.randomUUID();
        if (pending.putIfAbsent(id, token) != null) return;
        World source = player.getWorld();
        worldService.teleportPlayerWithTargetDirectionAsync(player, target, null,
                () -> available && token.equals(pending.get(id)) && !player.isDead() && player.getWorld().equals(source))
                .whenComplete((success, failure) -> {
                    if (!plugin.isEnabled()) return;
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (!token.equals(pending.get(id))) return;
                        boolean completed = failure == null && Boolean.TRUE.equals(success);
                        if (completed && returning) {
                            returns.remove(id);
                            try { repository.saveAll(returns); }
                            catch (RuntimeException saveFailure) { Logger.log(LogId.E_7801, saveFailure, "server_record_returns.yml"); }
                        }
                        if (player.isOnline()) message(player, completed ? (returning ? PlayerMsgId.P_7811 : PlayerMsgId.P_7810) : PlayerMsgId.P_7812);
                        // 接触・連打による重複要求と失敗ログの連発を抑えます。
                        Bukkit.getScheduler().runTaskLater(plugin, () -> pending.remove(id, token), 20L);
                    });
                });
    }

    /** @param player 送信先 @param id 通知リソース */
    private void message(Player player, PlayerMsgId id) { PlayerMessageService.getInstance().send(player, id); }
}
