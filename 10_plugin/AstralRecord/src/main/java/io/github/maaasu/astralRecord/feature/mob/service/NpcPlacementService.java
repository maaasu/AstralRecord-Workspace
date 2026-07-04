package io.github.maaasu.astralRecord.feature.mob.service;

import io.github.maaasu.astralRecord.feature.mob.model.MobCategory;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.model.MobTemplate;
import io.github.maaasu.astralRecord.feature.mob.model.NpcPlacement;
import io.github.maaasu.astralRecord.feature.mob.repository.NpcPlacementRepository;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * NPC の配置座標とスポーン状態を管理します。
 */
public final class NpcPlacementService {

    private final Plugin plugin;
    private final MobService mobService;
    private final NpcPlacementRepository repository;
    private final Map<String, NpcPlacement> placements = new LinkedHashMap<>();
    private final Map<String, UUID> spawnedByLocation = new LinkedHashMap<>();
    private final Map<String, ChunkTicket> chunkTicketByLocation = new LinkedHashMap<>();
    private final Map<ChunkTicket, Integer> chunkTicketRefs = new LinkedHashMap<>();
    private boolean dirty;

    /**
     * サービスを初期化します。
     *
     * @param mobService Mob 管理サービス
     * @param repository NPC 配置リポジトリ
     */
    public NpcPlacementService(
            @NotNull Plugin plugin,
            @NotNull MobService mobService,
            @NotNull NpcPlacementRepository repository
    ) {
        this.plugin = plugin;
        this.mobService = mobService;
        this.repository = repository;
    }

    /**
     * NPC 配置 YAML を読み込み、ロード済みワールドの NPC をスポーンします。
     *
     * @return 読み込んだ配置数
     */
    public int loadAll() {
        destroySpawnedNpcs();
        releaseAllChunkTickets();
        placements.clear();
        spawnedByLocation.clear();
        for (NpcPlacement placement : repository.loadAll()) {
            placements.put(placement.locationKey(), placement);
        }
        dirty = false;
        spawnLoadedWorlds();
        return placements.size();
    }

    /**
     * 現在ロード済みのワールドに紐づく NPC 配置をすべてスポーンします。
     *
     * @return 新たにスポーンした NPC 数
     */
    public int spawnLoadedWorlds() {
        int count = 0;
        for (NpcPlacement placement : placements.values()) {
            if (spawn(placement) != null) {
                count++;
            }
        }
        return count;
    }

    /**
     * 未スポーンの NPC 配置が残っているかを返します。
     *
     * @return 1 件以上の未スポーン配置がある場合は {@code true}
     */
    public boolean hasPendingPlacements() {
        for (NpcPlacement placement : placements.values()) {
            UUID instanceId = spawnedByLocation.get(placement.locationKey());
            if (instanceId == null || mobService.getInstance(instanceId) == null) {
                return true;
            }
        }
        return false;
    }

    /**
     * NPC 配置を登録して即時スポーンします。
     *
     * @param npcId    NPC マスタ ID
     * @param location 配置座標
     * @return 登録とスポーンに成功した場合は生成インスタンス、失敗時は null
     */
    @Nullable
    public MobInstance place(@NotNull String npcId, @NotNull Location location) {
        MobTemplate template = mobService.findTemplate(npcId);
        if (template == null || template.category() != MobCategory.NPC) {
            return null;
        }

        NpcPlacement placement = NpcPlacement.from(npcId, location);
        removeSpawned(placement.locationKey());
        placements.put(placement.locationKey(), placement);
        dirty = true;
        saveIfDirty();
        return spawn(placement);
    }

    /**
     * 指定 NPC テンプレート ID に一致する配置をすべて削除します。
     *
     * @param npcId 削除対象 NPC テンプレート ID
     * @return 削除した配置件数
     */
    public int removeByNpcId(@NotNull String npcId) {
        List<String> targetKeys = placements.values().stream()
                .filter(placement -> placement.npcId().equals(npcId))
                .map(NpcPlacement::locationKey)
                .toList();

        int count = 0;
        for (String locationKey : targetKeys) {
            if (placements.remove(locationKey) == null) {
                continue;
            }
            removeSpawned(locationKey);
            count++;
        }

        if (count > 0) {
            dirty = true;
            saveIfDirty();
        }
        return count;
    }

    /**
     * 指定ワールドに紐づく NPC 配置をスポーンします。
     *
     * @param world ロードされたワールド
     * @return 新たにスポーンした NPC 数
     */
    public int spawnForWorld(@NotNull World world) {
        int count = 0;
        for (NpcPlacement placement : placements.values()) {
            if (!placement.worldName().equals(world.getName())) {
                continue;
            }
            if (spawn(placement) != null) {
                count++;
            }
        }
        return count;
    }

