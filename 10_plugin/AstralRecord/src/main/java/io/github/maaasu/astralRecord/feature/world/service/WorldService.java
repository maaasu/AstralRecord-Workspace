package io.github.maaasu.astralRecord.feature.world.service;

import io.github.maaasu.astralRecord.feature.world.model.WorldMasterData;
import io.github.maaasu.astralRecord.feature.world.repository.WorldRepository;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * WorldMasterData をロードし、Plugin 内で参照するサービスです。
 */
public class WorldService {

    private final WorldRepository repository;
    private final Map<String, WorldMasterData> loadedWorlds = new LinkedHashMap<>();

    /**
     * WorldService を初期化します。
     *
     * @param repository WorldMasterData リポジトリ
     */
    public WorldService(@NotNull WorldRepository repository) {
        this.repository = repository;
    }

    /**
     * WorldMasterData を API から再ロードします。
     *
     * @return ロードした件数
     */
    public synchronized int loadAll() {
        List<WorldMasterData> worlds = repository.findAll().stream()
                .sorted(Comparator.comparing(WorldMasterData::id))
                .toList();
        loadedWorlds.clear();
        for (WorldMasterData world : worlds) {
            loadedWorlds.put(world.id(), world);
        }
        Logger.log(LogId.I_5750, loadedWorlds.size());
        return loadedWorlds.size();
    }

    /**
     * filebase YAML を MasterDataDB に同期してから WorldMasterData を再ロードします。
     *
     * @return ロードした件数
     */
    public synchronized int reloadFromYaml() {
        repository.seedMasterData();
        return loadAll();
    }

    /**
     * ロード済み WorldMasterData を取得します。
     *
     * @return WorldMasterData 一覧
     */
    @NotNull
    public synchronized Collection<WorldMasterData> getAll() {
        return List.copyOf(loadedWorlds.values());
    }

    /**
     * 指定 ID の WorldMasterData を取得します。
     *
     * @param worldId WorldMasterData ID
     * @return WorldMasterData。未ロードの場合は {@code null}
     */
    @Nullable
    public synchronized WorldMasterData getById(@NotNull String worldId) {
        return loadedWorlds.get(worldId);
    }

    /**
     * 定義に対応する Bukkit ロード済みワールドを取得します。
     *
     * @param data WorldMasterData
     * @return ロード済みワールド。未ロードの場合は {@code null}
     */
    @Nullable
    public org.bukkit.World resolveLoadedWorld(@NotNull WorldMasterData data) {
        org.bukkit.World byId = Bukkit.getWorld(data.id());
        if (byId != null) {
            return byId;
        }

        String baseWorldName = new File(data.baseWorldPath()).getName();
        if (baseWorldName == null || baseWorldName.isBlank()) {
            return null;
        }
        return Bukkit.getWorld(baseWorldName);
    }
}
