package io.github.maaasu.astralRecord.feature.world.service;

import io.github.maaasu.astralRecord.feature.world.model.WorldMasterData;
import io.github.maaasu.astralRecord.feature.world.repository.WorldRepository;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.WorldCreator;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        loadRegisteredBukkitWorlds(worlds);
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
        for (String candidate : baseWorldNameCandidates(data)) {
            org.bukkit.World world = Bukkit.getWorld(candidate);
            if (world != null) {
                return world;
            }
        }

        File baseWorldFolder = resolveWorldFolder(normalizeWorldPath(data.baseWorldPath()));
        for (org.bukkit.World world : Bukkit.getWorlds()) {
            if (sameFile(world.getWorldFolder(), baseWorldFolder)) {
                return world;
            }
        }
        return Bukkit.getWorld(data.id());
    }

    private int loadRegisteredBukkitWorlds(@NotNull Collection<WorldMasterData> worlds) {
        int loadedCount = 0;
        for (WorldMasterData world : worlds) {
            org.bukkit.World loaded = loadBukkitWorld(world);
            if (loaded != null) {
                loadedCount++;
            }
        }
        Logger.log(LogId.I_5751, loadedCount, worlds.size());
        return loadedCount;
    }

    @Nullable
    private org.bukkit.World loadBukkitWorld(@NotNull WorldMasterData data) {
        org.bukkit.World existing = resolveLoadedWorld(data);
        if (existing != null) {
            Logger.log(LogId.D_5751, data.id(), existing.getName());
            return existing;
        }

        String worldName = normalizeWorldPath(data.baseWorldPath());
        if (worldName.isBlank()) {
            Logger.log(LogId.W_5752, data.id(), data.baseWorldPath());
            return null;
        }

        File worldFolder = resolveWorldFolder(worldName);
        if (!new File(worldFolder, "level.dat").isFile()) {
            Logger.log(LogId.W_5752, data.id(), worldName);
            return null;
        }

        try {
            org.bukkit.World created = Bukkit.createWorld(new WorldCreator(worldName));
            if (created == null) {
                Logger.log(LogId.W_5752, data.id(), worldName);
                return null;
            }
            Logger.log(LogId.D_5751, data.id(), created.getName());
            return created;
        } catch (RuntimeException e) {
            Logger.log(LogId.E_5753, e, data.id() + " path=" + worldName);
            return null;
        }
    }

    @NotNull
    private Set<String> baseWorldNameCandidates(@NotNull WorldMasterData data) {
        Set<String> candidates = new LinkedHashSet<>();
        String normalizedPath = normalizeWorldPath(data.baseWorldPath());
        addCandidate(candidates, normalizedPath);
        addCandidate(candidates, new File(normalizedPath).getName());
        return candidates;
    }

    private static void addCandidate(@NotNull Set<String> candidates, @Nullable String candidate) {
        if (candidate != null && !candidate.isBlank()) {
            candidates.add(candidate);
        }
    }

    @NotNull
    private static String normalizeWorldPath(@NotNull String path) {
        return path.trim()
                .replace('\\', '/')
                .replaceAll("/{2,}", "/")
                .replaceFirst("^\\./", "");
    }

    @NotNull
    private static File resolveWorldFolder(@NotNull String worldName) {
        File folder = new File(worldName);
        return folder.isAbsolute() ? folder : new File(Bukkit.getWorldContainer(), worldName);
    }

    private static boolean sameFile(@NotNull File left, @NotNull File right) {
        try {
            return left.getCanonicalFile().equals(right.getCanonicalFile());
        } catch (IOException e) {
            return left.getAbsoluteFile().equals(right.getAbsoluteFile());
        }
    }
}