    /**
     * 登録済み NPC 配置一覧を返します。
     *
     * @return NPC 配置一覧
     */
    @NotNull
    public Collection<NpcPlacement> getPlacements() {
        return List.copyOf(placements.values());
    }

    /**
     * 登録済み NPC 配置に含まれるテンプレート ID 一覧を返します。
     *
     * @return テンプレート ID 一覧
     */
    @NotNull
    public Collection<String> getPlacedNpcIds() {
        return placements.values().stream()
                .map(NpcPlacement::npcId)
                .distinct()
                .toList();
    }

    /**
     * 保存が必要な NPC 配置を YAML に書き込みます。
     */
    public void saveIfDirty() {
        if (!dirty) {
            return;
        }
        repository.saveAll(new ArrayList<>(placements.values()));
        dirty = false;
    }

    @Nullable
    private MobInstance spawn(@NotNull NpcPlacement placement) {
        if (spawnedByLocation.containsKey(placement.locationKey())) {
            UUID currentId = spawnedByLocation.get(placement.locationKey());
            if (currentId != null && mobService.getInstance(currentId) != null) {
                return mobService.getInstance(currentId);
            }
            spawnedByLocation.remove(placement.locationKey());
            releaseChunkTicket(placement.locationKey());
        }

        Location location = prepareSpawnLocation(placement);
        if (location == null) {
            return null;
        }

        if (!retainChunkTicket(placement.locationKey(), location)) {
            return null;
        }

        MobInstance instance = mobService.spawn(placement.npcId(), location);
        if (instance != null) {
            spawnedByLocation.put(placement.locationKey(), instance.instanceId());
        } else {
            releaseChunkTicket(placement.locationKey());
        }
        return instance;
    }

    @Nullable
    private Location prepareSpawnLocation(@NotNull NpcPlacement placement) {
        Location location = placement.toLocation();
        if (location == null || location.getWorld() == null) {
            return null;
        }

        var chunk = location.getChunk();
        if (!chunk.isLoaded() && !chunk.load()) {
            return null;
        }
        return location;
    }

    private void destroySpawnedNpcs() {
        for (UUID instanceId : List.copyOf(spawnedByLocation.values())) {
            mobService.destroy(instanceId);
        }
    }

    private void removeSpawned(@NotNull String locationKey) {
        UUID instanceId = spawnedByLocation.remove(locationKey);
        if (instanceId != null) {
            mobService.destroy(instanceId);
        }
        releaseChunkTicket(locationKey);
    }

    private boolean retainChunkTicket(@NotNull String locationKey, @NotNull Location location) {
        Chunk chunk = location.getChunk();
        ChunkTicket ticket = new ChunkTicket(chunk.getWorld().getName(), chunk.getX(), chunk.getZ());
        ChunkTicket currentTicket = chunkTicketByLocation.get(locationKey);
        if (ticket.equals(currentTicket)) {
            return true;
        }
        if (currentTicket != null) {
            releaseChunkTicket(locationKey);
        }

        int refs = chunkTicketRefs.getOrDefault(ticket, 0);
        if (refs == 0) {
            try {
                chunk.addPluginChunkTicket(plugin);
            } catch (RuntimeException ex) {
                return true;
            }
        }

        chunkTicketByLocation.put(locationKey, ticket);
        chunkTicketRefs.put(ticket, refs + 1);
        return true;
    }

    private void releaseChunkTicket(@NotNull String locationKey) {
        ChunkTicket ticket = chunkTicketByLocation.remove(locationKey);
        if (ticket == null) {
            return;
        }

        int refs = chunkTicketRefs.getOrDefault(ticket, 0) - 1;
        if (refs > 0) {
            chunkTicketRefs.put(ticket, refs);
            return;
        }

        chunkTicketRefs.remove(ticket);
        World world = Bukkit.getWorld(ticket.worldName());
        if (world != null) {
            world.getChunkAt(ticket.x(), ticket.z()).removePluginChunkTicket(plugin);
        }
    }

    private void releaseAllChunkTickets() {
        for (ChunkTicket ticket : List.copyOf(chunkTicketRefs.keySet())) {
            World world = Bukkit.getWorld(ticket.worldName());
            if (world != null) {
                world.getChunkAt(ticket.x(), ticket.z()).removePluginChunkTicket(plugin);
            }
        }
        chunkTicketByLocation.clear();
        chunkTicketRefs.clear();
    }

    private record ChunkTicket(@NotNull String worldName, int x, int z) {
    }
}
