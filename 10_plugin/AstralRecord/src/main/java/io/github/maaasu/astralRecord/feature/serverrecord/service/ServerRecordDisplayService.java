package io.github.maaasu.astralRecord.feature.serverrecord.service;

import io.github.maaasu.astralRecord.feature.serverrecord.config.ServerRecordConfig;
import io.github.maaasu.astralRecord.feature.serverrecord.model.ServerRecordPlacement;
import io.github.maaasu.astralRecord.feature.serverrecord.model.ServerRecordSnapshot;
import io.github.maaasu.astralRecord.feature.serverrecord.model.ServerRecordType;
import io.github.maaasu.astralRecord.feature.serverrecord.repository.ServerRecordPlacementRepository;
import io.github.maaasu.astralRecord.feature.serverrecord.repository.ServerRecordRepository;
import io.github.maaasu.astralRecord.feature.textdisplay.model.TextDisplayPlacement;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.shared.display.DisplayAnchor;
import io.github.maaasu.astralRecord.shared.display.DisplayTextOptions;
import io.github.maaasu.astralRecord.shared.display.DisplayTextService;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** 配置を永続化し、非同期の一括応答をメインスレッドで全表示へ反映します。 */
public final class ServerRecordDisplayService {
    private final Plugin plugin;
    private final ServerRecordPlacementRepository placementsRepository;
    private final ServerRecordRepository apiRepository = new ServerRecordRepository();
    private final Map<String, ServerRecordPlacement> placements = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, DisplayTextService.ManagedTextDisplay> displays = new LinkedHashMap<>();
    private DisplayTextService displayService;
    private ServerRecordConfig config;
    private ServerRecordSnapshot snapshot;
    private CompletableFuture<ServerRecordSnapshot> pending;
    private BukkitTask refreshTask;
    private boolean stopped = true;
    private boolean failed;

    /** @param plugin プラグイン本体 */
    public ServerRecordDisplayService(Plugin plugin) {
        this.plugin = plugin;
        placementsRepository = new ServerRecordPlacementRepository(plugin, "server_records.yml");
    }

    /** @param displayService 共有表示基盤 @param config 読込済み設定 */
    public void start(DisplayTextService displayService, ServerRecordConfig config) {
        stop();
        this.displayService = displayService;
        this.config = config;
        placements.clear();
        try {
            for (ServerRecordPlacement placement : placementsRepository.loadAll()) placements.put(placement.anchor().id(), placement);
        } catch (RuntimeException failure) {
            Logger.log(LogId.E_7801, failure, "server_records.yml");
            return;
        }
        stopped = false;
        spawnLoaded();
        refreshTask = Bukkit.getScheduler().runTaskTimer(plugin, this::refresh, 1L, config.refreshIntervalSeconds() * 20L);
    }

    /** @param type 種別 @param id 配置ID（nullなら自動採番） @param location 実行者の現在位置 @return 永続化済み配置 */
    public ServerRecordPlacement place(ServerRecordType type, String id, Location location) {
        if (stopped) throw new IllegalStateException("Server record display service unavailable");
        String selectedId = id;
        if (selectedId == null) {
            int number = 1;
            do { selectedId = type.id() + "-" + number++; } while (placements.containsKey(selectedId));
        }
        ServerRecordPlacement placement = new ServerRecordPlacement(type, TextDisplayPlacement.from(selectedId, "", location));
        var candidate = new LinkedHashMap<>(placements);
        candidate.put(selectedId, placement);
        placementsRepository.saveAll(candidate.values());
        removeDisplay(selectedId);
        placements.put(selectedId, placement);
        spawn(placement);
        refresh();
        return placement;
    }

    /** @param id 配置ID @return 削除・保存に成功した場合true */
    public boolean remove(String id) {
        if (!placements.containsKey(id)) return false;
        var candidate = new LinkedHashMap<>(placements);
        candidate.remove(id);
        placementsRepository.saveAll(candidate.values());
        placements.remove(id);
        removeDisplay(id);
        return true;
    }

    /** @return 永続化されている配置 */
    public Collection<ServerRecordPlacement> getPlacements() { return List.copyOf(placements.values()); }

