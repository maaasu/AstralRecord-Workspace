package io.github.maaasu.astralRecord.feature.mob.service;

import io.github.maaasu.astralRecord.feature.mob.model.MobCategory;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.model.MobTemplate;
import io.github.maaasu.astralRecord.feature.mob.model.NpcPlacement;
import io.github.maaasu.astralRecord.feature.mob.repository.NpcPlacementRepository;
import org.bukkit.Location;
import org.bukkit.World;
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

    private final MobService mobService;
    private final NpcPlacementRepository repository;
    private final Map<String, NpcPlacement> placements = new LinkedHashMap<>();
    private final Map<String, UUID> spawnedByLocation = new LinkedHashMap<>();
    private boolean dirty;

    /**
     * サービスを初期化します。
     *
     * @param mobService Mob 管理サービス
     * @param repository NPC 配置リポジトリ
     */
    public NpcPlacementService(
            @NotNull MobService mobService,
            @NotNull NpcPlacementRepository repository
    ) {
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
        }

        Location location = placement.toLocation();
        if (location == null || location.getWorld() == null) {
            return null;
        }

        MobInstance instance = mobService.spawn(placement.npcId(), location);
        if (instance != null) {
            spawnedByLocation.put(placement.locationKey(), instance.instanceId());
        }
        return instance;
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
    }
}