    /** 非同期要求の多重起動を避け、全種別を一度だけ取得します。配置がない場合は問い合わせません。 */
    public void refresh() {
        if (stopped || placements.isEmpty() || pending != null) return;
        spawnLoaded();
        try {
            CompletableFuture<ServerRecordSnapshot> request = apiRepository.fetchAsync(config.limit());
            pending = request;
            request.whenComplete((response, failure) -> {
                if (!plugin.isEnabled()) return;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (stopped || pending != request) return;
                    pending = null;
                    if (failure == null) {
                        snapshot = response;
                        failed = false;
                    } else {
                        failed = true;
                        Logger.log(LogId.W_7800, failure);
                    }
                    updateDisplays();
                });
            });
        } catch (RuntimeException failure) {
            failed = true;
            Logger.log(LogId.W_7800, failure);
            updateDisplays();
        }
    }

    /** ロード済みチャンクだけに表示を復元します。未使用ワールドの強制ロードは行いません。 */
    public void spawnLoaded() {
        if (stopped) return;
        for (ServerRecordPlacement placement : placements.values()) spawn(placement);
    }

    /** @param chunk 読み込まれたチャンク。その中の配置だけを復元します */
    public void spawnChunk(Chunk chunk) {
        if (stopped) return;
        for (ServerRecordPlacement placement : placements.values()) {
            var anchor = placement.anchor();
            if (anchor.worldName().equals(chunk.getWorld().getName())
                    && ((int) Math.floor(anchor.x()) >> 4) == chunk.getX()
                    && ((int) Math.floor(anchor.z()) >> 4) == chunk.getZ()) spawn(placement);
        }
    }

    /** @param chunk 解放されるチャンク。非永続エンティティを破棄します */
    public void unloadChunk(Chunk chunk) {
        for (ServerRecordPlacement placement : placements.values()) {
            var anchor = placement.anchor();
            if (anchor.worldName().equals(chunk.getWorld().getName())
                    && ((int) Math.floor(anchor.x()) >> 4) == chunk.getX()
                    && ((int) Math.floor(anchor.z()) >> 4) == chunk.getZ()) removeDisplay(anchor.id());
        }
    }

    /** @param world 解放されるワールド */
    public void unloadWorld(World world) {
        for (ServerRecordPlacement placement : placements.values()) {
            if (placement.anchor().worldName().equals(world.getName())) removeDisplay(placement.anchor().id());
        }
    }

    /** タイマー・通信・表示を停止し、遅延完了による更新を失効させます。 */
    public void stop() {
        stopped = true;
        if (refreshTask != null) refreshTask.cancel();
        refreshTask = null;
        var request = pending;
        pending = null;
        if (request != null) request.cancel(true);
        for (String id : List.copyOf(displays.keySet())) removeDisplay(id);
    }

    /** @param placement ロード済みチャンクに作成する配置 */
    private void spawn(ServerRecordPlacement placement) {
        var anchor = placement.anchor();
        if (displays.containsKey(anchor.id())) return;
        Location location = anchor.toLocation();
        if (location == null || !location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) return;
        String text = ServerRecordTextFormatter.format(placement.type(), snapshot, failed, config.refreshIntervalSeconds());
        var options = new DisplayTextOptions(text, new Vector(), Display.Billboard.CENTER, 420, 48.0F,
                (byte) 0xFF, false, true, false, Color.fromARGB(205, 12, 16, 32), 0, 0, false, 15, 15);
        displays.put(anchor.id(), displayService.create(DisplayAnchor.fixed(location), options));
    }

    /** 成功応答または障害状態だけを差し替えます。 */
    private void updateDisplays() {
        spawnLoaded();
        for (var entry : displays.entrySet()) {
            entry.getValue().setText(ServerRecordTextFormatter.format(placements.get(entry.getKey()).type(), snapshot, failed, config.refreshIntervalSeconds()));
        }
    }

    /** @param id 破棄する配置ID */
    private void removeDisplay(String id) {
        var display = displays.remove(id);
        if (display != null) display.destroy();
    }
}
